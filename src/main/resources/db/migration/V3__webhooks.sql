-- Where a merchant wants to be notified
CREATE TABLE webhook_endpoints (
    id          UUID PRIMARY KEY,
    merchant_id UUID         NOT NULL REFERENCES accounts(id),
    url         VARCHAR(500) NOT NULL,
    secret      VARCHAR(100) NOT NULL,   -- HMAC signing secret (must be recoverable to sign, so not hashed)
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

-- The transactional outbox AND the delivery queue.
-- Rows are inserted in the same DB transaction that changes the payment, so an event
-- exists if and only if the payment change committed.
--   PENDING   = waiting for first attempt, or waiting for a retry (next_attempt_at)
--   DELIVERED = endpoint answered 2xx
--   DEAD      = retries exhausted: the dead-letter queue. Can be replayed manually.
CREATE TABLE webhook_events (
    id               UUID PRIMARY KEY,
    merchant_id      UUID         NOT NULL REFERENCES accounts(id),
    endpoint_id      UUID         NOT NULL REFERENCES webhook_endpoints(id),
    event_type       VARCHAR(40)  NOT NULL,
    payload          TEXT         NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_status_code INT,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);
-- partial index: the worker only ever looks at PENDING rows
CREATE INDEX idx_webhook_events_due ON webhook_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_webhook_events_merchant ON webhook_events (merchant_id, created_at DESC);
