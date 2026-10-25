# PayCore: project context (handoff file for a new Claude chat)

> HOW TO USE: start a new chat, attach this file (and optionally the repo zip), and say:
> "This is my project context. Read it, then continue with the 'Next steps' section."

## 1. Who I am and the goal
- 3rd-year student at a tier-2 college (MIT Bangalore), applying for the **Visa Summer 2027 SWE internship**.
- PayCore is my main resume project: a payment-processing backend (mini Stripe/Razorpay).
- I am a beginner with tooling (Docker, Maven, Git). I want exact step-by-step instructions,
  complete files I can drop in, and short explanations of the *why*.
- I have NOT yet studied the code deeply. I plan to learn it after building. Before interviews I must be able
  to answer the questions in section 7.

## 2. Environment
- Windows 11, Docker Desktop, Python installed, Git. Terminal = cmd.
- Project folder: `C:\Users\nishi\OneDrive\Documents\PROJECTS\paycore\paycore` (OneDrive causes git/Docker file-lock
  hiccups; plan to move to `C:\dev\paycore`).
- Repo: https://github.com/nishitatanna2028-source/Paycore (branch `main`).
- Run: `docker compose up --build` (starts Postgres, Redis, the app on :8080). Swagger: http://localhost:8080/swagger-ui.html
- Claude's sandbox CANNOT compile Java (Maven Central blocked), so Java code is untested until I run it.
  When something fails, I paste the first red error from the terminal.

## 3. Stack
Java 17, Spring Boot 3.2.5, Spring Data JPA + JdbcTemplate, PostgreSQL 15, Flyway, Redis 7 (rate limiting),
springdoc Swagger, Docker Compose. Plain servlet filter for auth (no Spring Security).
**Flyway rule: never edit an already-applied migration; add a new V<n>__ file.**

## 4. Architecture and key design decisions
Packages under `com.paycore`: `common`, `domain`, `repo`, `service`, `security`, `webhook`, `web`, `config`.

**Ledger (V1).** Double-entry, append-only `ledger_entries`; every transfer writes a DEBIT and CREDIT sharing a
transaction id. Amounts are `long` minor units (paise). Accounts: CUSTOMER, MERCHANT, SYSTEM (FUNDING, ESCROW with
fixed UUIDs ...0001/...0002). DB `CHECK (balance >= 0)` for non-system accounts as a backstop.
`LedgerService.transfer` is `Propagation.MANDATORY` (cannot run outside a transaction).

**Payment state machine.** CREATED -> AUTHORIZED -> CAPTURED -> REFUNDED, or AUTHORIZED -> VOIDED.
Money: deposit FUNDING->customer; authorize customer->ESCROW; capture ESCROW->merchant; void ESCROW->customer;
refund merchant->customer. Transitions live only in `PaymentStatus.canMoveTo`.

**Concurrency.** Pessimistic row locks (`SELECT ... FOR UPDATE`) on payment then accounts; accounts locked in
sorted-UUID order to prevent deadlocks. Chosen over optimistic locking because escrow is a hot row and optimistic
would cause retry storms.

**Idempotency.** `INSERT ... ON CONFLICT DO NOTHING` on the key, in the SAME transaction as the business action.
Same key+same request replays the stored response (`Idempotent-Replay: true`); same key+different body -> 422.
Failed requests roll back with the key so clients can retry. Keys are scoped per caller (`<merchantId|admin>:<key>`).

**Auth (V2).** Merchant API keys `pk_...` (256-bit random, only SHA-256 hash stored, shown once, revocable) for
`/payments/**` and `/webhooks/**`. Admin key (`X-Admin-Key`, env `ADMIN_KEY`, default `dev-admin-key`) for accounts,
deposits, `/admin/**`. Tenant isolation: another merchant's payment returns 404. Implemented in `ApiAuthFilter`.

**Rate limiting.** Redis token bucket via an atomic Lua script (`redis/token_bucket.lua`), uses Redis TIME (no clock
skew between app servers), per API key/admin. Default burst 100, refill 50/s. 429 + `Retry-After`. Fails OPEN if Redis
is down (availability over strictness). Verified the Lua logic offline.

**Webhooks (V3).** Transactional outbox: `WebhookService.enqueue` (MANDATORY tx) inserts `webhook_events` rows in the
same transaction as the payment change, so an event exists iff the change committed. `WebhookDispatcher` polls every
1s: claims due rows with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING` and a lease
(crash-safe, multi-instance-safe), sends HTTP outside any DB transaction, 8 in parallel. Failure -> exponential backoff
(base*2^(n-1), capped, +-20% jitter). After max attempts -> status DEAD (dead-letter queue); merchant can
`POST /webhooks/events/{id}/replay`. Statuses: PENDING, DELIVERED, DEAD. Delivery is at-least-once and unordered;
receivers dedupe on event id. Requests signed `X-PayCore-Signature: t=<ts>,v1=HMAC_SHA256(secret, "<ts>.<body>")`.
SSRF guard (`WebhookUrls`) blocks private/loopback/link-local targets and redirects are not followed; compose enables
`WEBHOOK_ALLOW_PRIVATE=true` ONLY for local testing. Dev compose also sets max attempts 5, base delay 1s.

**Reconciliation.** `GET /admin/reconcile` checks stored balances == ledger sums and total debits == credits
(REPEATABLE_READ snapshot).

## 5. Tests I run
- `python scripts/concurrency_test.py` : idempotency race, overdraw race, capture race, auth, tenant isolation,
  key revocation, rate limiting, reconciliation. (All passed before webhooks were added.)
- `python scripts/webhook_test.py` : flaky receiver + retries, signature check, DLQ, replay, deactivation.
  Needs Windows Firewall to allow Python.

## 6. Status (approx. share of the final resume project)
Done: ledger, state machine, idempotency, locking, reconciliation, API keys, tenant isolation, rate limiting,
webhooks (code written; verify with webhook_test.py). Roughly 65-70% done.
Not done: k6 load test with real numbers + one measured optimization; Java integration tests (Testcontainers) + CI;
basic fraud rules; metrics (Micrometer/Prometheus/Grafana) + structured logs; deployment with a public Swagger link;
architecture diagram; frontend dashboard (low priority).

## 7. Interview questions I must be able to answer in my own words
1. Why lock accounts in sorted order?  2. What if two identical requests arrive at the same instant?
3. Why pessimistic over optimistic locking here?  4. Why is the idempotency record in the same transaction?
5. What is the outbox pattern and what problem does it solve (dual-write)?  6. What does SKIP LOCKED do and what
happens if a worker crashes mid-delivery?  7. Why exponential backoff with jitter?  8. Why fail open on rate limiting?
9. Why is a SHA-256 hash enough for API keys but not for passwords?  10. What is SSRF and how did I mitigate it?

## 8. Known limitations (be honest about them in interviews)
Escrow is a single hot row; idempotency keys never expire; webhook ordering not guaranteed; one dispatcher
thread claims batches (fine for one instance); no per-merchant rate-limit tiers; API-key lookup hits the DB on every
request (a cache would help); webhook secrets stored plaintext (needed to sign).

## 9. Next steps (in order)
1. Confirm `webhook_test.py` passes; commit.  2. k6 load test and record real throughput/p95; do one optimization
and show before/after.  3. Testcontainers integration tests + GitHub Actions CI.  4. Fraud rules (velocity, large
amount) using Redis counters.  5. Micrometer + Prometheus + Grafana dashboard.  6. Deploy (Fly.io/Render) with
public Swagger.  7. Architecture diagram + README design section + resume bullets with only measured numbers.
