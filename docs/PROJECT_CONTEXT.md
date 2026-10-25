# PayCore: technical context for Claude (read fully before generating any code)

This file is written for Claude in a **new chat**. It records exactly what exists in the repo so new files do not
conflict with, or silently break, existing code. Update it after every milestone and commit it.

---

## 0. Rules of engagement (follow these)

1. **Read sections 2 to 9 before writing code.** Do not invent classes, endpoints, columns or env vars that already exist
   under another name. Reuse existing helpers.
2. **Migrations are append-only.** Applied: `V1` to `V5`. The next new file must be `V6__<name>.sql`. Never edit a file
   that has already been applied (Flyway checksum failure on the user's database).
3. **Deliver complete files**, not fragments, unless patching with an exact, verified replacement. Package everything as a
   zip of only new/changed files with paths relative to the project root; the user extracts it over the project folder and
   chooses "Replace the files in the destination". Always list new/changed files and give exact Windows `cmd` steps.
4. **The user is a beginner with tooling.** Give exact commands, say what output to expect, and what to paste back on failure.
5. **Keep the README current and well formatted every step** (user requirement). This file is for Claude, the README is for
   readers/recruiters.
6. **Only put measured numbers in docs and resume bullets.** Never invent benchmark results.
7. **Never rename or change the signature of anything listed in section 6** without updating every caller and test.
8. **Anything that changes the JSON of `Payment`** (getters on the entity) changes API responses AND webhook payloads
   (`WebhookService.enqueue` serializes the entity). Use `@JsonIgnore` for internal fields (as done for `escrowAccountId`).
9. **Default auth for any new path is ADMIN-only** (see section 7.1). A new public path, merchant path or actuator path
   must be added explicitly in `ApiAuthFilter`.
10. Do not use `localStorage`-style assumptions, Spring Security, or Lombok: the project uses neither.

### What Claude's sandbox can and cannot verify

| Can | Cannot |
|---|---|
| Run Python, k6 (downloaded from GitHub releases), Lua via `lupa` + a fake Redis | Download Maven Central artifacts (blocked), so **cannot compile/run the Spring app** |
| Install a JDK as root (`apt-get install -y openjdk-17-jdk-headless`) and run `javac` | Run Docker, PostgreSQL or Redis |
| Syntax-check all Java (`javac -proc:none`, ignore "package does not exist / cannot find symbol" noise) | Run Testcontainers tests |
| Type-check the Java **tests** against hand-written stubs of Jackson/JUnit/AssertJ/Awaitility/Testcontainers/Spring-test | |
| Validate YAML / XML / Python syntax | |

Main Java is therefore syntax-checked but **not compiled against Spring**; the user's `docker compose up --build` (and CI)
is the first real compile. Expect the occasional first-run compile error; the user pastes the first red line.

---

## 1. Project and user snapshot

- Owner: 3rd-year student, MIT Bangalore (tier-2), applying for **Visa Summer 2027 SWE internship**. PayCore is the main resume project.
- Environment: Windows 11 (Intel Core Ultra 7 255H, 16 cores, 31.5 GB RAM), Docker Desktop, Python 3, Git, `cmd`.
  No local JDK/Maven (everything builds inside Docker). Project folder `C:\dev\paycore` (moved out of OneDrive).
- Repo: `https://github.com/nishitatanna2028-source/Paycore`, branch `main`. Push flow that works:
  `git add .` / `git commit -m "..."` / `git pull --rebase` / `git push`. Never suggest `--force`.
- Wants: step-by-step instructions, complete files, fast progress, honest numbers, README updated every step.
- Has **not yet studied the code deeply**; plans to learn it. Interview questions to prepare are in section 13.

---

## 2. Repository file inventory (every file, one line each)

```text
.github/workflows/ci.yml                      CI: setup-java 17, `mvn -B verify` (Testcontainers needs the runner's Docker)
.gitignore                                    target/, IDE files
Dockerfile                                    maven:3.9-eclipse-temurin-17 build (`mvn -q dependency:go-offline`, then
                                              `mvn -q -Dmaven.test.skip=true package`) -> eclipse-temurin:17-jre, jar paycore-0.0.1.jar
docker-compose.yml                            services db, redis, app, k6 (profile "loadtest"); see section 8.3
pom.xml                                       Spring Boot 3.2.5 parent, Java 17; deps in section 3
README.md                                     recruiter-facing documentation
docs/PROJECT_CONTEXT.md                       this file
loadtest/payment_flow.js                      k6 script: setup() creates merchants/customers, VU loop create->authorize->capture
loadtest/compare.py                           `python loadtest/compare.py <a> <b>`: markdown table from results/*.json; refuses runs with >5% errors
loadtest/results/{baseline,sharded}.json      saved k6 summaries (real measurements); warmup.json is scratch; .gitkeep keeps the dir
scripts/concurrency_test.py                   16 end-to-end checks (section 10)
scripts/webhook_test.py                       13 end-to-end checks; runs its own receivers on :9000 and :9999
scripts/fraud_test.py                         10 end-to-end checks
src/main/resources/application.yml            all config (section 8)
src/main/resources/db/migration/V1..V5        schema (section 5)
src/main/resources/redis/token_bucket.lua     rate limiter script
src/main/resources/redis/fraud_velocity.lua   fraud sliding-window script
src/main/java/com/paycore/PayCoreApplication.java      @SpringBootApplication main
  common/ApiException.java                    RuntimeException(HttpStatus status, String message); getStatus()
  common/SystemAccounts.java                  FUNDING, ESCROW, MAX_ESCROW_SHARDS=16, escrowFor(paymentId, shards)
  config/OpenApiConfig.java                   Swagger security schemes (merchantKey bearer, adminKey header)
  config/SchedulingConfig.java                @EnableScheduling (webhook dispatcher)
  domain/{Account,LedgerEntry,Payment}.java   JPA entities (field access, snake_case columns by Spring naming)
  domain/{AccountType,Direction,PaymentStatus,RiskFlag}.java   enums
  fraud/FraudService.java                     velocity + large-amount screening
  repo/{AccountRepository,PaymentRepository,LedgerRepository}.java
  security/ApiAuthFilter.java                 OncePerRequestFilter: auth + rate limit
  security/ApiKeyService.java                 issue / authenticate / revoke API keys (JdbcTemplate)
  security/AuthContext.java                   record(String scope, UUID merchantId); admin(), merchant(id)
  security/RateLimiter.java                   Redis token bucket client
  service/{LedgerService,AccountService,PaymentService,IdempotencyService,ReconciliationService}.java
  web/ApiController.java                      accounts, deposit, payments, reconcile, review queue/approve
  web/AdminController.java                    api-key issue/revoke
  web/WebhookController.java                  /webhooks endpoints
  web/ErrorHandler.java                       @RestControllerAdvice -> {"error": "..."} JSON
  webhook/{WebhookService,WebhookDispatcher,WebhookSigner,WebhookUrls}.java
src/test/java/com/paycore/AbstractIntegrationTest.java   shared Testcontainers + HTTP helpers
src/test/java/com/paycore/PaymentApiTest.java            11 tests
src/test/java/com/paycore/WebhookDeliveryTest.java       3 tests
src/test/java/com/paycore/FraudRulesTest.java            3 tests (lowers velocity limit to 5 via @TestPropertySource)
```

---

## 3. Build and dependencies

- Maven (no wrapper), Java 17, Spring Boot **3.2.5**. Dependencies: web, data-jpa, validation, data-redis, flyway-core,
  postgresql (runtime), springdoc-openapi-starter-webmvc-ui 2.5.0, starter-test (test), `org.testcontainers:postgresql` (test).
- No Lombok, no Spring Security, no actuator/micrometer yet (planned).
- Docker image build skips test **compilation** (`-Dmaven.test.skip=true`) so a test bug can never break the image.
  Tests run only in CI / `mvn verify`.
- Jackson `ObjectMapper` is Spring's auto-configured bean (ISO dates). Entities serialize via getters.

---

## 4. Domain model and money rules

- **Amounts** are `long` minor units (paise). Never floats.
- **Accounts**: `CUSTOMER`, `MERCHANT`, `SYSTEM`. `balance` is stored AND derivable from the ledger (reconciled).
  System accounts: `FUNDING 00000000-0000-0000-0000-000000000001` (goes negative), original
  `ESCROW 00000000-0000-0000-0000-000000000002`, escrow shards `00000000-0000-0000-0002-0000000000NN`
  (NN = 00..0f, names `ESCROW_00..ESCROW_15`, seeded by V4).
- **Payment state machine** (`PaymentStatus.canMoveTo`): CREATED→AUTHORIZED; AUTHORIZED→CAPTURED|VOIDED; CAPTURED→REFUNDED;
  VOIDED and REFUNDED are terminal. Illegal move = `ApiException(409)`.
- **Money flow**: deposit FUNDING→customer; authorize customer→escrowShard; capture escrowShard→merchant;
  void escrowShard→customer; refund merchant→customer.
- **Escrow shard**: chosen at authorize by `SystemAccounts.escrowFor(paymentId, escrowShards)` (hash of payment id; `shards<=1`
  returns original ESCROW); stored in `payments.escrow_account_id`; capture/void use `escrowOf(p)` (null => original ESCROW,
  covers pre-V4 payments).
- **Risk flag** (`RiskFlag` NONE/REVIEW/APPROVED) on the payment: authorize is blocked (409) while REVIEW.

### Invariants that must keep holding (tests rely on them)

1. Every transfer writes exactly one DEBIT and one CREDIT with a shared `transaction_id`, inside the caller's transaction.
2. `SUM(credits) == SUM(debits)` and every `accounts.balance` equals its ledger sum (`/admin/reconcile.healthy == true`).
3. Lock order is always: payment row, then account rows sorted ascending by UUID (`LedgerService.transfer`).
4. Customer/merchant balance never negative (service check AND DB `CHECK`); only SYSTEM accounts may go negative.
5. A webhook event row is written in the SAME transaction as the payment change (`WebhookService.enqueue` is `MANDATORY`).
6. A fraud decline (`ApiException`) rolls back everything including the idempotency key.
7. Another merchant's payment is reported as 404, never 403 (except creating a payment for another merchant's id = 403).

