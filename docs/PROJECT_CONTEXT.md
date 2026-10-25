# PayCore: project context (handoff file for a new Claude chat)

> **How to use:** start a new chat, attach this file (optionally also a zip of the repo), and say:
> *"This is my project context. Read it, then continue with the 'Next steps' section."*
> **Keep it current:** ask Claude to update this file after every milestone, then commit it.

---

## 1. Who I am and the goal

- 3rd-year student at a tier-2 college (MIT Bangalore), applying for the **Visa Summer 2027 SWE internship**.
- PayCore is my main resume project: a payment-processing backend (a mini Stripe/Razorpay).
- I am a **beginner with tooling** (Docker, Maven, Git). I want exact step-by-step instructions, complete files I can
  drop in, and short explanations of the *why*. I want to move fast.
- I have **not yet studied the code deeply**; I plan to after building. Before interviews I must be able to answer the
  questions in section 8 in my own words.
- Each time we finish a step I want the **README and this context file updated and well formatted**.

## 2. Environment

| Item | Value |
|---|---|
| OS / shell | Windows 11, `cmd` |
| Tools | Docker Desktop, Python 3, Git |
| Project folder | `C:\Users\nishi\OneDrive\Documents\PROJECTS\paycore\paycore` (OneDrive causes git/Docker file-lock hiccups; plan to move to `C:\dev\paycore`) |
| Repo | https://github.com/nishitatanna2028-source/Paycore (branch `main`) |
| Run | `docker compose up --build` (Postgres, Redis, app on :8080) |
| Swagger | http://localhost:8080/swagger-ui.html (Authorize button for keys) |

**Claude's sandbox cannot compile Java** (Maven Central is blocked), so Java is untested until I run it. When something
fails I paste the first red error from the terminal. Claude *can* run Python, Lua (via a fake Redis) and k6 against mock
servers, so those parts get verified before delivery. Delivery format so far: a zip of only the new/changed files that I
extract over the project folder ("Replace the files").

## 3. Stack

Java 17 · Spring Boot 3.2.5 · Spring Data JPA + JdbcTemplate · PostgreSQL 15 · Flyway · Redis 7 · springdoc Swagger ·
Docker Compose · k6 (in Docker). Auth is a plain servlet filter (no Spring Security).

**Flyway rule: never edit an already-applied migration; add a new `V<n>__` file.**

## 4. Architecture and key decisions

Packages under `com.paycore`: `common`, `config`, `domain`, `repo`, `security`, `service`, `webhook`, `web`.

