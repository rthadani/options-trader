(ns options-trader.db.duckdb-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.test-util :as tu]))


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
         tu/as-lower)
       (map :table_name)
       set))


(deftest bootstrap-creates-all-tables-test
  (testing "bootstrap! creates every expected Phase 3 table"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)
          ts  (table-set ds)]
      (doseq [t expected-tables]
        (is (contains? ts t)
            (str "expected table missing after bootstrap!: " t))))))


(deftest bootstrap-idempotency-test
  (testing "calling bootstrap! twice records each migration exactly once"
    (let [cfg (tu/tempfile-cfg)]
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


(deftest bars-daily-round-trip-test
  (testing "insert and read back a bars_daily row"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (jdbc/execute! ds
        ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
          VALUES (?, ?, ?, ?, ?, ?, ?)"
         "AAPL" "2024-01-02" 185.0 187.5 184.0 186.0 52000000])
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM bars_daily WHERE symbol = 'AAPL'"]
                   tu/as-lower)]
        (is (= 1 (count rows)))
        (is (= "AAPL" (-> rows first :symbol)))
        (is (= 186.0  (-> rows first :close)))
        (is (= 52000000 (-> rows first :volume)))))))


(deftest positions-round-trip-test
  (testing "insert and read back a positions row"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (jdbc/execute! ds
        ["INSERT INTO positions (account, symbol, opt_right, quantity, avg_cost)
          VALUES (?, ?, ?, ?, ?)"
         "DU123456" "AAPL" "" 100 185.50])
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM positions WHERE account = 'DU123456'"]
                   tu/as-lower)]
        (is (= 1 (count rows)))
        (is (= "AAPL"     (-> rows first :symbol)))
        (is (= 100        (-> rows first :quantity)))
        (is (= 185.50     (-> rows first :avg_cost)))))))