---

## 5. Database schema (Flyway, PostgreSQL 15)

**V1__init.sql**
- `accounts(id UUID PK, name VARCHAR(100), type VARCHAR(20), balance BIGINT DEFAULT 0, created_at TIMESTAMPTZ DEFAULT now(), CHECK balance_non_negative (type='SYSTEM' OR balance>=0))`
- `payments(id UUID PK, customer_id FK accounts, merchant_id FK accounts, amount BIGINT CHECK >0, status VARCHAR(20), created_at, updated_at)`
- `ledger_entries(id BIGSERIAL PK, transaction_id UUID, account_id FK, direction VARCHAR(6), amount BIGINT CHECK >0, reason VARCHAR(30), payment_id UUID NULL, created_at)`; indexes on `account_id`, `transaction_id`
- `idempotency_keys(idem_key VARCHAR(100) PK, fingerprint VARCHAR(64), response_body TEXT NULL, created_at)`
- seeds FUNDING and ESCROW (SYSTEM, balance 0)

**V2__api_keys.sql**: `api_keys(id UUID PK, merchant_id FK, name VARCHAR(100), key_prefix VARCHAR(12), key_hash VARCHAR(64) UNIQUE, revoked BOOLEAN DEFAULT false, created_at)`, index on merchant_id;
`ALTER idempotency_keys.idem_key TYPE VARCHAR(160)` (keys are stored as `<scope>:<client key>`).

