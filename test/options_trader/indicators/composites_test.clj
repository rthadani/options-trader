(ns options-trader.indicators.composites-test
  "Fixture-driven tests for the MACD, Parabolic SAR, and OBV composite indicator slice.
   Covers: schema creation (all 8 columns added idempotently), single-bar edge case
   (cross flags 'none' when no prior bar), insufficient-history (obv_trend 'none' < 50 bars),
   happy-path (60 bars: all columns non-NULL, flags are valid strings), OBV direction
   (rising volume on up-trending prices), and idempotency (one row per symbol after two calls)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.composites :as composites])
  (:import [java.io File]
           [java.sql Date]
           [java.time LocalDate]))

;;; ── Temp DB fixture ──────────────────────────────────────────────────────────

(defn- tempfile-cfg []
  (let [f (File/createTempFile "composites-test-" ".duckdb")]
    (.delete f)
    {:db {:path (.getAbsolutePath f)}}))

;;; ── Seed helpers ─────────────────────────────────────────────────────────────

(defn- seed-bar!
  "Insert a bars_daily row with explicit OHLCV values."
  [ds sym ^LocalDate bar-date open high low close volume]
  (jdbc/execute! ds
    ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
      VALUES (?, ?, ?, ?, ?, ?, ?)"
     sym (Date/valueOf bar-date)
     (double open) (double high) (double low) (double close) (long volume)]))

(defn- fetch-row [ds sym]
  (first (jdbc/execute! ds
           [(str "SELECT * FROM latest_indicators WHERE symbol = '" sym "'")]
           {:builder-fn rs/as-unqualified-lower-maps})))

;;; ── Schema creation ──────────────────────────────────────────────────────────

(deftest ensure-schema-test
  (testing "ensure-schema! adds all 8 composite columns, idempotent on second call"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (composites/ensure-schema! ds)
      (composites/ensure-schema! ds)
      (let [cols (set (map :column_name
                           (jdbc/execute! ds
                             ["SELECT column_name
                               FROM information_schema.columns
                               WHERE table_name = 'latest_indicators'"]
                             {:builder-fn rs/as-unqualified-lower-maps})))]
        (is (contains? cols "macd")            "macd column must exist")
        (is (contains? cols "macd_signal")     "macd_signal column must exist")
        (is (contains? cols "macd_hist")       "macd_hist column must exist")
        (is (contains? cols "macd_cross_flag") "macd_cross_flag column must exist")
        (is (contains? cols "psar")            "psar column must exist")
        (is (contains? cols "psar_flip_flag")  "psar_flip_flag column must exist")
        (is (contains? cols "obv")             "obv column must exist")
        (is (contains? cols "obv_trend")       "obv_trend column must exist")))))

;;; ── Single bar: cross flags must be 'none' ───────────────────────────────────

(deftest single-bar-flags-none-test
  (testing "single bar in bars_daily: no prior bar for flag comparison → cross flags 'none'"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-bar! ds "ONE" (LocalDate/of 2024 1 1) 100.0 105.0 95.0 100.0 10000)
      (composites/refresh-composites! ds)
      (let [row (fetch-row ds "ONE")]
        (is (some? row)                            "row must be inserted")
        (is (= "none" (:macd_cross_flag row))
            "1 MACD bar — no prev_m → macd_cross_flag must be 'none'")
        (is (= "none" (:psar_flip_flag row))
            "1 PSAR bar — no prev_p → psar_flip_flag must be 'none'")
        (is (= "none" (:obv_trend row))
            "1 bar < 50 required for sma50 → obv_trend must be 'none'")
        (is (some? (:macd row))   "macd is computed even for 1 bar")
        (is (some? (:psar row))   "psar is computed even for 1 bar")
        (is (some? (:obv row))    "obv is running sum, present for 1 bar")))))

;;; ── Insufficient history: obv_trend 'none' when < 50 bars ───────────────────

