# PayCore

A small payment-processing backend: double-entry ledger, payment state machine,
idempotent APIs and concurrency-safe money movement. Java 17, Spring Boot, PostgreSQL.

## Run

```bash
docker compose up --build        # app on :8080, Postgres on :5432
```

Swagger UI: http://localhost:8080/swagger-ui.html

Try to break it (server must be running):

```bash
python3 scripts/concurrency_test.py
```

## Payment flow

```
CREATED --authorize--> AUTHORIZED --capture--> CAPTURED --refund--> REFUNDED
                           |
                         void
                           v
                        VOIDED
```

| Step      | Money movement        |
|-----------|-----------------------|
| deposit   | FUNDING -> customer   |
| authorize | customer -> ESCROW    |
| capture   | ESCROW -> merchant    |
| void      | ESCROW -> customer    |
| refund    | merchant -> customer  |

All amounts are integers in minor units (paise). Never floats.

## Quick walkthrough

```bash
curl -s -XPOST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"alice","type":"CUSTOMER"}'
curl -s -XPOST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"shop","type":"MERCHANT"}'
# use the returned ids below
curl -s -XPOST localhost:8080/accounts/$CUSTOMER/deposit -H 'Content-Type: application/json' -H 'Idempotency-Key: dep-1' -d '{"amount":50000}'
curl -s -XPOST localhost:8080/payments -H 'Content-Type: application/json' -H 'Idempotency-Key: pay-1' -d "{\"customerId\":\"$CUSTOMER\",\"merchantId\":\"$MERCHANT\",\"amount\":12000}"
curl -s -XPOST localhost:8080/payments/$PAYMENT/authorize -H 'Idempotency-Key: auth-1'
curl -s -XPOST localhost:8080/payments/$PAYMENT/capture   -H 'Idempotency-Key: cap-1'
curl -s localhost:8080/admin/reconcile
```

## Design decisions (fill in your own numbers and failure notes here)

- **Double-entry ledger.** Every transfer writes a DEBIT and CREDIT with a shared transaction id.
  The ledger is append-only. `/admin/reconcile` checks balances == ledger sums and debits == credits.
- **Pessimistic row locking** (`SELECT ... FOR UPDATE`) on accounts and payments. Chosen over optimistic
  locking because money contention on hot rows (escrow) would cause constant retry storms.
  Locks are always taken in the same order (payment, then accounts sorted by id) to prevent deadlocks.
- **Idempotency** via `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the business action.
  Same key + same request -> stored response replayed. Same key + different request -> 422.
  Failed requests roll back with the key, so clients can safely retry.
- **DB constraint** `CHECK (balance >= 0)` for non-system accounts as a backstop to application checks.
- **Crash safety.** A transfer is one DB transaction, so a crash mid-payment cannot leave a debit without its credit.

## Known limitations / next steps

- ESCROW is a single hot row (serialises all authorisations); could be sharded per merchant.
- Idempotency keys are global, not scoped per API key, and never expire.
- No auth, rate limiting, or webhooks yet.