**V3__webhooks.sql**: `webhook_endpoints(id UUID PK, merchant_id FK, url VARCHAR(500), secret VARCHAR(100), active BOOLEAN DEFAULT true, created_at)`;
`webhook_events(id UUID PK, merchant_id FK, endpoint_id FK, event_type VARCHAR(40), payload TEXT, status VARCHAR(12) DEFAULT 'PENDING', attempts INT DEFAULT 0, next_attempt_at TIMESTAMPTZ DEFAULT now(), last_status_code INT, last_error VARCHAR(500), created_at, delivered_at)`;
partial index `(next_attempt_at) WHERE status='PENDING'`, index `(merchant_id, created_at DESC)`. Statuses: PENDING, DELIVERED, DEAD.

**V4__escrow_shards.sql**: `ALTER payments ADD escrow_account_id UUID REFERENCES accounts(id)`; inserts 16 SYSTEM accounts ESCROW_00..15.

**V5__fraud.sql**: `ALTER payments ADD risk_flag VARCHAR(10) NOT NULL DEFAULT 'NONE', ADD risk_reason VARCHAR(200)`;
partial index `(created_at) WHERE risk_flag='REVIEW'`.

JPA uses `ddl-auto: none` (Flyway owns the schema). Entities map enums with `@Enumerated(STRING)`.

