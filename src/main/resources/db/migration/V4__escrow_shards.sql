-- Every authorize/capture/void used to lock the single ESCROW row, serialising all payments.
-- We now spread held funds over up to 16 escrow accounts. The shard is chosen from the payment id
-- when it is authorised and remembered on the payment, so capture/void use the same account.
ALTER TABLE payments ADD COLUMN escrow_account_id UUID REFERENCES accounts(id);

INSERT INTO accounts (id, name, type, balance)
SELECT ('00000000-0000-0000-0002-' || lpad(to_hex(g), 12, '0'))::uuid,
       'ESCROW_' || lpad(g::text, 2, '0'),
       'SYSTEM',
       0
FROM generate_series(0, 15) AS g;
