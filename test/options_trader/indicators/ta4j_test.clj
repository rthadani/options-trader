(ns options-trader.indicators.ta4j-test
  "Fixture-driven unit tests for ta4j indicator values.
   Series: linear trend close[i]=100+i, high[i]=101.5+i, low[i]=98.5+i (50 bars).
   Expected (hand-computed, locked to 4 decimals):
     RSI[14]  = 100.0000  (pure uptrend → no losses → RSI=100)
     ATR[14]  =   3.0000  (TR constant = H-L = 3.0 every bar)
     BBW[20,2]= 16.5341  (4*sqrt(33.25)/139.5 * 100 = 16.5341... per ta4j 0.16)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [options-trader.indicators.ta4j :as ta4j])
  (:import [java.time LocalDate ZoneOffset]))

;;; ── Fixture loading ──────────────────────────────────────────────────────────

(defn- parse-date-ms [s]
  (-> (LocalDate/parse s)
      (.atStartOfDay ZoneOffset/UTC)
      .toInstant
      .toEpochMilli))

(defn- fixture->ohlcv [bars]
  (mapv (fn [b]
          {:time   (parse-date-ms (:bar_date b))
           :open   (double (:open b))
           :high   (double (:high b))
           :low    (double (:low b))
           :close  (double (:close b))
           :volume (long (:volume b))})
        bars))

(def ^:private raw-fixture
  (edn/read-string (slurp (io/resource "fixtures/ohlcv-50.edn"))))

(def ^:private test-series
  (ta4j/ds->ta4j-ohlcv (fixture->ohlcv raw-fixture)))

;;; ── Rounding helper ──────────────────────────────────────────────────────────

(defn- round4 [x]
  (Double/parseDouble (format "%.4f" x)))

;;; ── RSI[14] ──────────────────────────────────────────────────────────────────

(deftest rsi-14-last-value-test
  (testing "RSI[14] on 50-bar pure uptrend equals 100.0000"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/rsi test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 100.0)
          (str "RSI[14] expected 100.0000, got " v)))))

;;; ── ATR[14] ──────────────────────────────────────────────────────────────────

(deftest atr-14-last-value-test
  (testing "ATR[14] on constant-TR=3.0 series equals 3.0000"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/atr test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 3.0)
          (str "ATR[14] expected 3.0000, got " v)))))

;;; ── BollingerBandWidth[20, 2.0] ──────────────────────────────────────────────

(deftest bb-width-20-2-last-value-test
  (testing "BollingerBandWidth[20,2.0] last value matches 4*sqrt(33.25)/139.5*100"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/bollinger-band-width test-series 20 2.0)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 16.5341)
          (str "BollingerBandWidth[20,2.0] expected 16.5341, got " v)))))

;;; ── SMA[50] ──────────────────────────────────────────────────────────────────

(deftest sma-50-last-value-test
  (testing "SMA[50] on 50-bar linear close[i]=100+i: mean of 100..149 = 124.5"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/sma test-series 50)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 124.5)
          (str "SMA[50] expected 124.5, got " v)))))

;;; ── SMA[200] ─────────────────────────────────────────────────────────────────

(deftest sma-200-last-value-test
  (testing "SMA[200] on 50 bars: ta4j uses available bars, same result as SMA[50] = 124.5"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/sma test-series 200)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 124.5)
          (str "SMA[200] on 50 bars expected 124.5, got " v)))))

;;; ── EMA[50] ──────────────────────────────────────────────────────────────────

(deftest ema-50-last-value-test
  (testing "EMA[50] on uptrend: weights recent (higher) prices, so EMA > SMA[50]=124.5"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/ema test-series 50)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v))
          "EMA[50] should not be NaN")
      (is (> v 124.5)
          (str "EMA[50] on uptrend should exceed SMA=124.5, got " v))
      (is (< v 150.0)
          (str "EMA[50] should be below last close=149, got " v)))))

;;; ── ADX[14] ──────────────────────────────────────────────────────────────────
;; Pure uptrend: +DM=1 each bar, -DM=0, TR=3, DX=100 always.
;; ADX Wilder-smooths DX over 14 bars; 50 bars is not enough to reach 100.0
;; but a pure uptrend must produce ADX in (90, 100].