---

## 6. Class reference (signatures other code depends on)

**`LedgerService`** `@Transactional(MANDATORY) void transfer(UUID from, UUID to, long amount, String reason, UUID paymentId)`
locks both accounts via `AccountRepository.findByIdForUpdate` in sorted order, checks balance (non-SYSTEM source), debits/credits, saves 2 `LedgerEntry`.

**`AccountService`** `create(String, AccountType)`, `get(UUID)` (404), `deposit(UUID, long)` (rejects SYSTEM; transfers from FUNDING, reason `DEPOSIT`).

**`PaymentService`** (ctor: `PaymentRepository, AccountRepository, LedgerService, WebhookService, FraudService, @Value("${paycore.escrow-shards:1}") int`)
`create(customerId, merchantId, amount)` (validates account types, `fraud.assess`, marks REVIEW, saves, enqueues `payment.created`);
`getOwned(id, merchantId)`; `authorize|capture|voidPayment|refund(UUID id, UUID merchantId)` (lock payment, `assertOwner` => 404, state move, transfer, enqueue event);
`approveReview(UUID)` (admin; 409 unless REVIEW; enqueues `payment.review_approved`); `reviewQueue()` (top 100 REVIEW, oldest first).
Event types: `payment.created|authorized|captured|voided|refunded|review_approved`.

**`FraudService`** `Assessment assess(UUID customerId, long amount)`: velocity via `fraud_velocity.lua` (key `fraud:vel:<customerId>`, 60 000 ms window,
unique member per attempt, fails open on Redis error) => `ApiException(422, "Payment declined: too many payments from this customer in the last minute")` when count > max;
amount >= review threshold => `Assessment(REVIEW, reason)`.

**`IdempotencyService`** `Result execute(String scopedKey, String fingerprint, Supplier<Object> action)` `@Transactional`: `INSERT ... ON CONFLICT DO NOTHING`;
inserted => run action, store JSON response; not inserted => fingerprint mismatch 422, stored body replayed (`Result.replayed=true`), null body => 409.

**`ApiKeyService`** `issue(UUID merchantId, String name)` -> `IssuedKey(id, apiKey "pk_"+base64url(32 bytes), prefix)`; `authenticate(String raw)` -> `Optional<KeyInfo(id, merchantId)>`
(lookup by SHA-256 hex, `revoked=FALSE`); `revoke(UUID keyId)`.

**`RateLimiter`** `Decision tryAcquire(String identity)`; Redis key `rl:<identity>`; identities `key:<apiKeyId>` and `admin`; fail-open Decision(true,-1,0).

**`ReconciliationService`** `Map<String,Object> run()` (REPEATABLE_READ): keys `healthy`, `totalDebits`, `totalCredits`, `mismatchedAccounts`.

**`WebhookService`** `enqueue(UUID merchantId, String eventType, Object data)` MANDATORY (one row per active endpoint; envelope `{id, type, createdAt, data}`);
`createEndpoint(merchantId, url)` (SSRF check, max 5 active, secret `whsec_`+base64url), `listEndpoints`, `deactivateEndpoint`, `listEvents(merchantId, status, limit<=200)`, `replay(merchantId, eventId)` (DEAD only, else 404/409).

