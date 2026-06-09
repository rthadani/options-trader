(ns options-trader.indicators.runner-test
  "Integration tests for the ta4j-driven runner; per-test in-memory DuckDB."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.indicators.runner :as runner]
            [options-trader.test-util :as tu]))


(defn- seed! [ds sym]
  (tu/seed-ohlcv! ds sym tu/raw-bars))


(deftest rsi-14-is-100-for-monotone-series
  (testing "rsi_14 = 100.0 for strictly-increasing 50-bar series (all gains, no losses)"
    (let [ds (tu/make-ds)]
      (seed! ds "MONO")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (tu/query-row ds "MONO")]
        (is (some? row)         "row should be written for MONO")
        (is (some? (:rsi_14 row)) "rsi_14 must be non-NULL")
        (is (< (Math/abs (- (double (:rsi_14 row)) 100.0)) 0.001)
            (str "expected rsi_14 ≈ 100.0 (all-up series), got " (:rsi_14 row)))))))

(deftest atr-14-matches-reference-value
  (testing "atr_14 ≈ 3.0 for constant true-range 50-bar series"
    ;; Each bar: high = close+1.5, low = close-1.5, prev_close+1.0 each step
    ;; TR = max(3.0, 2.5, 0.5) = 3.0 for every bar — Wilder ATR converges to 3.0
    (let [ds (tu/make-ds)]
      (seed! ds "ATRREF")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (tu/query-row ds "ATRREF")]
        (is (some? (:atr_14 row)) "atr_14 must be non-NULL")
        (is (< (Math/abs (- (double (:atr_14 row)) 3.0)) 0.01)
            (str "expected atr_14 ≈ 3.0, got " (:atr_14 row)))))))

(deftest ensure-schema-adds-rsi-column
  (testing "ensure-schema! creates rsi_14 DOUBLE column; safe to call twice (idempotent)"
    (let [ds (tu/make-ds)]
      (runner/ensure-schema! ds)
      (runner/ensure-schema! ds)
      (let [cols (jdbc/execute! ds
                   ["SELECT column_name
                     FROM information_schema.columns
                     WHERE table_name = 'latest_indicators'
                       AND column_name = 'rsi_14'"]
                   tu/as-lower)]
        (is (= 1 (count cols)) "rsi_14 column should exist after ensure-schema!")))))

(deftest refresh-runner-indicators-is-idempotent
  (testing "calling refresh-runner-indicators! twice produces exactly one row per symbol"
    (let [ds (tu/make-ds)]
      (seed! ds "IDEM")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (runner/refresh-runner-indicators! ds)
      (let [cnt (-> (jdbc/execute! ds
                      ["SELECT COUNT(*) AS cnt FROM latest_indicators WHERE symbol = 'IDEM'"]
                      tu/as-lower)
                    first :cnt)]
        (is (= 1 (long cnt)) "ON CONFLICT upsert must yield exactly one row")))))

(deftest symbol-with-no-bars-produces-no-row
  (testing "a symbol absent from bars_daily generates no row in latest_indicators"
    (let [ds (tu/make-ds)]
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'GHOST'"]
                   tu/as-lower)]
        (is (empty? rows) "no row expected for symbol not in bars_daily")))))

(deftest multiple-symbols-each-get-a-row
  (testing "two independent symbols both appear in latest_indicators"
    (let [ds (tu/make-ds)]
      (seed! ds "AAA")
      (seed! ds "ZZZ")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [syms (->> (jdbc/execute! ds
                        ["SELECT symbol FROM latest_indicators ORDER BY symbol"]
                        tu/as-lower)
                      (mapv :symbol))]
        (is (some #{"AAA"} syms) "AAA should appear in latest_indicators")
        (is (some #{"ZZZ"} syms) "ZZZ should appear in latest_indicators")))))

(deftest sma-50-computable-from-50-bars
  (testing "sma_50 is non-NULL when exactly 50 bars are loaded"
    (let [ds (tu/make-ds)]
      (seed! ds "SMA50")
      (runner/ensure-schema! ds)
      (runner/refresh-runner-indicators! ds)
      (let [row (tu/query-row ds "SMA50")]
        (is (some? (:sma_50 row)) "sma_50 should be computable from 50 bars")
        ;; mean of close 100..149 = 124.5
        (is (< (Math/abs (- (double (:sma_50 row)) 124.5)) 0.001)
            (str "expected sma_50 ≈ 124.5, got " (:sma_50 row)))))))
