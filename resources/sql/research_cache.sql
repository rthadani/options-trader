-- Cache reads for the fetch_* MCP tools. Read counterparts of the
-- refresh writers in refresh.sql.

-- :name latest-fundamentals :? :1
SELECT data FROM fundamentals
 WHERE symbol = :symbol
 ORDER BY fetched_at DESC LIMIT 1;

-- ── news ─────────────────────────────────────────────────────────────

-- :name latest-news :? :*
SELECT id, symbol, title, url, source, published_at, sentiment, data
  FROM news WHERE symbol = :symbol
 ORDER BY published_at DESC LIMIT :limit;

-- :name latest-news-titles :? :*
SELECT title, sentiment FROM news WHERE symbol = :symbol
 ORDER BY published_at DESC LIMIT :limit;

-- ── filings (edgar cache) ────────────────────────────────────────────
--
-- Filings need conditional WHERE branches (form_type / start / end are
-- all optional). hugsql's `--~` Clojure-expression form is the natural
-- fit — keeps the SQL declarative while letting the predicate set
-- adjust per call.

-- :name latest-filings :? :*
SELECT accession, symbol, cik, form_type, filed_at, period, data
  FROM filings
 WHERE symbol = :symbol
--~ (when (:form-type params)  "AND form_type = :form-type")
--~ (when (:start-date params) "AND filed_at >= :start-date")
--~ (when (:end-date params)   "AND filed_at <= :end-date")
 ORDER BY filed_at DESC LIMIT :limit;

-- ── earnings ─────────────────────────────────────────────────────────

-- :name latest-earnings-history :? :*
SELECT symbol, period, reported_at,
       eps_actual, eps_estimate,
       rev_actual, rev_estimate, surprise_pct
  FROM earnings_events WHERE symbol = :symbol
 ORDER BY reported_at DESC LIMIT :limit;

-- :name earnings-calendar-range :? :*
SELECT symbol, report_date, report_time, eps_estimate
  FROM earnings_calendar
 WHERE report_date >= :start AND report_date <= :end
 ORDER BY report_date;

-- ── short_interest ───────────────────────────────────────────────────

-- :name latest-short-interest :? :*
SELECT symbol, settlement_date, short_interest,
       float_shares, days_to_cover, short_pct_float
  FROM short_interest WHERE symbol = :symbol
 ORDER BY settlement_date DESC LIMIT :limit;
