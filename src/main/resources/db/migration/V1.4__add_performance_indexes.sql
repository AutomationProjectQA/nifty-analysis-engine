-- Performance indexes on hot query paths: time-series lookups, status filters, and FK joins.
-- All use IF NOT EXISTS so the migration is safe to re-run / apply on existing databases.

-- market_snapshot: findLatest / findLatestBefore / findHistoryBefore / findBetween
CREATE INDEX IF NOT EXISTS idx_market_snapshot_time ON market_snapshot (snapshot_time);

-- option_snapshot: findLatestSnapshotTime (MAX), findBySnapshotTime, by-strike-after
CREATE INDEX IF NOT EXISTS idx_option_snapshot_time ON option_snapshot (snapshot_time);
CREATE INDEX IF NOT EXISTS idx_option_snapshot_strike_time ON option_snapshot (strike_price, snapshot_time);

-- market_candle: findLatestByTimeframe / findHistoryBefore (filter timeframe, order by timestamp)
CREATE INDEX IF NOT EXISTS idx_market_candle_tf_ts ON market_candle (timeframe, timestamp);

-- trade_signal: findByStatus / findBySignalTimeAfter / findAllByOrderBySignalTimeDesc / active-duplicate check
CREATE INDEX IF NOT EXISTS idx_trade_signal_status ON trade_signal (status);
CREATE INDEX IF NOT EXISTS idx_trade_signal_time ON trade_signal (signal_time);
CREATE INDEX IF NOT EXISTS idx_trade_signal_strike_type_status ON trade_signal (strike, signal_type, status);

-- FK joins (Postgres does not auto-index foreign keys)
CREATE INDEX IF NOT EXISTS idx_trade_result_signal ON trade_result (signal_id);
CREATE INDEX IF NOT EXISTS idx_signal_explanation_signal ON signal_explanation (signal_id);

-- market_news: findTop5/All ByOrderByPublishedAtDesc
CREATE INDEX IF NOT EXISTS idx_market_news_published ON market_news (published_at);

-- ai_report: findByTypeAndPublishDate / findLatestByType / findByTypeOrderByPublishDateDesc
CREATE INDEX IF NOT EXISTS idx_ai_report_type_date ON ai_report (type, publish_date);
