-- Persist the live last-traded price (LTP) of CE/PE contracts per strike so the option
-- chain and strategy builder can display real premiums instead of theoretical-only values.
ALTER TABLE option_snapshot ADD COLUMN IF NOT EXISTS ce_ltp DOUBLE PRECISION;
ALTER TABLE option_snapshot ADD COLUMN IF NOT EXISTS pe_ltp DOUBLE PRECISION;
