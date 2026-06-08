(ns options-trader.db.queries.indicators
  "Indicator-pipeline query API. Static SQL (lookups + recompute CTEs) lives
   in resources/sql/indicators.sql; this ns wraps them and also exposes the
   shared dynamic-column upsert that every indicator pass uses."
  (:require [clojure.string :as str]
            [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [options-trader.util :refer [as-lower query-scalar]]))

(hugsql/def-sqlvec-fns "sql/indicators.sql")

;;; ── Lookups ──────────────────────────────────────────────────────────

(defn select-latest-indicators
  "Latest-indicator rows for the given vector of symbols."
  [ds symbols]
  (if (seq symbols)
    (jdbc/execute! ds (select-latest-indicators-sqlvec {:symbols symbols}) as-lower)
    []))

(defn all-bars-daily-symbols
  "All distinct symbols present in bars_daily, sorted."
  [ds]
  (mapv :symbol (jdbc/execute! ds (all-bars-daily-symbols-sqlvec) as-lower)))

(defn load-bars-daily
  "All bars_daily rows for symbol, ascending."
  [ds symbol]
  (jdbc/execute! ds (load-bars-daily-sqlvec {:symbol symbol}) as-lower))

(defn latest-close
  "Most-recent close from bars_daily for symbol, or nil."
  [ds symbol]
  (query-scalar ds (latest-close-sqlvec {:symbol symbol}) :close))

(defn persist-fundamentals!
  "Upsert one row into fundamentals keyed on (symbol, period). data is
   the JSON-serialised canonical payload (caller responsible for the
   serialisation)."
  [ds {:keys [symbol period data]}]
  (jdbc/execute-one! ds (persist-fundamentals-sqlvec
                          {:symbol symbol :period period :data data})))

(defn insert-indicator-history!
  "Append one observation to indicator_history; idempotent on the
   (symbol, indicator, ind_date) unique."
  [ds {:keys [symbol indicator ind-date value]}]
  (jdbc/execute-one! ds (insert-indicator-history-sqlvec
                          {:symbol symbol :indicator indicator
                           :ind-date ind-date :value value})))

(defn percent-rank-latest
  "PERCENT_RANK of the latest indicator value over the trailing n-days
   window. Returns nil when no history rows exist for that symbol +
   indicator."
  [ds {:keys [symbol indicator n-days]}]
  (query-scalar ds (percent-rank-latest-sqlvec
                      {:symbol symbol :indicator indicator :n-days n-days})
                :pr))

;;; ── Schema helpers ───────────────────────────────────────────────────
;;
;; ALTER TABLE column names can't be bound via JDBC params (the IDENT must
;; be literal SQL). Build the statement with string concat — values come
;; from trusted indicator-config code, never user input.

(defn ensure-double-columns!
  "Idempotently ADD missing DOUBLE columns to latest_indicators."
  [ds col-keywords]
  (doseq [col col-keywords]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
            (name col) " DOUBLE")])))

(defn ensure-varchar-column!
  "Idempotently ADD a VARCHAR column to latest_indicators."
  [ds col-keyword]
  (jdbc/execute! ds
    [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
          (name col-keyword) " VARCHAR")]))

(defn ensure-column-with-type!
  "Idempotently ADD a column with explicit SQL type to a table.
   Used by passes that need non-DOUBLE columns or non-latest_indicators tables."
  [ds table col-keyword sql-type]
  (jdbc/execute! ds
    [(str "ALTER TABLE " (name table)
          " ADD COLUMN IF NOT EXISTS " (name col-keyword) " " sql-type)]))

;;; ── Dynamic upsert into latest_indicators ────────────────────────────

(defn upsert-latest-row!
  "Upsert one row into latest_indicators keyed on (symbol). `values` is
   a map of {column-keyword value}; only the columns present are written.
   No-op when values is empty."
  [ds symbol values]
  (when (seq values)
    (let [cols       (mapv name (keys values))
          set-clause (str/join ", " (map #(str % " = excluded." %) cols))
          sql        (str "INSERT INTO latest_indicators (symbol, "
                          (str/join ", " cols)
                          ") VALUES (?, "
                          (str/join ", " (repeat (count cols) "?"))
                          ") ON CONFLICT (symbol) DO UPDATE SET "
                          set-clause)]
      (jdbc/execute! ds (into [sql symbol] (vals values))))))

(defn upsert-row!
  "Generalised upsert: works for any table keyed on a single column. Used
   by indicator passes that target tables other than latest_indicators
   (e.g. sector_metrics keyed on (sector, ts))."
  [ds {:keys [table key-cols] :as _opts} key-vals values]
  (when (seq values)
    (let [cols       (mapv name (keys values))
          set-clause (str/join ", " (map #(str % " = excluded." %) cols))
          all-cols   (concat (map name key-cols) cols)
          sql        (str "INSERT INTO " (name table) " ("
                          (str/join ", " all-cols)
                          ") VALUES ("
                          (str/join ", " (repeat (count all-cols) "?"))
                          ") ON CONFLICT (" (str/join ", " (map name key-cols)) ") DO UPDATE SET "
                          set-clause)]
      (jdbc/execute! ds (into [sql] (concat key-vals (vals values)))))))

;;; ── Run a static recompute pass ──────────────────────────────────────

(defn run-static-recompute!
  "Run an indicator-pass SELECT (typically a big CTE that yields one row
   per symbol of derived values), then upsert each row into
   latest_indicators. Returns the row count."
  [ds sqlvec]
  (let [rows (jdbc/execute! ds sqlvec as-lower)]
    (doseq [row rows]
      (upsert-latest-row! ds (:symbol row) (dissoc row :symbol)))
    (count rows)))