**`WebhookDispatcher`** `@Scheduled tick()` every `poll-interval-ms`: claim (`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`, attempts+1, lease), deliver in a pool of 8
(`java.net.http.HttpClient`, 3 s connect / 5 s request timeout, redirects NEVER), headers `X-PayCore-Event-Id`, `-Event-Type`, `-Timestamp`, `-Signature`; 2xx => DELIVERED;
else backoff `base*2^(n-1)` capped, jitter 0.8..1.2; `attempts>=max` or deactivated endpoint => DEAD.

**`WebhookSigner`** `signature(secret, ts, body)` hex HMAC-SHA256 over `ts + "." + body`; `header(...)` => `t=<ts>,v1=<hex>`.
**`WebhookUrls`** `assertAllowed(url, allowPrivate)`: http/https only, host required; unless allowPrivate blocks loopback/site-local/link-local/any-local/multicast/IPv6 ULA.

**`ApiException`** is mapped by `ErrorHandler` to `{"error": message}` with its status. Also handled: validation (400), missing header (400), malformed body/UUID (400), `DataIntegrityViolationException` (409).

---

## 7. HTTP API

### 7.1 Authentication and routing (`ApiAuthFilter`, in this order)

1. Public (no auth, no rate limit): paths starting `/swagger-ui`, `/v3/api-docs`, and `/favicon.ico`.
2. Paths starting `/payments` or `/webhooks` => **merchant**: `Authorization: Bearer pk_...` => `AuthContext.merchant(merchantId)`, rate-limit identity `key:<id>`. Fail => 401 `{"error":...}` + `WWW-Authenticate: Bearer`.
3. **Everything else => admin**: `X-Admin-Key` compared constant-time with `paycore.admin-key`; identity `admin`.
4. Rate limit: sets `X-RateLimit-Limit` (+ `X-RateLimit-Remaining` if known); denied => 429 + `Retry-After`.
5. Sets request attribute `ApiAuthFilter.ATTR` = `AuthContext`; controllers read it via `HttpServletRequest`.

### 7.2 Endpoints

| Method | Path | Auth | Idempotency-Key header | Success | Notes |
|---|---|---|---|---|---|
| POST | `/accounts` | admin | no | 201 Account | body `{name, type: CUSTOMER\|MERCHANT}` |
| GET | `/accounts/{id}` , `/accounts/{id}/ledger` | admin | no | 200 | |
| POST | `/accounts/{id}/deposit` | admin | **required** | 200 Account | body `{amount}` |
| POST | `/admin/merchants/{id}/api-keys` | admin | no | 201 `{id, apiKey, prefix, warning}` | key shown once |
| DELETE | `/admin/api-keys/{id}` | admin | no | 204 | |
| GET | `/admin/reconcile` | admin | no | 200 | |
| GET | `/admin/review-queue` | admin | no | 200 `[Payment]` | |
| POST | `/admin/payments/{id}/approve` | admin | no | 200 Payment | 409 if not REVIEW |
| POST | `/payments` | merchant | **required** | 201 Payment | body `{customerId, merchantId, amount}`; merchantId must equal key's merchant else 403; fraud 422 |
| GET | `/payments/{id}` | merchant | no | 200 Payment | |
| POST | `/payments/{id}/authorize\|capture\|void\|refund` | merchant | **required** | 200 Payment | |
| POST/GET | `/webhooks` | merchant | no | 201 / 200 | create returns secret once |
| DELETE | `/webhooks/{id}` | merchant | no | 204 | deactivates |
| GET | `/webhooks/events?status=&limit=` | merchant | no | 200 | includes `payload` |
| POST | `/webhooks/events/{id}/replay` | merchant | no | 202 | DEAD only |

Idempotent responses carry `Idempotent-Replay: true|false`. Idempotency key: 1 to 100 chars, stored as `<merchantId or "admin">:<key>`; fingerprint = SHA-256 of `route|request.toString()`.
Payment JSON fields: `id, customerId, merchantId, amount, status, createdAt, updatedAt, riskFlag, riskReason` (`escrowAccountId` is `@JsonIgnore`).

---

## 8. Configuration

### 8.1 `paycore.*` properties (application.yml) and env vars

