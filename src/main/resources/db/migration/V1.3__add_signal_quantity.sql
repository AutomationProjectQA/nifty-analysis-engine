-- Store the executed quantity on each signal so realised P&L can be computed in INR
-- (P&L = (exit_premium - entry_premium) * quantity).
ALTER TABLE trade_signal ADD COLUMN IF NOT EXISTS quantity INTEGER;