| Topic | Decision |
|---|---|
| **Ledger (V1)** | Double-entry, append-only `ledger_entries`; each transfer = DEBIT + CREDIT sharing a transaction id. Amounts are `long` minor units (paise). Account types: CUSTOMER, MERCHANT, SYSTEM. `LedgerService.transfer` is `Propagation.MANDATORY`. DB `CHECK (balance >= 0)` for non-system accounts. |
| **State machine** | CREATED → AUTHORIZED → CAPTURED → REFUNDED, or AUTHORIZED → VOIDED. Only `PaymentStatus.canMoveTo` defines transitions. |
| **Money flow** | deposit FUNDING→customer; authorize customer→escrow; capture escrow→merchant; void escrow→customer; refund merchant→customer. |
| **Concurrency** | Pessimistic `SELECT ... FOR UPDATE` on payment, then accounts in **sorted-UUID order** (deadlock prevention). Chosen over optimistic locking because escrow is a hot row and optimistic would cause retry storms. |
| **Escrow sharding (V4)** | One shared ESCROW row serialized every authorize/capture. Now `ESCROW_SHARDS` (1..16) spreads funds over accounts `00000000-0000-0000-0002-0000000000NN`; shard = hash(paymentId) % shards, stored in `payments.escrow_account_id` so capture/void use the same one. `1` = original single `...0002` ESCROW; **default is now 16** (measured 4.4x faster). Pre-V4 payments fall back to the original. |
| **Idempotency** | `INSERT ... ON CONFLICT DO NOTHING` in the SAME transaction as the action. Same key+request replays the stored response (`Idempotent-Replay: true`); same key+different body = 422; failures roll back with the key. Keys scoped per caller: `<merchantId or admin>:<key>`. |
| **Auth (V2)** | Merchant API keys `pk_...` (256-bit random, only SHA-256 stored, shown once, revocable) for `/payments/**` and `/webhooks/**`. Admin key `X-Admin-Key` (env `ADMIN_KEY`, default `dev-admin-key`) for accounts, deposits, `/admin/**`. Another merchant's payment returns 404. Implemented in `ApiAuthFilter`. |
| **Rate limiting** | Redis token bucket via atomic Lua (`redis/token_bucket.lua`), uses Redis `TIME`, per API key/admin. Default burst 100, refill 50/s. 429 + `Retry-After`. **Fails open** if Redis is down. |
| **Webhooks (V3)** | Transactional outbox: `WebhookService.enqueue` (MANDATORY tx) writes `webhook_events` in the payment's transaction. `WebhookDispatcher` polls every 1s, claims with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING` plus a lease (crash safe, multi-instance safe), sends HTTP outside any DB transaction (8 parallel). Exponential backoff base*2^(n-1) with ±20% jitter; after max attempts status `DEAD` (dead-letter), merchant can `POST /webhooks/events/{id}/replay`. At-least-once, unordered. Signed `X-PayCore-Signature: t=<ts>,v1=HMAC_SHA256(secret, "<ts>.<body>")`. |
| **SSRF guard** | `WebhookUrls` blocks private/loopback/link-local targets at registration and delivery; redirects never followed. Compose sets `WEBHOOK_ALLOW_PRIVATE=true` **for local testing only**. Dev compose also uses 5 max attempts and 1s base delay. |
| **Reconciliation** | `GET /admin/reconcile`: stored balances == ledger sums, total debits == total credits (REPEATABLE_READ snapshot). |

Events: `payment.created`, `.authorized`, `.captured`, `.voided`, `.refunded`.

## 5. Tests and tools

| Command | Purpose |
|---|---|
| `python scripts/concurrency_test.py` | 16 checks: idempotency race, overdraw race, capture race, state machine, auth, tenant isolation, idempotency scoping, key revocation, rate limiting, reconciliation |
| `python scripts/webhook_test.py` | 13 checks: retries with a flaky receiver, signatures, events API, DLQ, replay, deactivation (allow Python through Windows Firewall) |
| `docker compose --profile loadtest run --rm -e LABEL=baseline k6` | k6 load test (`loadtest/payment_flow.js`); options via `-e VUS=50 -e DURATION_S=60 -e MERCHANTS=50 -e LABEL=...`; writes `loadtest/results/<LABEL>.json` |
| `python loadtest/compare.py baseline sharded` | prints a markdown before/after table for the README |

Load test design: each VU = one customer + one merchant doing create→authorize→capture in a loop, so the only shared
resource is the escrow account (isolates the lock bottleneck). Closed loop = measures max sustainable throughput.

**Load-test gotcha (found by running it):** per-key rate limiting (100 burst, 50/s) throttles k6 because each VU uses one
merchant key; the first warm-up showed 93% errors at a fake 8,588 req/s. For load tests, uncomment the two `RATE_LIMIT_*`
lines in `docker-compose.yml` (set to 1,000,000), `docker compose up -d app`, and re-comment them afterwards.
The script flags runs with >5% errors and `compare.py` refuses to compare them.

## 6. Progress log

| Step | What | State |
|---|---|---|
| 1 | Core: ledger, state machine, idempotency, locking, reconciliation, Docker, concurrency tests | Done, tested, pushed |
| 2 | API-key auth, tenant isolation, scoped idempotency, Redis rate limiting | Done, tested, pushed |
| 3 | Webhooks: outbox, signing, retries, DLQ, replay, SSRF guard | Done, tested (webhook_test.py all passed), pushed |
| 4 | k6 load test + escrow sharding (V4) + README rewrite | **Done, measured** (results in section 7), default shards = 16 |