(deftest adx-14-last-value-test
  (testing "ADX[14] on pure linear uptrend is in (90, 100]"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/adx test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (> v 90.0)
          (str "ADX[14] on pure uptrend should be > 90, got " v))
      (is (<= v 100.0)
          (str "ADX[14] should not exceed 100, got " v)))))

;;; ── StochasticOscillatorK[14] ────────────────────────────────────────────────
;; close[49]=149, LowestLow[14]=low[36]=134.5, HighestHigh[14]=high[49]=150.5
;; %K = (149-134.5)/(150.5-134.5)*100 = 14.5/16.0*100 = 90.625

(deftest stoch-k-14-last-value-test
  (testing "StochasticOscillatorK[14] last value = 90.625"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/stoch-k test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 90.625)
          (str "StochK[14] expected 90.625, got " v)))))

;;; ── WilliamsR[14] ────────────────────────────────────────────────────────────
;; WilliamsR = (HighestHigh - Close) / Range * (-100)
;; = (150.5 - 149) / 16.0 * (-100) = 1.5/16.0*(-100) = -9.375

(deftest williams-r-14-last-value-test
  (testing "WilliamsR[14] last value = -9.375"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/williams-r test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) -9.375)
          (str "WilliamsR[14] expected -9.375, got " v)))))

;;; ── CCI[20] ──────────────────────────────────────────────────────────────────
;; TP[i]=100+i, window 30..49, mean=139.5, MAD=5.0
;; CCI = (149-139.5)/(0.015*5.0) = 9.5/0.075 = 126.6667

(deftest cci-20-last-value-test
  (testing "CCI[20] last value = 126.6667"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/cci test-series 20)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 126.6667)
          (str "CCI[20] expected 126.6667, got " v)))))

;;; ── MACD[12,26] ──────────────────────────────────────────────────────────────
;; On a pure uptrend, EMA[12] leads EMA[26]: MACD > 0.

(deftest macd-12-26-test
  (testing "MACD[12,26] on pure uptrend is positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/macd test-series 12 26)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 5.0)  (str "MACD[12,26] expected > 5.0, got " v))
      (is (< v 12.0) (str "MACD[12,26] expected < 12.0, got " v)))))

;;; ── MACD signal and histogram ────────────────────────────────────────────────

(deftest macd-signal-test
  (testing "MACD signal EMA[9] over MACD is positive on uptrend"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/macd-signal test-series 12 26 9)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 0.0) (str "MACDSignal expected > 0, got " v)))))

(deftest macd-hist-test
  (testing "MACD histogram is finite and near zero relative to MACD"
    (let [n    (ta4j/bar-count test-series)
          ind  (ta4j/macd-hist test-series 12 26 9)
          v    (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (< (Math/abs v) 3.0) (str "MACDHist expected |hist| < 3, got " v)))))

;;; ── StandardDeviation[20] ────────────────────────────────────────────────────
;; Window 30..49 (close 130..149), mean=139.5, population variance=33.25
;; StdDev = sqrt(33.25) ≈ 5.7663  (consistent with BBW test: 4*σ/139.5*100=16.5341)

(deftest stddev-20-test
  (testing "StdDev[20] on bars 30..49 = sqrt(33.25) ≈ 5.7663"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/stddev test-series 20)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 5.7663)
          (str "StdDev[20] expected 5.7663, got " v)))))

;;; ── UlcerIndex[14] ───────────────────────────────────────────────────────────
;; Pure uptrend: each close is the rolling max, so DrawDown% = 0 for every bar.
;; UlcerIndex = sqrt(sum(0)/14) = 0.0

(deftest ulcer-index-14-test
  (testing "UlcerIndex[14] on pure uptrend = 0.0 (no drawdown)"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/ulcer-index test-series 14)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 0.0)
          (str "UlcerIndex[14] expected 0.0, got " v)))))

;;; ── MassIndex[9,25] ──────────────────────────────────────────────────────────
;; H-L = 3.0 constant every bar.
;; EMA[9](3.0) = 3.0 (constant series seeds at SMA=3.0 and never changes).
;; Double-EMA = EMA[9](3.0) = 3.0.  Ratio = 3.0/3.0 = 1.0 each bar.
;; MassIndex = sum(1.0, 25 bars) = 25.0

(deftest mass-index-9-25-test
  (testing "MassIndex[9,25] with constant H-L=3.0 equals 25.0"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/mass-index test-series 9 25)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 25.0)
          (str "MassIndex[9,25] expected 25.0, got " v)))))

