(ns options-trader.indicators.engine-test
  "Integration test: seeds bars_daily in a temp DuckDB, runs compute-many,
   and asserts latest_indicators has the expected indicator values."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.engine :as engine]
            [options-trader.indicators.ta4j :as ta4j]
            [options-trader.test-util :as tu])
  (:import [java.sql Date]))

;;; ── Tests ────────────────────────────────────────────────────────────────────

(deftest compute-many-upserts-latest-indicators-test
  (testing "compute-many populates latest_indicators with correct values"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (tu/seed-bars! ds "TEST" tu/raw-bars)
      (engine/compute-many ds "TEST")
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'TEST'"]
                   tu/as-lower)
            row  (first rows)]
        (testing "exactly one row"
          (is (= 1 (count rows))))
        (testing "RSI[14] = 100.0000"
          (is (= (tu/round4 (:rsi_14 row)) 100.0)
              (str "rsi_14 expected 100.0, got " (:rsi_14 row))))
        (testing "ATR[14] = 3.0000"
          (is (= (tu/round4 (:atr_14 row)) 3.0)
              (str "atr_14 expected 3.0, got " (:atr_14 row))))
        (testing "BollingerBandWidth[20,2.0] = 16.5341"
          (is (= (tu/round4 (:bb_width row)) 16.5341)
              (str "bb_width expected 16.5341, got " (:bb_width row))))))))

(deftest compute-many-idempotent-test
  (testing "calling compute-many twice overwrites the row (not duplicates)"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (tu/seed-bars! ds "TEST" tu/raw-bars)
      (engine/compute-many ds "TEST")
      (engine/compute-many ds "TEST")
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'TEST'"]
                   tu/as-lower)]
        (is (= 1 (count rows))
            "upsert must not duplicate rows")))))

(deftest percentile-rank-test
  (testing "atr_14_percentile_126d column exists and is in [0.0, 1.0] after compute-many"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; Seed indicator_history with extra ATR values so PERCENT_RANK is well-defined.
      ;; ATR for the fixture is 3.0; surrounding values bracket it for a non-trivial rank.
      (doseq [[d v] [["2023-01-01" 2.0]
                     ["2023-01-02" 3.0]
                     ["2023-01-03" 4.0]]]
        (jdbc/execute! ds
          ["INSERT INTO indicator_history (symbol, indicator, ind_date, value) VALUES ('TEST', 'atr_14', ?, ?)"
           (Date/valueOf ^String d) v]))
      (tu/seed-bars! ds "TEST" tu/raw-bars)
      (engine/compute-many ds "TEST")
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM latest_indicators WHERE symbol = 'TEST'"]
                   tu/as-lower)
            row  (first rows)
            pct  (:atr_14_percentile_126d row)]
        (testing "percentile column exists"
          (is (some? pct)
              "atr_14_percentile_126d should be populated"))
        (testing "percentile rank is in [0.0, 1.0]"
          (is (<= 0.0 pct 1.0)
              (str "atr_14_percentile_126d expected in [0,1], got " pct)))))))

;;; ── Composite unit tests ─────────────────────────────────────────────────────

(deftest ttm-squeeze-flag-unit-test
  (testing "ttm_squeeze_flag=true when BB sits inside KC"
    (let [spec   {:column :ttm_squeeze_flag :kind :ttm-squeeze
                  :requires [:bb_upper :bb_lower :kc_upper :kc_lower]}
          ;; BB [105, 95] is fully inside KC [110, 90]
          values {:bb_upper 105.0 :bb_lower 95.0 :kc_upper 110.0 :kc_lower 90.0}
          result (engine/compute-composite spec values {} nil nil)]
      (is (true? result) "BB inside KC should produce squeeze=true")))

  (testing "ttm_squeeze_flag=false when BB extends outside KC"
    (let [spec   {:column :ttm_squeeze_flag :kind :ttm-squeeze
                  :requires [:bb_upper :bb_lower :kc_upper :kc_lower]}
          ;; BB [115, 85] extends beyond KC [110, 90]
          values {:bb_upper 115.0 :bb_lower 85.0 :kc_upper 110.0 :kc_lower 90.0}
          result (engine/compute-composite spec values {} nil nil)]
      (is (false? result) "BB outside KC should produce squeeze=false"))))

