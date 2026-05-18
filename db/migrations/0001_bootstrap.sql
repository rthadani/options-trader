-- Phase 3 bootstrap migration: all core schema tables.
-- Applied exactly once by options-trader.db.duckdb/bootstrap!
-- DuckDB syntax: JSON column type, ON CONFLICT for upserts, no MERGE.

CREATE TABLE IF NOT EXISTS bars_daily (
  symbol   VARCHAR NOT NULL,
  bar_date DATE    NOT NULL,
  open     DOUBLE,
  high     DOUBLE,
  low      DOUBLE,
  close    DOUBLE,
  volume   BIGINT,
  vwap     DOUBLE,
  PRIMARY KEY (symbol, bar_date)
);

CREATE TABLE IF NOT EXISTS bars_intraday (
  symbol   VARCHAR   NOT NULL,
  bar_ts   TIMESTAMP NOT NULL,
  bar_size VARCHAR   NOT NULL,
  open     DOUBLE,
  high     DOUBLE,
  low      DOUBLE,
  close    DOUBLE,
  volume   BIGINT,
  PRIMARY KEY (symbol, bar_ts, bar_size)
);

CREATE TABLE IF NOT EXISTS quotes (
  symbol    VARCHAR   NOT NULL,
  quoted_at TIMESTAMP NOT NULL,
  bid       DOUBLE,
  ask       DOUBLE,
  last      DOUBLE,
  bid_size  INTEGER,
  ask_size  INTEGER,
  PRIMARY KEY (symbol, quoted_at)
);

CREATE TABLE IF NOT EXISTS option_chain (
  symbol        VARCHAR   NOT NULL,
  expiry        DATE      NOT NULL,
  strike        DOUBLE    NOT NULL,
  opt_right     VARCHAR   NOT NULL,
  fetched_at    TIMESTAMP NOT NULL,
  bid           DOUBLE,
  ask           DOUBLE,
  last          DOUBLE,
  volume        INTEGER,
  open_interest INTEGER,
  iv            DOUBLE,
  delta         DOUBLE,
  gamma         DOUBLE,
  theta         DOUBLE,
  vega          DOUBLE,
  PRIMARY KEY (symbol, expiry, strike, opt_right, fetched_at)
);

CREATE TABLE IF NOT EXISTS iv_daily (
  symbol  VARCHAR NOT NULL,
  iv_date DATE    NOT NULL,
  iv30    DOUBLE,
  iv60    DOUBLE,
  iv90    DOUBLE,
  hv30    DOUBLE,
  hv60    DOUBLE,
  rv30    DOUBLE,
  PRIMARY KEY (symbol, iv_date)
);

