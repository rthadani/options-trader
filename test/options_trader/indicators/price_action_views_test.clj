(ns options-trader.indicators.price-action-views-test
  "Fixture-driven tests for price-action and volume indicators.
   Covers: happy path (250 bars, hand-computed SMA and ratios), partial history
   (60 bars: sma_50 populated / sma_200 NULL), sparse history (30 bars: both NULL),
   idempotency (two calls produce one row per symbol), and zero-close edge
   (NULLIF prevents divide-by-zero when SMA collapses to zero)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.price-action-views :as pav]
            [options-trader.test-util :as tu])
  (:import [java.sql Date]
           [java.time LocalDate]))

(defn- seed-bar!
  "Insert a bars_daily row. high=close+2, low=close-2, open=close."
  [ds sym ^LocalDate bar-date close volume]
  (jdbc/execute! ds
    ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
      VALUES (?, ?, ?, ?, ?, ?, ?)"
     sym (Date/valueOf bar-date)
     (double close) (+ (double close) 2.0) (- (double close) 2.0)
     (double close) (long volume)]))

;; Tighter than tu/approx='s 1e-3 default — the SMA / volume-ratio
;; expected values are exact rationals.
(defn- approx= [a b]
  (tu/approx= a b 1e-4))

;;; ── Happy path: 250 bars ─────────────────────────────────────────────────────

(deftest happy-path-test
  (testing "250 bars: all columns populated with hand-computed values"
    ;; 249 bars with close=100, volume=1000 followed by one bar close=110, volume=2000.
    ;; sma_50  = (49*100 + 110) / 50  = 5010/50   = 100.2
    ;; sma_200 = (199*100 + 110) / 200 = 20010/200 = 100.05
    ;; close_vs_sma_50         = 110 / 100.2    ≈ 1.09780
    ;; distance_to_sma_50_pct  = (110-100.2)/100.2 ≈ 0.09780
    ;; close_vs_sma_200        = 110 / 100.05   ≈ 1.09945
    ;; distance_to_sma_200_pct = (110-100.05)/100.05 ≈ 0.09945
    ;; avg_vol_5d  = (4*1000 + 2000) / 5  = 1200
    ;; avg_vol_20d = (19*1000 + 2000) / 20 = 1050
    ;; volume_ratio_5d_vs_20d  = 1200 / 1050 ≈ 1.14286
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 249)]
        (seed-bar! ds "HP250" (.minusDays base (- 249 i)) 100.0 1000))
      (seed-bar! ds "HP250" base 110.0 2000)
      (pav/refresh-price-action-views! ds)
      (let [row (tu/query-row ds "HP250")]
        (testing "row exists"
          (is (some? row)))
        (testing "close_vs_sma_50 ≈ 110/100.2"
          (is (some? (:close_vs_sma_50 row)))
          (is (approx= (:close_vs_sma_50 row) (/ 110.0 100.2))))
        (testing "close_vs_sma_200 ≈ 110/100.05"
          (is (some? (:close_vs_sma_200 row)))
          (is (approx= (:close_vs_sma_200 row) (/ 110.0 100.05))))
        (testing "distance_to_sma_50_pct ≈ (110-100.2)/100.2"
          (is (some? (:distance_to_sma_50_pct row)))
          (is (approx= (:distance_to_sma_50_pct row) (/ (- 110.0 100.2) 100.2))))
        (testing "distance_to_sma_200_pct ≈ (110-100.05)/100.05"
          (is (some? (:distance_to_sma_200_pct row)))
          (is (approx= (:distance_to_sma_200_pct row) (/ (- 110.0 100.05) 100.05))))
        (testing "volume_ratio_5d_vs_20d ≈ 1200/1050"
          (is (some? (:volume_ratio_5d_vs_20d row)))
          (is (approx= (:volume_ratio_5d_vs_20d row) (/ 1200.0 1050.0))))))))

;;; ── 60 bars: sma_50 populated, sma_200 NULL ──────────────────────────────────

