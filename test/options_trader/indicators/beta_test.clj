(ns options-trader.indicators.beta-test
  "Tests for the 252-day OLS beta slice. Each deftest is self-describing."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.test-util :as tu]
            [options-trader.indicators.beta :as beta])
  (:import [java.sql Date]
           [java.time LocalDate]))



(defn- make-dates
  "Generate n consecutive LocalDate values starting from 2020-01-01."
  [n]
  (let [start (LocalDate/of 2020 1 1)]
    (mapv #(.plusDays start %) (range n))))

(defn- seed-bars!
  "Insert bars_daily rows for sym. dates is a seq of LocalDate, closes a seq of doubles."
  [ds sym dates closes]
  (doseq [[d c] (map vector dates closes)]
    (jdbc/execute! ds
      ["INSERT INTO bars_daily (symbol, bar_date, close) VALUES (?, ?, ?)"
       sym (Date/valueOf d) c])))

(defn- seed-benchmark!
  "Insert benchmark_returns rows. dates is a seq of LocalDate, rets a seq of doubles."
  [ds benchmark dates rets]
  (doseq [[d r] (map vector dates rets)]
    (jdbc/execute! ds
      ["INSERT INTO benchmark_returns (benchmark, ret_date, ret_1d) VALUES (?, ?, ?)"
       benchmark (Date/valueOf d) r])))

(defn- fetch-beta [ds sym]
  (:beta_252d
   (first (jdbc/execute! ds
            [(str "SELECT beta_252d FROM latest_indicators WHERE symbol = '" sym "'")]
            tu/as-lower))))


(deftest happy-path-260-paired-bars-test
  (testing "260 paired observations with y=2x relationship → beta_252d ≈ 2.0"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; 261 bars → 260 non-NULL log returns; benchmark covers all 260 dates
      (let [n            261
            dates        (make-dates n)
            bench-rets   (mapv #(* (- % 130) 0.0002) (range 1 n))
            sym-log-rets (mapv #(* 2.0 %) bench-rets)
            closes       (vec (reductions (fn [c lr] (* c (Math/exp lr))) 100.0 sym-log-rets))]
        (seed-bars!      ds "BTEST" dates closes)
        (seed-benchmark! ds "SPY" (rest dates) bench-rets)
        (beta/refresh-beta! ds "SPY")
        (let [v (fetch-beta ds "BTEST")]
          (is (some? v) "beta_252d must not be NULL with 260 paired observations")
          (is (< (Math/abs (- v 2.0)) 0.001)
              (str "expected beta ≈ 2.0, got " v)))))))


(deftest short-history-null-test
  (testing "249 paired observations (< 252 minimum) → beta_252d is NULL"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (let [n            250
            dates        (make-dates n)
            bench-rets   (mapv #(* (- % 124) 0.0002) (range 1 n))
            sym-log-rets (mapv #(* 2.0 %) bench-rets)
            closes       (vec (reductions (fn [c lr] (* c (Math/exp lr))) 100.0 sym-log-rets))]
        (seed-bars!      ds "SHORT" dates closes)
        (seed-benchmark! ds "SPY" (rest dates) bench-rets)
        (beta/refresh-beta! ds "SPY")
        (let [v (fetch-beta ds "SHORT")]
          (is (nil? v) "249 paired observations → beta_252d must be NULL"))))))


(deftest missing-benchmark-null-test
  (testing "symbol has 261 bars but no benchmark data → no beta row / NULL"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (let [n      261
            dates  (make-dates n)
            closes (vec (reductions (fn [c _] (* c 1.01)) 100.0 (range n)))]
        (seed-bars! ds "NOBENCH" dates closes)
        ;; Intentionally no benchmark rows seeded
        (beta/refresh-beta! ds "SPY")
        (let [row (first (jdbc/execute! ds
                           ["SELECT beta_252d FROM latest_indicators WHERE symbol = 'NOBENCH'"]
                           tu/as-lower))]
          (is (or (nil? row) (nil? (:beta_252d row)))
              "no benchmark_returns rows → beta_252d must be NULL or row absent"))))))


(deftest idempotency-test
  (testing "two successive refresh! calls leave exactly one row per symbol"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (let [n            261
            dates        (make-dates n)
            bench-rets   (mapv #(* (- % 130) 0.0002) (range 1 n))
            sym-log-rets (mapv #(* 1.5 %) bench-rets)
            closes       (vec (reductions (fn [c lr] (* c (Math/exp lr))) 100.0 sym-log-rets))]
        (seed-bars!      ds "IDEM" dates closes)
        (seed-benchmark! ds "SPY" (rest dates) bench-rets)
        (beta/refresh-beta! ds "SPY")
        (beta/refresh-beta! ds "SPY")
        (let [cnt (-> (jdbc/execute! ds
                        ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                        tu/as-lower)
                      first :n)
              v   (fetch-beta ds "IDEM")]
          (is (= 1 cnt)  "upsert must not duplicate rows on repeated calls")
          (is (some? v)  "beta_252d must be present after idempotent upsert"))))))


(deftest perfect-correlation-beta-one-test
  (testing "sym_log_ret identical to bench_ret → regr_slope = 1.0 exactly"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (let [n          261
            dates      (make-dates n)
            bench-rets (mapv #(* (- % 130) 0.0002) (range 1 n))
            closes     (vec (reductions (fn [c lr] (* c (Math/exp lr))) 100.0 bench-rets))]
        (seed-bars!      ds "PERFECT" dates closes)
        (seed-benchmark! ds "SPY" (rest dates) bench-rets)
        (beta/refresh-beta! ds "SPY")
        (let [v (fetch-beta ds "PERFECT")]
          (is (some? v) "perfectly correlated series must produce a non-NULL beta")
          (is (< (Math/abs (- v 1.0)) 1e-9)
              (str "sym_log_ret = bench_ret → regr_slope must equal 1.0, got " v)))))))
