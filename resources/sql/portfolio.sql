-- Portfolio queries: positions / account_summary read+write, view DDL,
-- default-account-id resolution. Loaded by db.queries.portfolio.

-- :name upsert-position :! :n
INSERT INTO positions
  (account, symbol, opt_right, expiry, strike, quantity, avg_cost, market_val, unrealized, updated_at)
VALUES (:account, :symbol, :opt-right, :expiry, :strike,
        :quantity, :avg-cost, :market-val, :unrealized, current_timestamp)
ON CONFLICT (account, symbol, opt_right, expiry, strike) DO UPDATE SET
  quantity   = excluded.quantity,
  avg_cost   = excluded.avg_cost,
  market_val = excluded.market_val,
  unrealized = excluded.unrealized,
  updated_at = excluded.updated_at;

-- :name insert-account-summary :! :n
INSERT INTO account_summary (account, fetched_at, net_liq, cash, buying_power, day_pl)
VALUES (:account, current_timestamp, :net-liq, :cash, :buying-power, :day-pl)
ON CONFLICT (account, fetched_at) DO NOTHING;

-- :name select-positions :? :*
SELECT symbol, opt_right, expiry, strike, quantity, avg_cost, market_val, unrealized
  FROM positions WHERE account = :account;

-- :name select-account-summary :? :1
SELECT net_liq, cash, buying_power, day_pl
  FROM account_summary WHERE account = :account
 ORDER BY fetched_at DESC LIMIT 1;

-- :name default-account-from-positions :? :1
SELECT DISTINCT account FROM positions LIMIT 1;

-- :name default-account-from-summary :? :1
SELECT account FROM account_summary
 ORDER BY fetched_at DESC LIMIT 1;

-- :name select-portfolio-summary :? :*
SELECT * FROM portfolio_summary;

-- :name select-portfolio-greeks :? :*
SELECT * FROM portfolio_greeks;

-- :name create-portfolio-summary-view :! :n
CREATE OR REPLACE VIEW portfolio_summary AS
SELECT
  a.account, a.net_liq, a.cash, a.buying_power, a.day_pl,
  COALESCE(SUM(p.market_val), 0.0) AS total_market_val,
  COALESCE(SUM(p.unrealized), 0.0) AS total_unrealized,
  COUNT(p.symbol)                  AS position_count
FROM (
  SELECT account, net_liq, cash, buying_power, day_pl,
         ROW_NUMBER() OVER (PARTITION BY account ORDER BY fetched_at DESC) AS rn
  FROM account_summary
) a
LEFT JOIN positions p ON p.account = a.account
WHERE a.rn = 1
GROUP BY a.account, a.net_liq, a.cash, a.buying_power, a.day_pl;

-- :name create-portfolio-greeks-view :! :n
CREATE OR REPLACE VIEW portfolio_greeks AS
SELECT
  p.account, p.symbol, p.opt_right, p.expiry, p.strike, p.quantity,
  oc.delta, oc.gamma, oc.theta, oc.vega, oc.iv,
  p.quantity * COALESCE(oc.delta, 0.0) * 100 AS position_delta,
  p.quantity * COALESCE(oc.gamma, 0.0) * 100 AS position_gamma,
  p.quantity * COALESCE(oc.theta, 0.0) * 100 AS position_theta,
  p.quantity * COALESCE(oc.vega,  0.0) * 100 AS position_vega
FROM positions p
JOIN (
  SELECT symbol, expiry, strike, opt_right, delta, gamma, theta, vega, iv,
         ROW_NUMBER() OVER (PARTITION BY symbol, expiry, strike, opt_right
                            ORDER BY fetched_at DESC) AS rn
  FROM option_chain
) oc
  ON oc.symbol    = p.symbol
 AND oc.expiry    = p.expiry
 AND oc.strike    = p.strike
 AND oc.opt_right = p.opt_right
WHERE oc.rn = 1
  AND p.opt_right != '';
