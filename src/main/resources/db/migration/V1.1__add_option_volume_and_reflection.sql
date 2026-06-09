-- Add option volume fields to option_snapshot table
ALTER TABLE option_snapshot ADD COLUMN ce_volume BIGINT;
ALTER TABLE option_snapshot ADD COLUMN pe_volume BIGINT;

-- Create trade_reflection table
CREATE TABLE trade_reflection (
    id BIGSERIAL PRIMARY KEY,
    signal_id BIGINT NOT NULL,
    failed_at TIMESTAMP NOT NULL,
    reflection_text TEXT NOT NULL,
    CONSTRAINT fk_trade_reflection_signal FOREIGN KEY (signal_id) REFERENCES trade_signal (id) ON DELETE CASCADE
);