(deftest insufficient-history-obv-trend-test
  (testing "30 bars: obv_trend 'none' (insufficient for SMA50), macd/psar/obv non-NULL"
    (let [cfg  (tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 30)]
        (seed-bar! ds "SHORT" (.plusDays base i)
                   100.0 102.0 98.0 (+ 100.0 (* i 0.1)) 1000))
      (composites/refresh-composites! ds)
      (let [row (fetch-row ds "SHORT")]
        (is (some? row)                         "row must exist")
        (is (= "none" (:obv_trend row))
            "30 bars < 50 needed for SMA50 → obv_trend must be 'none'")
        (is (some? (:macd row))                 "macd must be non-NULL with 30 bars")
        (is (some? (:macd_signal row))          "macd_signal must be non-NULL with 30 bars")
        (is (some? (:macd_hist row))            "macd_hist must be non-NULL with 30 bars")
        (is (some? (:psar row))                 "psar must be non-NULL with 30 bars")
        (is (some? (:obv row))                  "obv must be non-NULL with 30 bars")
        (is (contains? #{"bullish" "bearish" "none"} (:macd_cross_flag row)))
        (is (contains? #{"bullish" "bearish" "none"} (:psar_flip_flag row)))))))

;;; ── Happy path: 60 bars → all columns non-NULL, flags valid strings ──────────

(deftest happy-path-60-bars-test
  (testing "60 bars with sinusoidal close: all 8 columns populated, flags are valid strings"
    (let [cfg  (tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 60)]
        (let [close (+ 100.0 (* (Math/sin (* i 0.3)) 10.0))]
          (seed-bar! ds "HAPPY" (.plusDays base i)
                     close (+ close 1.5) (- close 1.5) close 5000)))
      (composites/refresh-composites! ds)
      (let [row (fetch-row ds "HAPPY")]
        (is (some? row)                 "row must exist")
        (is (some? (:macd row))         "macd must be non-NULL with 60 bars")
        (is (some? (:macd_signal row))  "macd_signal must be non-NULL with 60 bars")
        (is (some? (:macd_hist row))    "macd_hist must be non-NULL with 60 bars")
        (is (contains? #{"bullish" "bearish" "none"} (:macd_cross_flag row))
            "macd_cross_flag must be a valid string")
        (is (some? (:psar row))         "psar must be non-NULL with 60 bars")
        (is (contains? #{"bullish" "bearish" "none"} (:psar_flip_flag row))
            "psar_flip_flag must be a valid string")
        (is (some? (:obv row))          "obv must be non-NULL with 60 bars")
        (is (contains? #{"rising" "falling" "flat" "none"} (:obv_trend row))
            "obv_trend must be a valid string")))))

;;; ── OBV direction: consistently rising volume on up-trending prices ──────────

(deftest obv-trend-rising-test
  (testing "60 bars with steadily rising prices and growing volume → obv is positive"
    (let [cfg  (tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 60)]
        (seed-bar! ds "OBVR" (.plusDays base i)
                   (+ 100.0 i) (+ 101.5 i) (+ 98.5 i)
                   (+ 100.0 i) (* 1000 (inc i))))
      (composites/refresh-composites! ds)
      (let [row (fetch-row ds "OBVR")]
        (is (some? row)             "row must exist")
        (is (some? (:obv row))      "obv must be computed")
        (is (pos? (:obv row))       "OBV must be positive when all days are up-days")
        (is (contains? #{"rising" "falling" "flat" "none"} (:obv_trend row))
            "obv_trend must be a valid string value")))))

;;; ── MACD histogram sign matches EMA gap ──────────────────────────────────────

(deftest macd-hist-sign-test
  (testing "macd_hist = macd - macd_signal; sign is consistent"
    (let [cfg  (tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 60)]
        (seed-bar! ds "MHIST" (.plusDays base i)
                   100.0 102.0 98.0 100.0 1000))
      (composites/refresh-composites! ds)
      (let [row (fetch-row ds "MHIST")]
        (is (some? row)               "row must exist")
        (is (some? (:macd row))       "macd must be non-NULL")
        (is (some? (:macd_signal row)) "macd_signal must be non-NULL")
        (is (some? (:macd_hist row))  "macd_hist must be non-NULL")
        (is (< (Math/abs (- (:macd_hist row)
                            (- (:macd row) (:macd_signal row))))
               1e-9)
            "macd_hist must equal macd - macd_signal within floating-point precision")))))

;;; ── Idempotency ──────────────────────────────────────────────────────────────

(deftest idempotency-test
  (testing "two successive refresh-composites! calls produce exactly one row per symbol"
    (let [cfg  (tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          base (LocalDate/of 2024 1 1)]
      (doseq [i (range 60)]
        (seed-bar! ds "IDEM" (.plusDays base i)
                   100.0 102.0 98.0 100.0 1000))
      (composites/refresh-composites! ds)
      (composites/refresh-composites! ds)
      (let [cnt (-> (jdbc/execute! ds
                      ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                      {:builder-fn rs/as-unqualified-lower-maps})
                    first :n)]
        (is (= 1 cnt) "upsert must not duplicate rows on repeated calls"))
      (let [row (fetch-row ds "IDEM")]
        (is (some? (:macd row))  "macd persists after second refresh call")
        (is (some? (:psar row))  "psar persists after second refresh call")
        (is (some? (:obv row))   "obv persists after second refresh call")))))
