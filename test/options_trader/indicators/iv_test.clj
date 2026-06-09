(ns options-trader.indicators.iv-test
  "Tests for IV/HV derived indicator columns. Expected values are
   hand-computed in each deftest's comment."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.iv :as iv]
            [options-trader.test-util :as tu])
  (:import [java.sql Date]))

(defn- seed-iv-daily!
  "Insert n rows of synthetic iv_daily data for sym.
   iv30[i] = base-iv + i*iv-step, hv30[i] = base-hv + i*hv-step, i=0..n-1 (oldest first)."
  [ds sym n base-iv iv-step base-hv hv-step]
  (let [base (java.time.LocalDate/of 2020 1 2)]
    (doseq [i (range n)]
      (let [dt   (.plusDays base (long i))
            iv30 (+ base-iv (* i iv-step))
            hv30 (+ base-hv (* i hv-step))]
        (jdbc/execute! ds
          ["INSERT INTO iv_daily (symbol, iv_date, iv30, hv30) VALUES (?, ?, ?, ?)"
           sym (Date/valueOf dt) iv30 hv30])))))


(deftest all-windows-fill-test
  (testing "symbol with 300 rows: all three windows non-null, hand-computed ranks = 100.0"
    ;; iv30[i] = 20 + i*0.1, hv30[i] = 10 + i*0.05, i=0..299
    ;; Latest (i=299): iv30=49.9, hv30=24.95
    ;; 252d window (rn 1..252 = rows i=48..299):
    ;;   min_iv=24.8, max_iv=49.9 → iv_rank_252d = 100.0
    ;;   pct_iv = 251/252 * 100 ≈ 99.603
    ;; iv_minus_hv = 49.9 - 24.95 = 24.95
    ;; iv_rank_window_used = "252d"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-iv-daily! ds "AAAA" 300 20.0 0.1 10.0 0.05)
      (iv/refresh-iv-indicators! ds)
      (let [row (tu/query-row ds "AAAA")]
        (testing "row exists"
          (is (some? row)))
        (testing "all iv_rank windows are non-null"
          (is (some? (:iv_rank_252d row)) "iv_rank_252d")
          (is (some? (:iv_rank_126d row)) "iv_rank_126d")
          (is (some? (:iv_rank_63d  row)) "iv_rank_63d"))
        (testing "all iv_percentile windows are non-null"
          (is (some? (:iv_percentile_252d row)) "iv_percentile_252d")
          (is (some? (:iv_percentile_126d row)) "iv_percentile_126d")
          (is (some? (:iv_percentile_63d  row)) "iv_percentile_63d"))
        (testing "hv columns are non-null"
          (is (some? (:hv_rank_252d      row)) "hv_rank_252d")
          (is (some? (:hv_percentile_252d row)) "hv_percentile_252d"))
        (testing "iv_minus_hv is non-null"
          (is (some? (:iv_minus_hv row))))
        (testing "iv_rank_window_used = '252d'"
          (is (= "252d" (:iv_rank_window_used row))))
        ;; Monotonic iv30 → today is the max → rank = 100
        (testing "iv_rank_252d = 100.0"
          (is (tu/approx= (:iv_rank_252d row) 100.0)))
        (testing "iv_rank_126d = 100.0"
          (is (tu/approx= (:iv_rank_126d row) 100.0)))
        (testing "iv_rank_63d = 100.0"
          (is (tu/approx= (:iv_rank_63d row) 100.0)))
        ;; 251 of 252 rows have iv30 < 49.9 → pct = 251/252*100
        (testing "iv_percentile_252d ≈ 251/252*100"
          (is (tu/approx= (:iv_percentile_252d row) (* (/ 251.0 252.0) 100.0))))
        ;; hv_rank_252d = 100.0 (monotonic hv30, latest is max)
        (testing "hv_rank_252d = 100.0"
          (is (tu/approx= (:hv_rank_252d row) 100.0)))
        ;; iv_minus_hv = 49.9 - 24.95 = 24.95
        (testing "iv_minus_hv ≈ 24.95"
          (let [expected (- (+ 20.0 (* 299 0.1)) (+ 10.0 (* 299 0.05)))]
            (is (tu/approx= (:iv_minus_hv row) expected))))))))