CREATE TABLE IF NOT EXISTS iv_backfill_status (
  symbol     VARCHAR PRIMARY KEY,
  last_date  DATE,
  status     VARCHAR,
  updated_at TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS fundamentals (
  symbol     VARCHAR NOT NULL,
  period     VARCHAR NOT NULL,
  fetched_at TIMESTAMP NOT NULL,
  data       JSON,
  PRIMARY KEY (symbol, period)
);

CREATE TABLE IF NOT EXISTS filings (
  accession VARCHAR PRIMARY KEY,
  symbol    VARCHAR,
  cik       VARCHAR,
  form_type VARCHAR,
  filed_at  DATE,
  period    DATE,
  data      JSON
);

CREATE TABLE IF NOT EXISTS news (
  id           VARCHAR PRIMARY KEY,
  symbol       VARCHAR,
  title        VARCHAR,
  url          VARCHAR,
  source       VARCHAR,
  published_at TIMESTAMP,
  sentiment    VARCHAR,
  data         JSON
);

CREATE TABLE IF NOT EXISTS positions (
  account    VARCHAR NOT NULL,
  symbol     VARCHAR NOT NULL,
  opt_right  VARCHAR NOT NULL DEFAULT '',
  expiry     DATE    NOT NULL DEFAULT '1900-01-01',
  strike     DOUBLE  NOT NULL DEFAULT 0.0,
  quantity   INTEGER NOT NULL,
  avg_cost   DOUBLE,
  market_val DOUBLE,
  unrealized DOUBLE,
  updated_at TIMESTAMP DEFAULT current_timestamp,
  PRIMARY KEY (account, symbol, opt_right, expiry, strike)
);

CREATE TABLE IF NOT EXISTS account_summary (
  account      VARCHAR   NOT NULL,
  fetched_at   TIMESTAMP NOT NULL,
  net_liq      DOUBLE,
  cash         DOUBLE,
  buying_power DOUBLE,
  day_pl       DOUBLE,
  data         JSON,
  PRIMARY KEY (account, fetched_at)
);

CREATE TABLE IF NOT EXISTS earnings_events (
  symbol       VARCHAR NOT NULL,
  period       VARCHAR NOT NULL,
  reported_at  DATE,
  eps_actual   DOUBLE,
  eps_estimate DOUBLE,
  rev_actual   DOUBLE,
  rev_estimate DOUBLE,
  surprise_pct DOUBLE,
  PRIMARY KEY (symbol, period)
);

CREATE TABLE IF NOT EXISTS earnings_calendar (
  symbol       VARCHAR NOT NULL,
  report_date  DATE    NOT NULL,
  report_time  VARCHAR,
  eps_estimate DOUBLE,
  PRIMARY KEY (symbol, report_date)
);

CREATE TABLE IF NOT EXISTS short_interest (
  symbol          VARCHAR NOT NULL,
  settlement_date DATE    NOT NULL,
  short_interest  BIGINT,
  float_shares    BIGINT,
  days_to_cover   DOUBLE,
  short_pct_float DOUBLE,
  PRIMARY KEY (symbol, settlement_date)
);

CREATE TABLE IF NOT EXISTS sector_map (
  symbol         VARCHAR PRIMARY KEY,
  sector         VARCHAR,
  industry_group VARCHAR,
  industry       VARCHAR,
  sub_industry   VARCHAR,
  updated_at     TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS event_calendars (
  id          VARCHAR PRIMARY KEY,
  symbol      VARCHAR,
  event_type  VARCHAR,
  event_date  DATE,
  description VARCHAR,
  data        JSON
);

CREATE TABLE IF NOT EXISTS benchmark_returns (
  benchmark VARCHAR NOT NULL,
  ret_date  DATE    NOT NULL,
  ret_1d    DOUBLE,
  ret_1w    DOUBLE,
  ret_1m    DOUBLE,
  ret_ytd   DOUBLE,
  PRIMARY KEY (benchmark, ret_date)
);

CREATE TABLE IF NOT EXISTS universes (
  name        VARCHAR PRIMARY KEY,
  description VARCHAR,
  created_at  TIMESTAMP DEFAULT current_timestamp
);

INSERT INTO universes (name, description) VALUES ('default', 'Default built-in universe')
  ON CONFLICT (name) DO NOTHING;

CREATE TABLE IF NOT EXISTS universe_members (
  universe VARCHAR   NOT NULL,
  symbol   VARCHAR   NOT NULL,
  added_at TIMESTAMP DEFAULT current_timestamp,
  PRIMARY KEY (universe, symbol)
);

CREATE TABLE IF NOT EXISTS universe_drift_log (
  id         BIGINT    NOT NULL,
  universe   VARCHAR   NOT NULL,
  symbol     VARCHAR   NOT NULL,
  action     VARCHAR   NOT NULL,
  drifted_at TIMESTAMP DEFAULT current_timestamp,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS indicator_renames (
  old_name   VARCHAR   NOT NULL,
  new_name   VARCHAR   NOT NULL,
  renamed_at TIMESTAMP DEFAULT current_timestamp,
  PRIMARY KEY (old_name, new_name)
);

CREATE TABLE IF NOT EXISTS refresh_log (
  id          BIGINT  NOT NULL,
  task        VARCHAR NOT NULL,
  symbol      VARCHAR,
  started_at  TIMESTAMP,
  finished_at TIMESTAMP,
  status      VARCHAR,
  error       VARCHAR,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS universe_lookup_failures (
  universe  VARCHAR   NOT NULL,
  symbol    VARCHAR   NOT NULL,
  failed_at TIMESTAMP NOT NULL DEFAULT current_timestamp,
  reason    VARCHAR,
  PRIMARY KEY (universe, symbol, failed_at)
);

CREATE TABLE IF NOT EXISTS screens (
  id         VARCHAR   PRIMARY KEY,
  name       VARCHAR   NOT NULL,
  universe   VARCHAR,
  criteria   JSON,
  created_at TIMESTAMP DEFAULT current_timestamp,
  updated_at TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS conversations (
  id         VARCHAR   PRIMARY KEY,
  started_at TIMESTAMP DEFAULT current_timestamp,
  title      VARCHAR,
  messages   JSON
);

CREATE TABLE IF NOT EXISTS investigations (
  id         VARCHAR   PRIMARY KEY,
  symbol     VARCHAR,
  started_at TIMESTAMP DEFAULT current_timestamp,
  status     VARCHAR,
  summary    VARCHAR,
  data       JSON
);

CREATE TABLE IF NOT EXISTS investigation_findings (
  id               VARCHAR   PRIMARY KEY,
  investigation_id VARCHAR   NOT NULL,
  finding_type     VARCHAR,
  content          VARCHAR,
  created_at       TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS orders (
  order_id    VARCHAR   PRIMARY KEY,
  account     VARCHAR   NOT NULL,
  symbol      VARCHAR   NOT NULL,
  opt_right   VARCHAR,
  expiry      DATE,
  strike      DOUBLE,
  action      VARCHAR   NOT NULL,
  quantity    INTEGER   NOT NULL,
  order_type  VARCHAR,
  limit_price DOUBLE,
  status      VARCHAR,
  placed_at   TIMESTAMP DEFAULT current_timestamp,
  updated_at  TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS fills (
  fill_id    VARCHAR   PRIMARY KEY,
  order_id   VARCHAR   NOT NULL,
  account    VARCHAR   NOT NULL,
  symbol     VARCHAR   NOT NULL,
  quantity   INTEGER   NOT NULL,
  fill_price DOUBLE    NOT NULL,
  filled_at  TIMESTAMP NOT NULL,
  commission DOUBLE
);

CREATE TABLE IF NOT EXISTS latest_indicators (
  symbol     VARCHAR PRIMARY KEY,
  updated_at TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS indicator_history (
  symbol    VARCHAR NOT NULL,
  indicator VARCHAR NOT NULL,
  ind_date  DATE    NOT NULL,
  value     DOUBLE,
  PRIMARY KEY (symbol, indicator, ind_date)
);

CREATE TABLE IF NOT EXISTS option_chain_agg (
  symbol         VARCHAR NOT NULL,
  agg_date       DATE    NOT NULL,
  expiry         DATE    NOT NULL,
  put_call_ratio DOUBLE,
  total_oi       BIGINT,
  call_oi        BIGINT,
  put_oi         BIGINT,
  avg_iv         DOUBLE,
  PRIMARY KEY (symbol, agg_date, expiry)
);

CREATE TABLE IF NOT EXISTS sector_metrics (
  sector   VARCHAR NOT NULL,
  met_date DATE    NOT NULL,
  ret_1d   DOUBLE,
  ret_1w   DOUBLE,
  ret_1m   DOUBLE,
  ret_ytd  DOUBLE,
  data     JSON,
  PRIMARY KEY (sector, met_date)
);
