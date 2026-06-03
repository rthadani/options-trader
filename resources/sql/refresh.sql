-- Named queries used by the refresh pipeline. Loaded by
-- options-trader.db.queries.refresh via hugsql/def-sqlvec-fns.
-- Each -- :name block becomes a fn returning [sql & params]; the API
-- layer hands those to next.jdbc. Batch INSERTs live here too so all
-- SQL lives in one place; the API fn does execute-batch! with the
-- string portion. Keyword-style params (:foo) bind from the calling
-- map; ? placeholders are used inside batch INSERT VALUES lists.

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