| Property | Env var | Default | Used by |
|---|---|---|---|
| `paycore.admin-key` | `ADMIN_KEY` | `dev-admin-key` | ApiAuthFilter |
| `paycore.escrow-shards` | `ESCROW_SHARDS` | `16` (yml) | PaymentService (clamped 1..16) |
| `paycore.rate-limit.capacity` / `.refill-per-second` | `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL` | `100` / `50` | RateLimiter |
| `paycore.fraud.enabled` | `FRAUD_ENABLED` | `true` | FraudService |
| `paycore.fraud.velocity-max-per-minute` | `FRAUD_VELOCITY_MAX` | `60` | FraudService |
| `paycore.fraud.review-amount` | `FRAUD_REVIEW_AMOUNT` | `10000000` | FraudService |
| `paycore.webhooks.allow-private-targets` | `WEBHOOK_ALLOW_PRIVATE` | `false` | WebhookService, WebhookDispatcher |
| `paycore.webhooks.max-attempts` | `WEBHOOK_MAX_ATTEMPTS` | `8` | WebhookDispatcher |
| `paycore.webhooks.base-delay-seconds` | `WEBHOOK_BASE_DELAY_SECONDS` | `2` | WebhookDispatcher |
| `paycore.webhooks.max-delay-seconds` / `lease-seconds` / `batch-size` / `poll-interval-ms` | none | `3600` / `60` / `20` / `1000` | WebhookDispatcher |
| `spring.datasource.*` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | local Postgres `paycore/paycore` | Hikari max pool 30 |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | timeouts 500 ms |

Note: `@Value` defaults in code (e.g. `escrow-shards:1`) are fallbacks only; the yml value wins.

### 8.2 Redis keys
`rl:<identity>` (hash tokens/ts, token bucket), `fraud:vel:<customerId>` (zset sliding window). Both Lua scripts use Redis `TIME`.

### 8.3 docker-compose.yml
Services: `db` (postgres:15-alpine, user/db/password `paycore`, healthcheck), `redis` (redis:7-alpine), `app` (build `.`, port 8080, env incl.
`ADMIN_KEY=dev-admin-key`, `WEBHOOK_ALLOW_PRIVATE="true"`, `WEBHOOK_MAX_ATTEMPTS="5"`, `WEBHOOK_BASE_DELAY_SECONDS="1"`, `ESCROW_SHARDS="16"`,
`extra_hosts host.docker.internal:host-gateway`), `k6` (grafana/k6:0.52.0, profile `loadtest`, `user: "0"`, mounts `./loadtest:/loadtest`, `BASE_URL=http://app:8080`).
The `app` env has a **commented** block of three lines (`RATE_LIMIT_CAPACITY`, `RATE_LIMIT_REFILL` = 1000000, `FRAUD_ENABLED="false"`) that the user
uncomments ONLY for load tests, then re-comments.

---

## 9. Key flows

**Create payment (merchant)**: filter (auth, rate limit) -> controller (`merchantId` match, build scoped key) -> `IdempotencyService.execute` (tx begins, key inserted) ->
`PaymentService.create` (validate accounts, `FraudService.assess` [Redis], save, `enqueue payment.created`) -> response stored in idempotency row -> commit.

**Authorize**: tx: key insert -> lock payment -> owner check -> REVIEW check (409) -> `moveTo(AUTHORIZED)` -> pick escrow shard -> `transfer(customer -> shard)` -> enqueue -> commit.

**Webhook delivery**: committed `webhook_events` row (PENDING) -> dispatcher claims (attempts+1, lease) -> signed POST -> DELIVERED, or reschedule/DEAD -> merchant may `replay` DEAD.

---

## 10. Tests: what exists and how they interact with config

