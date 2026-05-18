(ns options-trader.indicators.event-flags
  "Activist/M&A/FDA event-flag slice: surfaces ma_rumor_flag, activist_filing_flag,
   and fda_event_flag as BOOLEAN columns on latest_indicators, computed by joining
   news, filings, and event_calendars against the per-symbol set in latest_indicators.
   M&A keywords are read at refresh time from resources/event-flags.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(defn ensure-schema!
  "Add the three BOOLEAN event-flag columns to latest_indicators.
   ALTER TABLE ... ADD COLUMN IF NOT EXISTS is idempotent."
  [ds]
  (doseq [col ["ma_rumor_flag" "activist_filing_flag" "fda_event_flag"]]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS " col " BOOLEAN")])))

;;; ── SQL builder ──────────────────────────────────────────────────────────────

(defn- ma-body-clause
  "Build OR-joined LIKE conditions for each M&A keyword against the news body JSON field."
  [keywords]
  (str/join " OR "
    (map #(str "lower(n.data->>'body') LIKE '%" (str/lower-case %) "%'") keywords)))

(defn- event-flags-sql [keywords]
  (str
    "WITH
     syms AS (
       SELECT DISTINCT symbol FROM latest_indicators
     ),
     ma_hits AS (
       SELECT DISTINCT n.symbol
       FROM news n
       WHERE n.published_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
         AND TRY_CAST(n.data->>'sentiment_score' AS DOUBLE) > 0.2
         AND (" (ma-body-clause keywords) ")
     ),
     activist_hits AS (
       SELECT DISTINCT symbol
       FROM filings
       WHERE form_type IN ('13D', '13G')
         AND filed_at >= CURRENT_DATE - INTERVAL '90 days'
     ),
     fda_hits AS (
       SELECT DISTINCT symbol
       FROM event_calendars
       WHERE event_type = 'fda'
         AND event_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '14 days'
     )
     SELECT
       s.symbol,
       COALESCE(ma.symbol  IS NOT NULL, FALSE) AS ma_rumor_flag,
       COALESCE(ac.symbol  IS NOT NULL, FALSE) AS activist_filing_flag,
       COALESCE(fda.symbol IS NOT NULL, FALSE) AS fda_event_flag
     FROM syms s
     LEFT JOIN ma_hits       ma  ON s.symbol = ma.symbol
     LEFT JOIN activist_hits ac  ON s.symbol = ac.symbol
     LEFT JOIN fda_hits      fda ON s.symbol = fda.symbol"))

;;; ── Upsert ───────────────────────────────────────────────────────────────────

(defn- upsert-row! [ds sym values]
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

(defn refresh-event-flags!
  "Compute M&A-rumor, activist-filing, and FDA event flags by joining news, filings,
   and event_calendars against the symbol set in latest_indicators. Upserts one row
   per symbol. All three columns COALESCE to FALSE when no source rows match."
  [ds]
  (ensure-schema! ds)
  (let [cfg      (edn/read-string (slurp (io/resource "event-flags.edn")))
        keywords (:ma-keywords cfg)
        sql      (event-flags-sql keywords)
        rows     (jdbc/execute! ds [sql] {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row rows]
      (upsert-row! ds (:symbol row) (dissoc row :symbol)))))
