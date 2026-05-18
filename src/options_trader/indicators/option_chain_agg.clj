(ns options-trader.indicators.option-chain-agg
  "Option chain aggregation: computes 7 DOUBLE columns from option_chain,
   scoped to ±1 strike of ATM across the front two expirations,
   then upserts one row per symbol into latest_indicators."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(def ^:private oca-double-cols
  [:option_oi_total :option_vol_total :atm_straddle_price
   :implied_move_pct :put_call_oi_ratio :put_call_volume_ratio
   :bid_ask_spread_pct])

(defn- ensure-oca-columns! [ds]
  (doseq [col oca-double-cols]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
            (name col) " DOUBLE")])))

;;; ── SQL ──────────────────────────────────────────────────────────────────────

(defn- agg-sql
  "Build the aggregation SQL. When symbols is non-empty, adds a WHERE clause
   inside the latest_close CTE to limit processing to those symbols."
  [symbols]
  (let [sym-where (when (seq symbols)
                    (str "  WHERE symbol IN ("
                         (str/join ", " (repeat (count symbols) "?"))
                         ")\n"))]
    (str
     "WITH\n"
     "latest_close AS (\n"
     "  SELECT symbol, close\n"
     "  FROM bars_daily\n"
     (or sym-where "")
     "  QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date DESC) = 1\n"
     "),\n"
     "latest_chain AS (\n"
     "  SELECT symbol, expiry, strike, opt_right, bid, ask, volume, open_interest\n"
     "  FROM option_chain\n"
     "  QUALIFY ROW_NUMBER() OVER (\n"
     "    PARTITION BY symbol, expiry, strike, opt_right ORDER BY fetched_at DESC\n"
     "  ) = 1\n"
     "),\n"
     "front2_exp AS (\n"
     "  SELECT symbol, expiry, dr AS exp_rank\n"
     "  FROM (\n"
     "    SELECT DISTINCT lc.symbol, lc.expiry,\n"
     "           DENSE_RANK() OVER (PARTITION BY lc.symbol ORDER BY lc.expiry) AS dr\n"
     "    FROM latest_chain lc\n"
     "    JOIN latest_close cl ON cl.symbol = lc.symbol\n"
     "    WHERE lc.expiry >= CURRENT_DATE\n"
     "  )\n"
     "  WHERE dr <= 2\n"
     "),\n"
     "strike_ranks AS (\n"
     "  SELECT symbol, expiry, strike,\n"
     "         ROW_NUMBER() OVER (PARTITION BY symbol, expiry ORDER BY strike) AS s_rank\n"
     "  FROM (\n"
     "    SELECT DISTINCT lc.symbol, lc.expiry, lc.strike\n"
     "    FROM latest_chain lc\n"
     "    JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry\n"
     "  )\n"
     "),\n"
     "atm_strike_per_exp AS (\n"
     "  SELECT lc.symbol, lc.expiry,\n"
     "         arg_min(lc.strike, ABS(lc.strike - cl.close)) AS atm_strike\n"
     "  FROM latest_chain lc\n"
     "  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry\n"
     "  JOIN latest_close cl ON cl.symbol = lc.symbol\n"
     "  GROUP BY lc.symbol, lc.expiry\n"
     "),\n"
     "atm_ranks AS (\n"
     "  SELECT sr.symbol, sr.expiry, sr.s_rank AS atm_rank\n"
     "  FROM strike_ranks sr\n"
     "  JOIN atm_strike_per_exp atm ON atm.symbol = sr.symbol\n"
     "                             AND atm.expiry = sr.expiry\n"
     "                             AND atm.atm_strike = sr.strike\n"
     "),\n"
     "window_data AS (\n"
     "  SELECT lc.symbol, lc.expiry, lc.strike, lc.opt_right,\n"
     "         lc.bid, lc.ask, lc.volume, lc.open_interest,\n"
     "         fe.exp_rank, atm.atm_strike\n"
     "  FROM latest_chain lc\n"
     "  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry\n"
     "  JOIN strike_ranks sr ON sr.symbol = lc.symbol AND sr.expiry = lc.expiry\n"
     "                      AND sr.strike = lc.strike\n"
     "  JOIN atm_ranks ar ON ar.symbol = lc.symbol AND ar.expiry = lc.expiry\n"
     "  JOIN atm_strike_per_exp atm ON atm.symbol = lc.symbol AND atm.expiry = lc.expiry\n"
     "  WHERE sr.s_rank BETWEEN ar.atm_rank - 1 AND ar.atm_rank + 1\n"
     "),\n"
     "aggregated AS (\n"
     "  SELECT\n"
     "    symbol,\n"
     "    CAST(SUM(open_interest) AS DOUBLE) AS option_oi_total,\n"
     "    CAST(SUM(volume) AS DOUBLE)        AS option_vol_total,\n"
     "    MAX(CASE WHEN exp_rank = 1 AND opt_right = 'C' AND strike = atm_strike\n"
     "             THEN (bid + ask) / 2.0 END) AS call_atm_mid,\n"
     "    MAX(CASE WHEN exp_rank = 1 AND opt_right = 'P' AND strike = atm_strike\n"
     "             THEN (bid + ask) / 2.0 END) AS put_atm_mid,\n"
     "    CAST(SUM(CASE WHEN opt_right = 'P' THEN open_interest END) AS DOUBLE)\n"
     "      / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN open_interest END), 0)\n"
     "      AS put_call_oi_ratio,\n"
     "    CAST(SUM(CASE WHEN opt_right = 'P' THEN volume END) AS DOUBLE)\n"
     "      / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN volume END), 0)\n"
     "      AS put_call_volume_ratio,\n"
     "    AVG(CASE WHEN bid > 0 AND ask > 0\n"
     "             THEN (ask - bid) / ((ask + bid) / 2.0) END) AS bid_ask_spread_pct\n"
     "  FROM window_data\n"
     "  GROUP BY symbol\n"
     ")\n"
     "SELECT\n"
     "  cl.symbol,\n"
     "  agg.option_oi_total,\n"
     "  agg.option_vol_total,\n"
     "  CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL\n"
     "       THEN agg.call_atm_mid + agg.put_atm_mid END AS atm_straddle_price,\n"
     "  CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL\n"
     "            AND cl.close > 0\n"
     "       THEN (agg.call_atm_mid + agg.put_atm_mid) / cl.close END AS implied_move_pct,\n"
     "  agg.put_call_oi_ratio,\n"
     "  agg.put_call_volume_ratio,\n"
     "  agg.bid_ask_spread_pct\n"
     "FROM latest_close cl\n"
     "LEFT JOIN aggregated agg ON agg.symbol = cl.symbol")))