| Suite | Count | Requires | Notes |
|---|---:|---|---|
| `scripts/concurrency_test.py` | 16 | stack running, **rate limiter ON**, fraud ON (defaults) | check 10 fires 1000 requests expecting 429; test 3 creates 30 payments for one customer (below velocity 60) |
| `scripts/webhook_test.py` | 13 | compose dev webhook settings (max attempts 5, base 1 s, allow private) | receivers on host ports 9000/9999 via `host.docker.internal`; Windows Firewall prompt |
| `scripts/fraud_test.py` | 10 | fraud ON, default thresholds (or matching `FRAUD_*` env) | velocity check makes VELOCITY_MAX+5 sequential creates for one fresh customer (needs merchant rate burst >= 65) |
| Java `PaymentApiTest` | 11 | Docker | base class sets rate limit 100000, velocity 1000, allow-private, webhook base 1 s / max 5 |
| Java `WebhookDeliveryTest` | 3 | Docker | in-process `com.sun.net.httpserver.HttpServer` receiver; independent HMAC verification |
| Java `FraudRulesTest` | 3 | Docker | `@TestPropertySource` velocity 5 (higher precedence than `@SpringBootTest(properties)`) |
| `loadtest/payment_flow.js` | n/a | rate limit + fraud **OFF** (commented compose lines) | each VU = own customer + own merchant; flags >5% errors |

Java test design: singleton containers started in a static block of `AbstractIntegrationTest` (shared by all classes and Spring contexts), port via
`@Value("${local.server.port}")`, JDK `HttpClient` (HTTP/1.1) so parallel requests are truly concurrent, `runParallel(n, IntFunction)` releases n threads
from a latch. Test classes end in `Test` (Surefire default). Datasets are isolated per test by creating fresh accounts/merchants.

---

## 11. Verification status (be honest)

| Component | State |
|---|---|
| Steps 1 to 4 (ledger, auth, rate limit, webhooks, sharding, k6) | **Run and passing on the user's machine** (all Python scripts green at those steps; k6 results recorded) |
| Step 5 (fraud rules, Java integration tests, CI) | **Delivered, not yet run by the user.** Verified here: Java syntax (`javac`), tests type-checked against stubs, both Lua scripts against a fake Redis, YAML/XML/Python syntax |
| First CI run | Unknown until pushed; likely first failures would be compile errors in main code or Testcontainers image pulls |

### Measured results (real, from the user's machine)
50 VUs, 60 s, rate limit and fraud off, 0% errors:

| Metric | 1 escrow | 16 shards | Change |
|---|---:|---:|---:|
| Throughput (req/s) | 673.4 | 2,991.1 | +344% (4.4x) |
| authorize p95 / p99 (ms) | 185.3 / 248.0 | 37.1 / 57.8 | -80% / -77% |
| capture p95 / p99 (ms) | 190.9 / 258.0 | 38.2 / 60.1 | -80% / -77% |

