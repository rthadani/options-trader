(ns options-trader.db.queries.refresh
  "Refresh-pipeline query API. SQL lives in resources/sql/refresh.sql; hugsql
   generates `*-sqlvec` fns that return [sql & params] vectors. Callers
   never touch raw SQL strings — they call the fns below.

   Batch INSERTs (`*-batch!`) take a sequence of row vectors and dispatch
   to next.jdbc/execute-batch! with the SQL string drawn from the .sql
   file. The single-row hugsql sqlvec form is used only to retrieve the
   prepared statement text; the actual row values stream through
   execute-batch! one row at a time."
  (:require [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [options-trader.util :refer [as-lower query-scalar]]))

;; Generates options-trader.db.queries.refresh/<name>-sqlvec for every
;; -- :name in resources/sql/refresh.sql. Each returns [sql & params].
(hugsql/def-sqlvec-fns "sql/refresh.sql")

;;; ── refresh_log ─────────────────────────────────────────────────────

(defn next-refresh-log-id [ds]
  (query-scalar ds (next-refresh-log-id-sqlvec) :n))

(defn insert-refresh-log! [ds {:keys [id task symbol started-at]}]
  (jdbc/execute-one! ds (insert-refresh-log-sqlvec
                          {:id id :task task :symbol symbol
                           :started-at started-at})))

(defn finish-refresh-log! [ds {:keys [id status error finished-at]}]
  (jdbc/execute-one! ds (finish-refresh-log-sqlvec
                          {:id id :status status :error error
                           :finished-at finished-at})))

;;; ── Internal helpers ────────────────────────────────────────────────

(defn- execute-batch! [ds sqlvec rows]
  (when (seq rows)
    (let [[sql] sqlvec]
      (jdbc/execute-batch! ds sql rows {})))
  (count rows))

;;; ── bars_daily ──────────────────────────────────────────────────────

(defn latest-bar-date [ds symbol]
  (query-scalar ds (latest-bar-date-sqlvec {:symbol symbol}) :d))

(defn insert-bars-daily-batch! [ds rows]
  (execute-batch! ds (insert-bars-daily-batch-sqlvec) rows))

;;; ── bars_intraday ───────────────────────────────────────────────────

(defn latest-intraday-ts [ds symbol bar-size]
  (query-scalar ds (latest-intraday-ts-sqlvec {:symbol symbol :bar-size bar-size}) :t))

(defn insert-bars-intraday-batch! [ds rows]
  (execute-batch! ds (insert-bars-intraday-batch-sqlvec) rows))

;;; ── news ────────────────────────────────────────────────────────────

(defn latest-news-published [ds symbol]
  (query-scalar ds (latest-news-published-sqlvec {:symbol symbol}) :t))

(defn insert-news-batch! [ds rows]
  (execute-batch! ds (insert-news-batch-sqlvec) rows))

;;; ── filings ─────────────────────────────────────────────────────────

(defn latest-filing-date [ds symbol]
  (query-scalar ds (latest-filing-date-sqlvec {:symbol symbol}) :d))

(defn insert-filings-batch! [ds rows]
  (execute-batch! ds (insert-filings-batch-sqlvec) rows))

;;; ── universes ───────────────────────────────────────────────────────

(defn current-members [ds universe]
  (->> (jdbc/execute! ds (select-universe-members-sqlvec {:universe universe}) as-lower)
       (map :symbol)
       set))

(defn next-drift-id [ds]
  (query-scalar ds (next-universe-drift-id-sqlvec) :n))

(defn insert-drift! [ds {:keys [id universe symbol action]}]
  (jdbc/execute-one! ds (insert-universe-drift-sqlvec
                          {:id id :universe universe :symbol symbol :action action})))

(defn upsert-universe! [ds {:keys [name description]}]
  (jdbc/execute-one! ds (upsert-universe-sqlvec {:name name :description description})))

(defn add-member! [ds {:keys [universe symbol]}]
  (jdbc/execute-one! ds (insert-universe-member-sqlvec
                          {:universe universe :symbol symbol})))

(defn remove-member! [ds {:keys [universe symbol]}]
  (jdbc/execute-one! ds (delete-universe-member-sqlvec
                          {:universe universe :symbol symbol})))

;;; ── iv_daily ─────────────────────────────────────────────────────────

(defn latest-iv-date [ds symbol]
  (query-scalar ds (latest-iv-date-sqlvec {:symbol symbol}) :d))

(defn upsert-iv-row! [ds {:keys [symbol iv-date iv30 hv30]}]
  (jdbc/execute-one! ds
    (upsert-iv-row-sqlvec {:symbol symbol :iv-date iv-date
                            :iv30 iv30 :hv30 hv30})))

;;; ── short_interest ───────────────────────────────────────────────────

(defn upsert-short-interest! [ds row]
  (jdbc/execute-one! ds (upsert-short-interest-sqlvec row)))

;;; ── earnings ─────────────────────────────────────────────────────────

(defn upsert-earnings-event! [ds row]
  (jdbc/execute-one! ds (upsert-earnings-event-sqlvec row)))

(defn upsert-earnings-calendar! [ds row]
  (jdbc/execute-one! ds (upsert-earnings-calendar-sqlvec row)))