;;; ── ChaikinMoneyFlow[20] ─────────────────────────────────────────────────────
;; MFM = (2*close - high - low)/(high - low)
;; close=c, high=c+1.5, low=c-1.5 → MFM = (2c-(c+1.5)-(c-1.5))/3 = 0/3 = 0
;; CMF = sum(MFM*vol)/sum(vol) = 0

(deftest cmf-20-test
  (testing "CMF[20] with close at midpoint of H/L = 0.0"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/cmf test-series 20)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 0.0)
          (str "CMF[20] expected 0.0, got " v)))))

;;; ── BollingerBandsUpper/Lower[20,2.0] ───────────────────────────────────────
;; SMA[20]=139.5, StdDev=sqrt(33.25)≈5.7663
;; BBUpper = 139.5 + 2*5.7663 = 151.0326
;; BBLower = 139.5 - 2*5.7663 = 127.9674

(deftest bb-upper-20-2-test
  (testing "BollingerBandsUpper[20,2.0] = SMA + 2*StdDev ≈ 151.0326"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/bollinger-upper test-series 20 2.0)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 151.0326)
          (str "BBUpper expected 151.0326, got " v)))))

(deftest bb-lower-20-2-test
  (testing "BollingerBandsLower[20,2.0] = SMA - 2*StdDev ≈ 127.9674"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/bollinger-lower test-series 20 2.0)
          v   (ta4j/indicator-value ind (dec n))]
      (is (= (round4 v) 127.9674)
          (str "BBLower expected 127.9674, got " v)))))

;;; ── PercentB[20,2.0] ─────────────────────────────────────────────────────────
;; %B = (close - BBLower)/(BBUpper - BBLower)
;; close=149 is near the upper band, so %B is between 0.9 and 1.0

(deftest percent-b-20-2-test
  (testing "PercentB[20,2.0] with close near upper band is between 0.9 and 1.0"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/percent-b test-series 20 2.0)
          v   (ta4j/indicator-value ind (dec n))]
      (is (> v 0.9) (str "PercentB expected > 0.9, got " v))
      (is (< v 1.0) (str "PercentB expected < 1.0, got " v)))))

;;; ── OnBalanceVolume ──────────────────────────────────────────────────────────
;; Pure uptrend: every bar adds volume. With 50 bars at 1M each, OBV > 0.

(deftest obv-test
  (testing "OBV on pure uptrend is positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/obv test-series)
          v   (ta4j/indicator-value ind (dec n))]
      (is (> v 0) (str "OBV expected > 0, got " v)))))

;;; ── ParabolicSAR[0.02,0.02,0.2] ─────────────────────────────────────────────
;; On a pure uptrend the SAR trails below price and is positive.

(deftest psar-test
  (testing "ParabolicSAR on pure uptrend is positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/parabolic-sar test-series 0.02 0.02 0.2)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 0.0) (str "PSAR expected > 0, got " v)))))

;;; ── KeltnerChannelUpper/Lower[20,2.0,10] ────────────────────────────────────
;; KCMiddle = EMA[20], ATR[10]=3.0.  Values are in a reasonable positive range.

(deftest kc-upper-test
  (testing "KeltnerChannelUpper[20,2.0,10] is finite and positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/keltner-upper test-series 20 2.0 10)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 100.0) (str "KC Upper expected > 100, got " v))
      (is (< v 160.0) (str "KC Upper expected < 160, got " v)))))

(deftest kc-lower-test
  (testing "KeltnerChannelLower[20,2.0,10] is finite and positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/keltner-lower test-series 20 2.0 10)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 100.0) (str "KC Lower expected > 100, got " v))
      (is (< v 160.0) (str "KC Lower expected < 160, got " v)))))

;;; ── FisherTransform[10] ──────────────────────────────────────────────────────
;; On a pure uptrend the normalized price approaches 1.0 and Fisher is positive.

(deftest fisher-10-test
  (testing "FisherTransform[10] on pure uptrend is positive"
    (let [n   (ta4j/bar-count test-series)
          ind (ta4j/fisher test-series 10)
          v   (ta4j/indicator-value ind (dec n))]
      (is (not (Double/isNaN v)))
      (is (> v 0.0) (str "Fisher[10] expected positive on uptrend, got " v)))))
