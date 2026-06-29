-- Phase 0 data integrity: prevent duplicate rows from corrupting "latest snapshot" aggregates
-- (PCR/OI) and P&L. Each block first removes any existing duplicates (keeping the highest id),
-- then adds the UNIQUE constraint. Safe to run on existing data.

-- option_snapshot: one row per (instrument, snapshot_time, strike)
DELETE FROM option_snapshot a USING option_snapshot b
 WHERE a.id < b.id
   AND a.instrument = b.instrument
   AND a.snapshot_time = b.snapshot_time
   AND a.strike_price = b.strike_price;
ALTER TABLE option_snapshot
    ADD CONSTRAINT uq_option_snapshot_instr_time_strike UNIQUE (instrument, snapshot_time, strike_price);

-- market_snapshot: one row per (instrument, snapshot_time)
DELETE FROM market_snapshot a USING market_snapshot b
 WHERE a.id < b.id
   AND a.instrument = b.instrument
   AND a.snapshot_time = b.snapshot_time;
ALTER TABLE market_snapshot
    ADD CONSTRAINT uq_market_snapshot_instr_time UNIQUE (instrument, snapshot_time);

-- trade_result: enforce the @OneToOne to trade_signal (one result per signal)
DELETE FROM trade_result a USING trade_result b
 WHERE a.id < b.id
   AND a.signal_id = b.signal_id;
ALTER TABLE trade_result
    ADD CONSTRAINT uq_trade_result_signal UNIQUE (signal_id);
