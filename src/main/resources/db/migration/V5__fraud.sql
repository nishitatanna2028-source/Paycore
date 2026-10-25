-- Fraud screening result stored on each payment.
--   NONE     = passed all rules
--   REVIEW   = held for manual review; authorize is blocked until an admin approves
--   APPROVED = an admin released it
ALTER TABLE payments
    ADD COLUMN risk_flag   VARCHAR(10) NOT NULL DEFAULT 'NONE',
    ADD COLUMN risk_reason VARCHAR(200);

-- partial index: the review queue only ever reads REVIEW rows
CREATE INDEX idx_payments_review ON payments (created_at) WHERE risk_flag = 'REVIEW';
