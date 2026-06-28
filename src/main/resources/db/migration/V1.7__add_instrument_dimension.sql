-- P5-2: multi-instrument support. Adds an `instrument` dimension to the time-series and signal
-- tables. Existing rows are NIFTY (the only instrument so far). Bank Nifty etc. write their own.
ALTER TABLE market_snapshot ADD COLUMN IF NOT EXISTS instrument VARCHAR(20) NOT NULL DEFAULT 'NIFTY';
ALTER TABLE option_snapshot ADD COLUMN IF NOT EXISTS instrument VARCHAR(20) NOT NULL DEFAULT 'NIFTY';
ALTER TABLE market_candle   ADD COLUMN IF NOT EXISTS instrument VARCHAR(20) NOT NULL DEFAULT 'NIFTY';
ALTER TABLE trade_signal    ADD COLUMN IF NOT EXISTS instrument VARCHAR(20) NOT NULL DEFAULT 'NIFTY';

-- Hot lookups become per-instrument; index (instrument, time) so they stay fast across instruments.
CREATE INDEX IF NOT EXISTS idx_market_snapshot_instr_time ON market_snapshot (instrument, snapshot_time);
CREATE INDEX IF NOT EXISTS idx_option_snapshot_instr_time ON option_snapshot (instrument, snapshot_time);
CREATE INDEX IF NOT EXISTS idx_market_candle_instr_tf_ts  ON market_candle (instrument, timeframe, timestamp);
CREATE INDEX IF NOT EXISTS idx_trade_signal_instr_status  ON trade_signal (instrument, status);