;;; ── Upsert ───────────────────────────────────────────────────────────────────

(defn- upsert-oca-row! [ds sym values]
  (let [col-names  (mapv name (keys values))
        col-vals   (vec (vals values))
        set-clause (str/join ", " (map #(str % " = excluded." %) col-names))
        sql (str "INSERT INTO latest_indicators (symbol, "
                 (str/join ", " col-names)
                 ") VALUES (?, "
                 (str/join ", " (repeat (count col-names) "?"))
                 ") ON CONFLICT (symbol) DO UPDATE SET "
                 set-clause)]
    (jdbc/execute! ds (into [sql sym] col-vals))))

;;; ── Public entry point ───────────────────────────────────────────────────────

(defn refresh-option-chain-agg!
  "Compute option chain aggregate columns from option_chain (scoped to ±1 strike
   of ATM across the front two expirations) and upsert into latest_indicators.
   Adds the 7 DOUBLE columns if absent. Without a symbols list, processes all
   symbols present in bars_daily; with a list, restricts to those symbols.
   Symbols with no chain rows get NULL for all seven columns."
  [ds & [symbols]]
  (ensure-oca-columns! ds)
  (let [sql    (agg-sql symbols)
        params (if (seq symbols) (vec symbols) [])
        rows   (jdbc/execute! ds (into [sql] params)
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row rows]
      (let [sym    (:symbol row)
            values (dissoc row :symbol)]
        (upsert-oca-row! ds sym values)))))
