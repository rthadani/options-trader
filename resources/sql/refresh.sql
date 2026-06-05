-- Refresh-pipeline queries. Loaded by options-trader.db.queries.refresh
-- via hugsql. Batch INSERTs use ? placeholders and ship through
-- next.jdbc/execute-batch!; single-row queries use :keyword params.

-- :name next-refresh-log-id :? :1
SELECT COALESCE(MAX(id), 0) + 1 AS n FROM refresh_log;

-- :name insert-refresh-log :! :n
INSERT INTO refresh_log (id, task, symbol, started_at, status)
VALUES (:id, :task, :symbol, :started-at, 'running');

-- :name finish-refresh-log :! :n
UPDATE refresh_log
   SET finished_at = :finished-at, status = :status, error = :error
 WHERE id = :id;

-- :name latest-bar-date :? :1
SELECT MAX(bar_date) AS d FROM bars_daily WHERE symbol = :symbol;

-- :name insert-bars-daily-batch :! :n
INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (symbol, bar_date) DO UPDATE SET
  open = excluded.open, high = excluded.high, low = excluded.low,
  close = excluded.close, volume = excluded.volume;

-- :name latest-intraday-ts :? :1
SELECT MAX(bar_ts) AS t FROM bars_intraday
 WHERE symbol = :symbol AND bar_size = :bar-size;

-- :name insert-bars-intraday-batch :! :n
INSERT INTO bars_intraday (symbol, bar_ts, bar_size, open, high, low, close, volume)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (symbol, bar_ts, bar_size) DO UPDATE SET
  open = excluded.open, high = excluded.high, low = excluded.low,
  close = excluded.close, volume = excluded.volume;

-- :name latest-news-published :? :1
SELECT MAX(published_at) AS t FROM news WHERE symbol = :symbol;

-- :name insert-news-batch :! :n
INSERT INTO news (id, symbol, title, url, source, published_at, sentiment, data)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
  title = excluded.title, url = excluded.url,
  sentiment = excluded.sentiment, data = excluded.data;

-- :name latest-filing-date :? :1
SELECT MAX(filed_at) AS d FROM filings WHERE symbol = :symbol;

-- :name insert-filings-batch :! :n
INSERT INTO filings (accession, symbol, cik, form_type, filed_at, period, data)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (accession) DO UPDATE SET
  form_type = excluded.form_type, filed_at = excluded.filed_at,
  period = excluded.period, data = excluded.data;

-- :name select-universe-members :? :*
SELECT symbol FROM universe_members WHERE universe = :universe;

-- :name next-universe-drift-id :? :1
SELECT COALESCE(MAX(id), 0) + 1 AS n FROM universe_drift_log;

-- :name insert-universe-drift :! :n
INSERT INTO universe_drift_log (id, universe, symbol, action)
VALUES (:id, :universe, :symbol, :action);

-- :name upsert-universe :! :n
INSERT INTO universes (name, description) VALUES (:name, :description)
ON CONFLICT (name) DO NOTHING;

-- :name insert-universe-member :! :n
INSERT INTO universe_members (universe, symbol) VALUES (:universe, :symbol)
ON CONFLICT DO NOTHING;

-- :name delete-universe-member :! :n
DELETE FROM universe_members WHERE universe = :universe AND symbol = :symbol;

-- ── iv_daily ─────────────────────────────────────────────────────────

-- :name latest-iv-date :? :1
SELECT MAX(iv_date) AS d FROM iv_daily WHERE symbol = :symbol;

-- :name upsert-iv-row :! :n
INSERT INTO iv_daily (symbol, iv_date, iv30, hv30)
VALUES (:symbol, :iv-date, :iv30, :hv30)
ON CONFLICT (symbol, iv_date) DO UPDATE SET
  iv30 = COALESCE(excluded.iv30, iv30),
  hv30 = COALESCE(excluded.hv30, hv30);

-- ── short_interest ───────────────────────────────────────────────────

-- :name upsert-short-interest :! :n
INSERT INTO short_interest
  (symbol, settlement_date, short_interest, float_shares,
   days_to_cover, short_pct_float)
VALUES
  (:symbol, :settlement-date, :short-interest, :float-shares,
   :days-to-cover, :short-pct-float)
ON CONFLICT (symbol, settlement_date) DO UPDATE SET
  short_interest  = excluded.short_interest,
  float_shares    = excluded.float_shares,
  days_to_cover   = excluded.days_to_cover,
  short_pct_float = excluded.short_pct_float;

-- ── earnings ─────────────────────────────────────────────────────────

-- :name upsert-earnings-event :! :n
INSERT INTO earnings_events
  (symbol, period, reported_at, eps_actual, eps_estimate, surprise_pct)
VALUES
  (:symbol, :period, :reported-at, :eps-actual, :eps-estimate, :surprise-pct)
ON CONFLICT (symbol, period) DO UPDATE SET
  reported_at  = excluded.reported_at,
  eps_actual   = excluded.eps_actual,
  eps_estimate = excluded.eps_estimate,
  surprise_pct = excluded.surprise_pct;

-- :name upsert-earnings-calendar :! :n
INSERT INTO earnings_calendar
  (symbol, report_date, report_time, eps_estimate)
VALUES
  (:symbol, :report-date, :report-time, :eps-estimate)
ON CONFLICT (symbol, report_date) DO UPDATE SET
  report_time  = excluded.report_time,
  eps_estimate = excluded.eps_estimate;