(deftest sixty-bars-test
  (testing "60 bars: sma_50 columns non-NULL, sma_200 columns NULL, volume_ratio non-NULL"
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 60)]
        (seed-bar! ds "BAR60" (.minusDays base (- 59 i)) 100.0 1000))
      (pav/refresh-price-action-views! ds)
      (let [row (tu/query-row ds "BAR60")]
        (testing "row exists"
          (is (some? row)))
        (testing "close_vs_sma_50 non-NULL (60 >= 50 bars)"
          (is (some? (:close_vs_sma_50 row))))
        (testing "distance_to_sma_50_pct non-NULL (60 >= 50 bars)"
          (is (some? (:distance_to_sma_50_pct row))))
        (testing "close_vs_sma_200 is NULL (60 < 200 bars)"
          (is (nil? (:close_vs_sma_200 row))))
        (testing "distance_to_sma_200_pct is NULL (60 < 200 bars)"
          (is (nil? (:distance_to_sma_200_pct row))))
        (testing "volume_ratio_5d_vs_20d non-NULL (60 >= 20 bars)"
          (is (some? (:volume_ratio_5d_vs_20d row))))))))

;;; ── 30 bars: both sma columns NULL ──────────────────────────────────────────

(deftest thirty-bars-test
  (testing "30 bars: sma_50 and sma_200 columns NULL, volume_ratio non-NULL"
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 30)]
        (seed-bar! ds "BAR30" (.minusDays base (- 29 i)) 100.0 1000))
      (pav/refresh-price-action-views! ds)
      (let [row (tu/query-row ds "BAR30")]
        (testing "row exists"
          (is (some? row)))
        (testing "close_vs_sma_50 is NULL (30 < 50 bars)"
          (is (nil? (:close_vs_sma_50 row))))
        (testing "close_vs_sma_200 is NULL (30 < 200 bars)"
          (is (nil? (:close_vs_sma_200 row))))
        (testing "distance_to_sma_50_pct is NULL (30 < 50 bars)"
          (is (nil? (:distance_to_sma_50_pct row))))
        (testing "distance_to_sma_200_pct is NULL (30 < 200 bars)"
          (is (nil? (:distance_to_sma_200_pct row))))
        (testing "volume_ratio_5d_vs_20d non-NULL (30 >= 20 bars)"
          (is (some? (:volume_ratio_5d_vs_20d row))))))))

;;; ── Idempotency ──────────────────────────────────────────────────────────────

(deftest idempotency-test
  (testing "two refresh! calls with same data produce exactly one row per symbol"
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 250)]
        (seed-bar! ds "IDEM" (.minusDays base (- 249 i)) 100.0 1000))
      (pav/refresh-price-action-views! ds)
      (pav/refresh-price-action-views! ds)
      (let [cnt (-> (jdbc/execute! ds
                      ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                      tu/as-lower)
                    first :n)]
        (is (= 1 cnt) "upsert must not duplicate rows"))
      (testing "close_vs_sma_50 persists after second call"
        (is (some? (:close_vs_sma_50 (tu/query-row ds "IDEM")))))
      (testing "volume_ratio_5d_vs_20d persists after second call"
        (is (some? (:volume_ratio_5d_vs_20d (tu/query-row ds "IDEM"))))))))

;;; ── Zero close: NULLIF guards divide-by-zero ─────────────────────────────────

(deftest zero-close-test
  (testing "250 bars all close=0: NULLIF prevents divide-by-zero, sma columns NULL"
    ;; When all closes are zero, sma_50_raw=0 and sma_200_raw=0.
    ;; NULLIF(0.0, 0.0) = NULL, so close/NULL = NULL and (close-NULL)/NULL = NULL.
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 250)]
        (seed-bar! ds "ZERO" (.minusDays base (- 249 i)) 0.0 1000))
      (pav/refresh-price-action-views! ds)
      (let [row (tu/query-row ds "ZERO")]
        (testing "row exists"
          (is (some? row)))
        (testing "close_vs_sma_50 is NULL when sma_50=0"
          (is (nil? (:close_vs_sma_50 row))))
        (testing "close_vs_sma_200 is NULL when sma_200=0"
          (is (nil? (:close_vs_sma_200 row))))
        (testing "distance_to_sma_50_pct is NULL when sma_50=0"
          (is (nil? (:distance_to_sma_50_pct row))))
        (testing "distance_to_sma_200_pct is NULL when sma_200=0"
          (is (nil? (:distance_to_sma_200_pct row))))))))
