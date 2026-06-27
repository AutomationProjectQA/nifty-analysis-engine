-- Nifty spot captured at signal entry, so it doesn't have to be reconstructed later.
ALTER TABLE trade_signal ADD COLUMN IF NOT EXISTS entry_spot DOUBLE PRECISION;
