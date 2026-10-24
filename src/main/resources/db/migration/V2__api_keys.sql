-- Only a SHA-256 hash of each key is stored; the plaintext is shown once at creation.
CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    merchant_id UUID         NOT NULL REFERENCES accounts(id),
    name        VARCHAR(100) NOT NULL,
    key_prefix  VARCHAR(12)  NOT NULL,           -- first chars, so humans can recognise a key
    key_hash    VARCHAR(64)  NOT NULL UNIQUE,    -- unique constraint also gives us the lookup index
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id);

-- idempotency keys are now stored as "<scope>:<client key>", so the column must be wider
ALTER TABLE idempotency_keys ALTER COLUMN idem_key TYPE VARCHAR(160);
