(ns options-trader.indicators.runner-test
  "Integration tests for the ta4j-driven indicator runner.
   Each test creates its own in-memory DuckDB so state never leaks between tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.indicators.runner :as runner]))

;;; ── Test helpers ────────────────────────────────────────────────────────────

(defn- make-ds
  "Fresh in-memory DuckDB with minimal bars_daily + latest_indicators schema.
   Returns a persistent Connection so all operations share one in-memory database."
  []
  (let [conn (jdbc/get-connection {:jdbcUrl "jdbc:duckdb:"})]
    (jdbc/execute! conn
      ["CREATE TABLE bars_daily (
          symbol   VARCHAR NOT NULL,
          bar_date DATE    NOT NULL,
          open     DOUBLE,
          high     DOUBLE,
          low      DOUBLE,
          close    DOUBLE,
          volume   BIGINT,
          PRIMARY KEY (symbol, bar_date))"])
    (jdbc/execute! conn
      ["CREATE TABLE latest_indicators (
          symbol     VARCHAR PRIMARY KEY,
          updated_at TIMESTAMP DEFAULT current_timestamp)"])
    conn))

(defn- seed!
  "Insert 50-bar OHLCV fixture rows for sym into bars_daily."
  [ds sym]
  (let [rows (edn/read-string (slurp (io/resource "fixtures/ohlcv-50.edn")))]
    (doseq [{:keys [bar_date open high low close volume]} rows]
      (jdbc/execute! ds
        ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
          VALUES (?, CAST(? AS DATE), ?, ?, ?, ?, ?)"
         sym bar_date open high low close volume]))))

(defn- query-row
  "Fetch the latest_indicators row for sym as a lower-keys map."
  [ds sym]
  (first (jdbc/execute! ds
           [(str "SELECT * FROM latest_indicators WHERE symbol = ?") sym]
           {:builder-fn rs/as-unqualified-lower-maps})))

;;; ── Tests ───────────────────────────────────────────────────────────────────

(deftest rsi-14-is-100-for-monotone-series
  (testing "rsi_14 = 100.0 for strictly-increasing 50-bar series (all gains, no losses)"
    (let [ds (make-ds)]
      (seed! ds "MONO")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (query-row ds "MONO")]
        (is (some? row)         "row should be written for MONO")
        (is (some? (:rsi_14 row)) "rsi_14 must be non-NULL")
        (is (< (Math/abs (- (double (:rsi_14 row)) 100.0)) 0.001)
            (str "expected rsi_14 ≈ 100.0 (all-up series), got " (:rsi_14 row)))))))

(deftest atr-14-matches-reference-value
  (testing "atr_14 ≈ 3.0 for constant true-range 50-bar series"
    ;; Each bar: high = close+1.5, low = close-1.5, prev_close+1.0 each step
    ;; TR = max(3.0, 2.5, 0.5) = 3.0 for every bar — Wilder ATR converges to 3.0
    (let [ds (make-ds)]
      (seed! ds "ATRREF")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (query-row ds "ATRREF")]
        (is (some? (:atr_14 row)) "atr_14 must be non-NULL")
        (is (< (Math/abs (- (double (:atr_14 row)) 3.0)) 0.01)
            (str "expected atr_14 ≈ 3.0, got " (:atr_14 row)))))))

(deftest ensure-schema-adds-rsi-column
  (testing "ensure-schema! creates rsi_14 DOUBLE column; safe to call twice (idempotent)"
    (let [ds (make-ds)]
      (runner/ensure-schema! ds)
      (runner/ensure-schema! ds)
      (let [cols (jdbc/execute! ds
                   ["SELECT column_name
                     FROM information_schema.columns
                     WHERE table_name = 'latest_indicators'
                       AND column_name = 'rsi_14'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (count cols)) "rsi_14 column should exist after ensure-schema!")))))

(deftest refresh-runner-indicators-is-idempotent
  (testing "calling refresh-runner-indicators! twice produces exactly one row per symbol"
    (let [ds (make-ds)]
      (seed! ds "IDEM")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (runner/refresh-runner-indicators! ds)
      (let [cnt (-> (jdbc/execute! ds
                      ["SELECT COUNT(*) AS cnt FROM latest_indicators WHERE symbol = 'IDEM'"]
                      {:builder-fn rs/as-unqualified-lower-maps})
                    first :cnt)]
        (is (= 1 (long cnt)) "ON CONFLICT upsert must yield exactly one row")))))

(deftest symbol-with-no-bars-produces-no-row
  (testing "a symbol absent from bars_daily generates no row in latest_indicators"
    (let [ds (make-ds)]
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'GHOST'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (empty? rows) "no row expected for symbol not in bars_daily")))))

(deftest multiple-symbols-each-get-a-row
  (testing "two independent symbols both appear in latest_indicators"
    (let [ds (make-ds)]
      (seed! ds "AAA")
      (seed! ds "ZZZ")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [syms (->> (jdbc/execute! ds
                        ["SELECT symbol FROM latest_indicators ORDER BY symbol"]
                        {:builder-fn rs/as-unqualified-lower-maps})
                      (mapv :symbol))]
        (is (some #{"AAA"} syms) "AAA should appear in latest_indicators")
        (is (some #{"ZZZ"} syms) "ZZZ should appear in latest_indicators")))))

(deftest sma-50-computable-from-50-bars
  (testing "sma_50 is non-NULL when exactly 50 bars are loaded"
    (let [ds (make-ds)]
      (seed! ds "SMA50")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (query-row ds "SMA50")]
        (is (some? (:sma_50 row)) "sma_50 should be computable from 50 bars")
        ;; mean of close 100..149 = 124.5
        (is (< (Math/abs (- (double (:sma_50 row)) 124.5)) 0.001)
            (str "expected sma_50 ≈ 124.5, got " (:sma_50 row)))))))