(deftest partial-window-test
  (testing "symbol with 80 rows: only 63d window non-null, 252d and 126d are NULL"
    ;; 80 rows: cnt=80 >= 63 (63d fills) but 80 < 126 (126d null) and 80 < 252 (252d null)
    ;; Latest (i=79): iv30=27.9, hv30=13.95
    ;; 63d window (rn 1..63 = rows i=17..79):
    ;;   min_iv = 20 + 17*0.1 = 21.7, max_iv = 27.9 → iv_rank_63d = 100.0
    ;;   pct_iv = 62/63 * 100 ≈ 98.413
    ;; iv_rank_window_used = "63d"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-iv-daily! ds "BBBB" 80 20.0 0.1 10.0 0.05)
      (iv/refresh-iv-indicators! ds)
      (let [row (tu/query-row ds "BBBB")]
        (testing "row exists"
          (is (some? row)))
        (testing "252d window returns NULL (80 < 252)"
          (is (nil? (:iv_rank_252d      row)) "iv_rank_252d must be NULL")
          (is (nil? (:iv_percentile_252d row)) "iv_percentile_252d must be NULL")
          (is (nil? (:hv_rank_252d      row)) "hv_rank_252d must be NULL")
          (is (nil? (:hv_percentile_252d row)) "hv_percentile_252d must be NULL"))
        (testing "126d window returns NULL (80 < 126)"
          (is (nil? (:iv_rank_126d      row)) "iv_rank_126d must be NULL")
          (is (nil? (:iv_percentile_126d row)) "iv_percentile_126d must be NULL"))
        (testing "63d window is non-null (80 >= 63)"
          (is (some? (:iv_rank_63d      row)) "iv_rank_63d")
          (is (some? (:iv_percentile_63d row)) "iv_percentile_63d"))
        (testing "iv_rank_63d = 100.0 (monotonic, latest is max)"
          (is (tu/approx= (:iv_rank_63d row) 100.0)))
        (testing "iv_percentile_63d ≈ 62/63*100"
          (is (tu/approx= (:iv_percentile_63d row) (* (/ 62.0 63.0) 100.0))))
        (testing "iv_rank_window_used = '63d'"
          (is (= "63d" (:iv_rank_window_used row))))
        (testing "iv_minus_hv is non-null"
          (is (some? (:iv_minus_hv row))))
        (testing "iv_minus_hv ≈ 27.9 - 13.95 = 13.95"
          (let [expected (- (+ 20.0 (* 79 0.1)) (+ 10.0 (* 79 0.05)))]
            (is (tu/approx= (:iv_minus_hv row) expected))))))))

(deftest no-rows-test
  (testing "symbol with no iv_daily rows: not inserted into latest_indicators"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (iv/refresh-iv-indicators! ds)
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'CCCC'"]
                   tu/as-lower)]
        (is (empty? rows)
            "no rows should be inserted when iv_daily is empty")))))

(deftest idempotent-test
  (testing "calling refresh-iv-indicators! twice upserts, not duplicates"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-iv-daily! ds "AAAA" 300 20.0 0.1 10.0 0.05)
      (iv/refresh-iv-indicators! ds)
      (iv/refresh-iv-indicators! ds)
      (let [rows (jdbc/execute! ds
                   ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'AAAA'"]
                   tu/as-lower)]
        (is (= 1 (:n (first rows)))
            "upsert must not duplicate rows")))))

(deftest multi-symbol-test
  (testing "refresh-iv-indicators! processes all symbols in iv_daily"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-iv-daily! ds "SYM1" 300 20.0 0.1 10.0 0.05)
      (seed-iv-daily! ds "SYM2" 80  20.0 0.1 10.0 0.05)
      (iv/refresh-iv-indicators! ds)
      (let [syms (->> (jdbc/execute! ds
                        ["SELECT symbol FROM latest_indicators ORDER BY symbol"]
                        tu/as-lower)
                      (map :symbol)
                      set)]
        (is (contains? syms "SYM1") "SYM1 must appear in latest_indicators")
        (is (contains? syms "SYM2") "SYM2 must appear in latest_indicators")))))
