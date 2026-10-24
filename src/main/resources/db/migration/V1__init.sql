CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL,           -- CUSTOMER | MERCHANT | SYSTEM
    balance     BIGINT       NOT NULL DEFAULT 0, -- minor units (paise)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- second line of defence: even a buggy service can't overdraw a real account
    CONSTRAINT balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0)
);

CREATE TABLE payments (
    id           UUID PRIMARY KEY,
    customer_id  UUID        NOT NULL REFERENCES accounts(id),
    merchant_id  UUID        NOT NULL REFERENCES accounts(id),
    amount       BIGINT      NOT NULL CHECK (amount > 0),
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- append-only: rows are never updated or deleted
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID        NOT NULL,   -- the debit and credit of one transfer share this
    account_id     UUID        NOT NULL REFERENCES accounts(id),
    direction      VARCHAR(6)  NOT NULL,   -- DEBIT | CREDIT
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    reason         VARCHAR(30) NOT NULL,
    payment_id     UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account ON ledger_entries(account_id);
CREATE INDEX idx_ledger_tx ON ledger_entries(transaction_id);

CREATE TABLE idempotency_keys (
    idem_key      VARCHAR(100) PRIMARY KEY,
    fingerprint   VARCHAR(64)  NOT NULL,
    response_body TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- system accounts with fixed ids
INSERT INTO accounts (id, name, type, balance) VALUES
 ('00000000-0000-0000-0000-000000000001', 'FUNDING', 'SYSTEM', 0),  -- source of deposited money (goes negative)
 ('00000000-0000-0000-0000-000000000002', 'ESCROW',  'SYSTEM', 0);  -- holds authorized funds