Interpretation: single escrow row held ~2 ms per transaction in series (673 req/s = ~224 checkouts/s = ~450 escrow transactions/s); 4.4x not 16x because the
bottleneck moved to shared resources (one machine's CPU, Postgres commits, Hikari pool 30 vs 50 VUs) and hashing collisions. **Next bottleneck not yet identified (needs metrics).**
Resume bullet (measured): "Profiled the payment path with k6 (50 concurrent users) and found a single escrow row lock serializing all authorizations; sharding it across 16 accounts raised throughput 4.4x (673 to 2,991 req/s) and cut authorize p95 latency 80% (185 ms to 37 ms) with zero errors."

---

## 12. Pitfalls already hit (do not repeat)

- **Load test vs rate limiter/fraud**: per-key limit (50/s) and velocity (60/min) throttle k6; first warm-up showed 93% errors at a fake 8,588 req/s. Hence the commented compose block and the >5% error guard.
- **OneDrive** causes git rebase/Docker file-lock hiccups ("Deletion of directory failed"); answer `n`. Project now at `C:\dev\paycore`.
- **Flyway**: never edit applied migrations; V6 is next.
- **Docker image build** must not compile tests (`-Dmaven.test.skip=true`).
- **Testcontainers**: use the singleton-container pattern; do not use `@Container` in the abstract base (contexts are cached across classes).
- **`@LocalServerPort`** package differs across Boot versions; the tests use `@Value("${local.server.port}")`.
- **TestRestTemplate/HttpURLConnection** mishandles 401 on POST; tests use the JDK `HttpClient` instead.
- **JSON of `Payment`** also feeds webhook payloads; keep internal fields `@JsonIgnore`.
- **`JdbcTemplate` and JPA share the same transaction** (JpaTransactionManager); `IdempotencyService` relies on this.
- **Windows Firewall** prompts for Python on the first `webhook_test.py` run.

---

## 13. Interview questions the user must be able to answer (they have not studied the code yet)

1. Why lock accounts in sorted order? 2. Two identical requests at the same instant? 3. Pessimistic vs optimistic locking here?
4. Why is the idempotency row in the same transaction? 5. What is the outbox pattern / dual-write problem?
6. What does `SKIP LOCKED` do, and what if a worker crashes mid-delivery (lease)? 7. Why exponential backoff with jitter?
8. Why do the rate limiter and fraud velocity check fail open; why Lua; why Redis `TIME`? 9. Why SHA-256 is enough for API keys but not passwords?
10. What is SSRF and how is it mitigated? 11. Why 4.4x and not 16x from sharding; trade-offs of sharded escrow; how to find the next bottleneck?
12. Why at-least-once and not exactly-once webhooks; how must a receiver cope? 13. Why a sliding window (sorted set) instead of a fixed-window counter for velocity?
14. How do the Testcontainers tests prove the concurrency claims, and why are they better than mocks?

---

## 14. Known limitations

Idempotency keys never expire; webhook ordering not guaranteed; API-key lookup hits the DB on every request; webhook secrets stored plaintext (needed to sign);
merchant/customer rows can still be hot; escrow shards capped at 16 by V4; held (REVIEW) payments can only be approved, not rejected/cancelled; only two fraud rules;
no pagination on ledger/events beyond limits; no metrics; no deployment; first CI run pending.

---

## 15. Roadmap with technical notes for the remaining steps

**6. Metrics (Micrometer, Prometheus, Grafana).** Add `spring-boot-starter-actuator` + `micrometer-registry-prometheus`; expose `/actuator/prometheus` and `/actuator/health`.
**`ApiAuthFilter` treats unknown paths as admin-only**, so either add `/actuator/health` and `/actuator/prometheus` to `isPublic` (acceptable only on an internal network)
or configure Prometheus to send `X-Admin-Key`. Suggested custom meters: payments by status/transition, fraud declines/reviews, rate-limit rejections, webhook deliveries by outcome,
DEAD queue size (gauge via a count query), request latency histograms (percentiles), Hikari pool metrics (already auto-bound). Compose: add `prometheus` (scrape `app:8080`)
and `grafana` services with a provisioned datasource + a dashboard JSON; keep them under a compose profile so the default stack stays light. Use metrics to identify the next
bottleneck (Hikari wait time, DB commit latency) and optionally run a second measured optimisation (e.g. pool size, API-key cache) with before/after k6 numbers.

**7. Deployment.** Fly.io or Render: set `ADMIN_KEY` to a real secret, `WEBHOOK_ALLOW_PRIVATE=false`, managed Postgres (`DB_URL/DB_USER/DB_PASSWORD`) and Redis (`REDIS_HOST`;
add `spring.data.redis.password/ssl` properties if the provider requires them; the app fails open if Redis is down), expose 8080, keep Swagger public for the demo,
turn off the dev webhook overrides. Add a "Live demo" link to the README; consider a seeded demo merchant.

**8. Polish.** Frontend dashboard (low priority, a single static page served by Spring or separate), architecture diagram refresh, final resume bullets (measured numbers only).

---

## 16. Change log (delivery zips the user has applied)

| Zip | Content |
|---|---|
| `paycore.zip` | Step 1: core ledger, state machine, idempotency, locking, reconciliation, Docker, concurrency test |
| `paycore-step2.zip` | API keys, admin key, tenant isolation, scoped idempotency, Redis rate limiter, V2 |
| `paycore-step3.zip` | Webhooks (outbox, dispatcher, signing, SSRF guard, DLQ, replay), V3, `webhook_test.py`, first context file |
| `paycore-step4.zip`, `-fix`, `-results` | k6 load test, escrow sharding V4, README rewrite, rate-limit/fraud guard, measured results, shards default 16 |
| `paycore-step5.zip` | Fraud rules V5 + `fraud_test.py`, Testcontainers tests, GitHub Actions CI, Dockerfile test-skip, README + this file rewritten |
