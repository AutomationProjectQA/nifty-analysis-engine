-- Phase 0 observability: persist one row per signal-evaluation pass so the trade-generation
-- funnel is measurable (group by reject_stage to see where candidates are eliminated).
CREATE TABLE IF NOT EXISTS decision_trace (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64),
    instrument VARCHAR(20) NOT NULL,
    evaluation_time TIMESTAMP NOT NULL,
    direction VARCHAR(10),
    final_confidence DOUBLE PRECISION,
    effective_gate DOUBLE PRECISION,
    outcome VARCHAR(20) NOT NULL,
    reject_stage VARCHAR(40),
    reject_reason TEXT,
    gate_detail TEXT,
    signals_emitted INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_decision_trace_time ON decision_trace(evaluation_time DESC);
CREATE INDEX IF NOT EXISTS idx_decision_trace_stage ON decision_trace(reject_stage);
CREATE INDEX IF NOT EXISTS idx_decision_trace_instrument_time ON decision_trace(instrument, evaluation_time DESC);