(deftest composite-columns-integration-test
  (testing "compute-many populates all five composite columns"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (tu/seed-bars! ds "TEST" tu/raw-bars)
      (engine/compute-many ds "TEST")
      (let [rows (jdbc/execute! ds
                   ["SELECT ttm_squeeze_flag, macd_cross_flag, mass_reversal_flag,
                            psar_flip_flag, obv_trend
                     FROM latest_indicators WHERE symbol = 'TEST'"]
                   tu/as-lower)
            row  (first rows)]
        (testing "all five composite columns are present and non-nil"
          (is (some? (:ttm_squeeze_flag row))   "ttm_squeeze_flag must be populated")
          (is (some? (:macd_cross_flag row))    "macd_cross_flag must be populated")
          (is (some? (:mass_reversal_flag row)) "mass_reversal_flag must be populated")
          (is (some? (:psar_flip_flag row))     "psar_flip_flag must be populated")
          (is (some? (:obv_trend row))          "obv_trend must be populated"))

        (testing "obv_trend='rising' on steady uptrend (SMA20 > SMA50)"
          ;; On ohlcv-50: OBV[i]=i*1M, SMA20=39.5M, SMA50=24.5M → rising
          (is (= "rising" (:obv_trend row))
              (str "obv_trend expected 'rising', got " (:obv_trend row))))

        (testing "ttm_squeeze_flag=false on volatile uptrend (BB wider than KC)"
          ;; BB stddev≈5.77 → half-width 11.5; KC atr≈3.0 → half-width 6.0
          ;; BB [127.97, 151.03] extends beyond KC [133.5, 145.5]
          (is (false? (:ttm_squeeze_flag row))
              (str "ttm_squeeze_flag expected false on uptrend, got " (:ttm_squeeze_flag row))))

        (testing "mass_reversal_flag=false on steady uptrend (mass_index≈25)"
          ;; Constant H-L=3 → EMA9(H-L)/EMA9(EMA9(H-L))=1 → sum over 25 bars ≈ 25 < 27
          (is (false? (:mass_reversal_flag row))
              (str "mass_reversal_flag expected false, got " (:mass_reversal_flag row))))

        (testing "macd_cross_flag='none' on consistent uptrend"
          ;; Both MACD and signal are stable positive values in a linear uptrend
          (is (= "none" (:macd_cross_flag row))
              (str "macd_cross_flag expected 'none', got " (:macd_cross_flag row))))

        (testing "psar_flip_flag='none' on consistent uptrend (PSAR always below price)"
          (is (= "none" (:psar_flip_flag row))
              (str "psar_flip_flag expected 'none', got " (:psar_flip_flag row))))))))

(deftest obv-trend-falling-unit-test
  (testing "obv_trend='falling' on a steady downtrend (SMA20 < SMA50)"
    ;; Declining bars: close goes from 149 down to 100 → OBV accumulates negatively
    (let [declining-bars
          (mapv (fn [i]
                  {:time   (* (long i) 86400000)
                   :open   (- 149.0 i)
                   :high   (+ 150.5 (- i))
                   :low    (- 147.5 i)
                   :close  (- 149.0 i)
                   :volume 1000000})
                (range 50))
          series   (ta4j/ds->ta4j-ohlcv declining-bars)
          n        (ta4j/bar-count series)
          obv-ind  (ta4j/obv series)
          spec     {:column :obv_trend :kind :obv-trend :requires [:obv]}
          ind-objs {:obv obv-ind}
          result   (engine/compute-composite spec {} ind-objs series declining-bars)]
      ;; OBV[i] = -i*1M, SMA20 = -39.5M, SMA50 = -24.5M → SMA20 < SMA50 → falling
      (is (= "falling" result)
          (str "obv_trend expected 'falling' on downtrend, got " result)))))

(deftest macd-cross-flag-unit-test
  (testing "macd_cross_flag returns a valid string and is consistent with indicator values"
    ;; 40 declining bars then 10 rising bars — MACD transitions from negative territory
    (let [declining (mapv (fn [i]
                            {:time  (* (long i) 86400000)
                             :open  (- 100.0 i) :high (+ 101.5 (- i))
                             :low   (- 98.5 i)  :close (- 100.0 i) :volume 1000000})
                          (range 40))
          rising    (mapv (fn [i]
                            {:time  (* (long (+ 40 i)) 86400000)
                             :open  (+ 61.0 (* 3 i)) :high (+ 62.5 (* 3 i))
                             :low   (+ 59.5 (* 3 i)) :close (+ 61.0 (* 3 i)) :volume 1000000})
                          (range 10))
          bars      (vec (concat declining rising))
          series    (ta4j/ds->ta4j-ohlcv bars)
          n         (ta4j/bar-count series)
          macd-ind  (ta4j/macd series 12 26)
          sig-ind   (ta4j/macd-signal series 12 26 9)
          spec      {:column :macd_cross_flag :kind :macd-cross :requires [:macd :macd_signal]}
          ind-objs  {:macd macd-ind :macd_signal sig-ind}
          result    (engine/compute-composite spec {} ind-objs series bars)]
      (is (contains? #{"bullish" "bearish" "none"} result)
          (str "macd_cross_flag must be bullish/bearish/none, got " result))
      ;; Verify result matches the actual indicator values at last two bars
      (let [prev-diff (- (ta4j/indicator-value macd-ind (- n 2))
                         (ta4j/indicator-value sig-ind  (- n 2)))
            curr-diff (- (ta4j/indicator-value macd-ind (dec n))
                         (ta4j/indicator-value sig-ind  (dec n)))
            expected  (cond
                        (and (neg? prev-diff) (>= curr-diff 0.0)) "bullish"
                        (and (pos? prev-diff) (<= curr-diff 0.0)) "bearish"
                        :else "none")]
        (is (= expected result)
            (str "result inconsistent with prev-diff=" prev-diff " curr-diff=" curr-diff))))))

(deftest psar-flip-flag-unit-test
  (testing "psar_flip_flag='none' on steady uptrend (PSAR always below price)"
    (let [bars    (mapv (fn [b]
                          {:time   (long (* (.getTime (Date/valueOf ^String (:bar_date b))) 1))
                           :open   (:open b) :high (:high b)
                           :low    (:low b)  :close (:close b) :volume (:volume b)})
                        tu/raw-bars)
          series  (ta4j/ds->ta4j-ohlcv bars)
          psar-ind (ta4j/parabolic-sar series 0.02 0.02 0.2)
          spec    {:column :psar_flip_flag :kind :psar-flip :requires [:psar :close]}
          ind-objs {:psar psar-ind}
          result  (engine/compute-composite spec {} ind-objs series bars)]
      (is (= "none" result)
          (str "psar_flip_flag expected 'none' on uptrend, got " result)))))
