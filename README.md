# PayCore

**A concurrency-safe payment processing backend built with Java 17, Spring Boot, and PostgreSQL.**

PayCore implements a double-entry ledger, a payment state machine, idempotent REST APIs, and transactional money movement with database-level concurrency control.

### Tech Stack

`Java 17` · `Spring Boot` · `PostgreSQL` · `Docker` · `REST APIs`

---

## Getting Started

### Run with Docker

Start the application and PostgreSQL database:

```bash
docker compose up --build
```

| Service | Address |
|---|---|
| Backend API | `http://localhost:8080` |
| PostgreSQL | `localhost:5432` |
| Swagger UI | [localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |

### Run Concurrency Tests

With the server running, execute:

```bash
python3 scripts/concurrency_test.py
```

---

## Concurrency & Stress Test Results

The automated test suite fires concurrent requests to verify database locking, payment state consistency, balance integrity, and idempotency guarantees.

**Test Results — 6/6 Passed**

```text
[PASS] 1. 20 parallel requests, same key
         → One payment created (distinct payment IDs: 1)

[PASS] 2. Same idempotency key, different request body
         → HTTP 422

[PASS] 3. 30 concurrent authorisation attempts
         → 10 successful, 20 rejected
         → Balance never negative (final balance: 0)

[PASS] 4. 10 parallel capture attempts
         → 1 successful, 9 rejected
         → Merchant credited exactly once (balance: 200)

[PASS] 5. Refund attempted before capture
         → HTTP 409 Conflict

[PASS] 6. Ledger reconciliation
         → Healthy: true
         → Total debits:  76100
         → Total credits: 76100
         → Mismatched accounts: []

========================================
             6/6 CHECKS PASSED
========================================
```

---

## Payment Lifecycle

Payments follow a strictly enforced state machine:

```text
CREATED ──authorize──> AUTHORIZED ──capture──> CAPTURED ──refund──> REFUNDED
                            |
                           void
                            |
                            v
                          VOIDED
```

### Money Movement

Every operation transfers funds between accounts using double-entry bookkeeping.

| Operation | Source | Destination |
|---|---|---|
| Deposit | `FUNDING` | Customer |
| Authorize | Customer | `ESCROW` |
| Capture | `ESCROW` | Merchant |
| Void | `ESCROW` | Customer |
| Refund | Merchant | Customer |

> **Precision:** All monetary amounts are represented as integers in minor currency units (paise). Floating-point arithmetic is never used for money.

---

## API Walkthrough

The following example demonstrates a complete payment lifecycle.

### 1. Create Accounts

Create a customer account:

```bash
curl -s -X POST localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","type":"CUSTOMER"}'
```

Create a merchant account:

```bash
curl -s -X POST localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"shop","type":"MERCHANT"}'
```

Save the returned account IDs as `CUSTOMER` and `MERCHANT`.

### 2. Deposit Funds

Deposit ₹500.00 into the customer account:

```bash
curl -s -X POST localhost:8080/accounts/$CUSTOMER/deposit \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-1' \
  -d '{"amount":50000}'
```

### 3. Create a Payment

Create a payment for ₹120.00:

```bash
curl -s -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pay-1' \
  -d "{\"customerId\":\"$CUSTOMER\",\"merchantId\":\"$MERCHANT\",\"amount\":12000}"
```

Save the returned payment ID as `PAYMENT`.

### 4. Authorize the Payment

```bash
curl -s -X POST localhost:8080/payments/$PAYMENT/authorize \
  -H 'Idempotency-Key: auth-1'
```

Funds move from the customer account into escrow.

### 5. Capture the Payment

```bash
curl -s -X POST localhost:8080/payments/$PAYMENT/capture \
  -H 'Idempotency-Key: cap-1'
```

Funds move from escrow to the merchant account.

### 6. Reconcile the Ledger

```bash
curl -s localhost:8080/admin/reconcile
```

Verify that ledger entries and account balances remain consistent.

---

## Design Decisions

### 1. Double-Entry Ledger

Every transfer creates a matching **DEBIT** and **CREDIT** entry linked by a shared transaction ID.

- The ledger is append-only.
- Every transfer preserves accounting balance.
- `/admin/reconcile` verifies account balances against ledger sums and checks that total debits equal total credits.

### 2. Pessimistic Row Locking

Uses PostgreSQL's `SELECT ... FOR UPDATE` to lock accounts and payments during critical operations.

**Why pessimistic locking?**

Optimistic locking could cause frequent retries under heavy contention, particularly on shared escrow accounts.

To reduce deadlock risk, locks are acquired in a consistent order:

1. Lock the payment.
2. Lock the participating accounts in ascending account ID order.
3. Execute the transfer atomically.

### 3. Idempotent APIs

Idempotency is implemented using:

```sql
INSERT ... ON CONFLICT DO NOTHING
```

The idempotency record and business operation participate in the same database transaction.

| Scenario | Behaviour |
|---|---|
| Same key + same request | Replay stored response |
| Same key + different request | HTTP `422` |
| Failed transaction | Roll back operation and key |
| Retried successful request | No duplicate money movement |

This allows clients to safely retry requests without duplicating successful operations.

### 4. Database-Level Balance Protection

A database constraint prevents negative balances on non-system accounts:

```sql
CHECK (balance >= 0)
```

This acts as a final safeguard alongside application-level balance validation.

### 5. Atomic Transactions & Crash Safety

Each money transfer executes within a single PostgreSQL transaction.

If the application crashes during processing, the transaction rolls back rather than leaving a partial transfer.

This ensures that a debit cannot be committed without its corresponding credit.

---

## Known Limitations & Future Improvements

| Area | Current Limitation / Improvement |
|---|---|
| Escrow scalability | A single shared escrow row serializes authorizations; consider merchant-level escrow sharding. |
| Idempotency | Keys are globally scoped and never expire; add API-key scoping and expiration. |
| Authentication | Add authentication and authorization. |
| Rate limiting | Introduce request throttling and abuse protection. |
| Webhooks | Add asynchronous payment event notifications. |

---

**PayCore** — Exploring reliable money movement through transactional consistency, concurrency control, and double-entry accounting.