**Approx. completion: ~80% of the final resume project.**
Remaining: Java integration tests + CI, fraud rules, metrics/dashboards,
deployment, architecture diagram polish, frontend (low priority).

## 7. Measured results (real numbers from my machine)

Load: 50 VUs, 60 s, 50 merchants, one customer per VU, per-key rate limit disabled for the test, 0% errors both runs.
Machine: _CPU / RAM / OS (fill in)_. Raw data in `loadtest/results/baseline.json` and `sharded.json`.

| Metric | Single escrow | 16 shards | Change |
|---|---:|---:|---:|
| Throughput (req/s) | 673.4 | 2,991.1 | +344% (4.4x) |
| authorize p95 / p99 (ms) | 185.3 / 248.0 | 37.1 / 57.8 | -80% / -77% |
| capture p95 / p99 (ms) | 190.9 / 258.0 | 38.2 / 60.1 | -80% / -77% |

**Findings.** Baseline 673 req/s = ~224 checkouts/s = ~450 escrow-locking transactions/s, i.e. the single escrow row
was held ~2 ms per transaction, serially; the 185 ms p95 was queueing on that lock. Sharding gave 4.4x, not 16x, because
the bottleneck moved to shared resources (one machine's CPU, Postgres commits/fsync, Hikari pool of 30 vs 50 VUs) and
hash sharding of 50 concurrent payments over 16 shards still collides. Next bottleneck is unconfirmed; metrics needed.

**Resume bullet (measured):** Profiled the payment path with k6 (50 concurrent users) and found a single escrow row
lock serializing all authorizations; sharding it across 16 accounts raised throughput 4.4x (673 to 2,991 req/s) and cut
authorize p95 latency 80% (185 ms to 37 ms) with zero errors.

## 8. Interview questions I must be able to answer in my own words

1. Why lock accounts in sorted order?
2. What happens if two identical requests arrive at the same instant?
3. Why pessimistic over optimistic locking here?
4. Why is the idempotency record in the same transaction as the payment?
5. What is the outbox pattern and what problem (dual write) does it solve?
6. What does `SKIP LOCKED` do, and what happens if a worker crashes mid-delivery (lease)?
7. Why exponential backoff **with jitter**?
8. Why does the rate limiter fail open? Why a Lua script, and why Redis `TIME`?
9. Why is SHA-256 enough for API keys but not for passwords?
10. What is SSRF and how is it mitigated here?
11. Why did sharding give 4.4x and not 16x, and how would I find the next bottleneck? What are the trade-offs of sharding escrow (reporting must sum shards, hot-shard skew, capture must use the same shard)?
12. Why is webhook delivery at-least-once and not exactly-once? How should a receiver cope?

## 9. Known limitations (be honest about these in interviews)

Idempotency keys never expire · webhook ordering not guaranteed · API-key lookup hits the DB each request (cache would
help) · webhook secrets stored plaintext (needed to sign) · merchant/customer rows can still be hot for one very busy
account · escrow shard count is capped at 16 by the migration · no Java integration tests or CI yet.

## 10. Next steps (in order)

1. ~~Load-test experiment~~ done (section 7). Make sure the two `RATE_LIMIT_*` lines in compose are commented out again.
2. **Testcontainers integration tests + GitHub Actions CI** (run both scripts' scenarios in Java; green badge).
3. **Fraud rules**: velocity limit and large-amount flag using Redis counters; flagged payments held for review.
4. **Metrics**: Micrometer + Prometheus + Grafana (payments/s, p95, webhook DLQ size, rate-limit rejects).
5. **Deploy** (Fly.io or Render) with a public Swagger link; add architecture diagram.
6. Final README polish and resume bullets using only measured numbers.
