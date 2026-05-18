(ns options-trader.db.duckdb-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db])
  (:import [java.io File]))

;;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- tempfile-cfg []
  (let [f (File/createTempFile "duckdb-test-" ".duckdb")
        path (.getAbsolutePath f)]
    (.delete f)  ; DuckDB requires a non-existent or valid DB file
    {:db {:path path}}))

(def ^:private expected-tables
  #{"bars_daily" "bars_intraday" "quotes" "option_chain" "iv_daily"
    "iv_backfill_status" "fundamentals" "filings" "news" "positions"
    "account_summary" "earnings_events" "earnings_calendar" "short_interest"
    "sector_map" "event_calendars" "benchmark_returns" "universes"
    "universe_members" "universe_drift_log" "indicator_renames" "refresh_log"
    "universe_lookup_failures" "screens" "conversations" "investigations"
    "investigation_findings" "orders" "fills" "latest_indicators"
    "indicator_history" "option_chain_agg" "sector_metrics"
    "_schema_migrations"})

(defn- table-set [ds]
  (->> (jdbc/execute! ds
         ["SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'"]
         {:builder-fn rs/as-unqualified-lower-maps})
       (map :table_name)
       set))

;;; ── Table-existence test ─────────────────────────────────────────────────────

(deftest bootstrap-creates-all-tables-test
  (testing "bootstrap! creates every expected Phase 3 table"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)
          ts  (table-set ds)]
      (doseq [t expected-tables]
        (is (contains? ts t)
            (str "expected table missing after bootstrap!: " t))))))

;;; ── Idempotency test ─────────────────────────────────────────────────────────

(deftest bootstrap-idempotency-test
  (testing "calling bootstrap! twice records each migration exactly once"
    (let [cfg (tempfile-cfg)]
      (db/bootstrap! cfg)
      (db/bootstrap! cfg)
      (let [ds      (db/datasource cfg)
            applied (db/applied-migrations ds)]
        (is (= 2 (count applied))
            (str "expected exactly 2 migration rows after two bootstrap! calls, got: "
                 (count applied)))
        (is (contains? applied 1)
            "_schema_migrations must contain version 1")
        (is (contains? applied 2)
            "_schema_migrations must contain version 2")))))

;;; ── bars_daily round-trip ────────────────────────────────────────────────────

(deftest bars-daily-round-trip-test
  (testing "insert and read back a bars_daily row"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (jdbc/execute! ds
        ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
          VALUES (?, ?, ?, ?, ?, ?, ?)"
         "AAPL" "2024-01-02" 185.0 187.5 184.0 186.0 52000000])
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM bars_daily WHERE symbol = 'AAPL'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (count rows)))
        (is (= "AAPL" (-> rows first :symbol)))
        (is (= 186.0  (-> rows first :close)))
        (is (= 52000000 (-> rows first :volume)))))))

;;; ── positions round-trip ─────────────────────────────────────────────────────

(deftest positions-round-trip-test
  (testing "insert and read back a positions row"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (jdbc/execute! ds
        ["INSERT INTO positions (account, symbol, opt_right, quantity, avg_cost)
          VALUES (?, ?, ?, ?, ?)"
         "DU123456" "AAPL" "" 100 185.50])
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM positions WHERE account = 'DU123456'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (count rows)))
        (is (= "AAPL"     (-> rows first :symbol)))
        (is (= 100        (-> rows first :quantity)))
        (is (= 185.50     (-> rows first :avg_cost)))))))
