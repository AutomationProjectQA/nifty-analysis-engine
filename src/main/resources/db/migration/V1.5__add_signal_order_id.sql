-- Broker order id for a placed trade signal (null for paper/simulated signals).
-- Enables reconciliation of engine signals against the broker order book.
ALTER TABLE trade_signal ADD COLUMN IF NOT EXISTS order_id VARCHAR(50);
