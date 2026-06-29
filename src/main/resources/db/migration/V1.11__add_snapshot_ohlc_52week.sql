-- Persist intraday high/low, previous close, and 52-week extremes on the market snapshot so the
-- dashboard can show today's high/low, previous close, and 52-week high/low for the index.
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS day_high DOUBLE PRECISION;
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS day_low DOUBLE PRECISION;
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS prev_close DOUBLE PRECISION;
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS week52_high DOUBLE PRECISION;
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS week52_low DOUBLE PRECISION;
