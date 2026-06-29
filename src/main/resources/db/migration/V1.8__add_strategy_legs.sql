-- P5-1: defined-risk multi-leg strategies. The `strategy` column on trade_signal tags how the
-- signal should be interpreted (null/absent = legacy single-leg long). Multi-leg strategies
-- (spreads, iron condor) store their legs in trade_leg.
ALTER TABLE trade_signal ADD COLUMN IF NOT EXISTS strategy VARCHAR(30);

CREATE TABLE IF NOT EXISTS trade_leg (
    id            BIGSERIAL PRIMARY KEY,
    signal_id     BIGINT NOT NULL REFERENCES trade_signal(id),
    action        VARCHAR(4)  NOT NULL,   -- BUY / SELL
    option_type   VARCHAR(2)  NOT NULL,   -- CE / PE
    strike        INTEGER     NOT NULL,
    entry_premium DOUBLE PRECISION NOT NULL,
    exit_premium  DOUBLE PRECISION,
    quantity      INTEGER     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_leg_signal ON trade_leg (signal_id);
