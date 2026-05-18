(ns options-trader.indicators.option-chain-agg-test
  "Fixture-driven tests for option-chain aggregation into latest_indicators.
   Covers ATM resolution, ±1 strike slicing, NULL propagation, and the
   no-chain-rows case (all seven columns NULL)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.option-chain-agg :as oca])
  (:import [java.io File]
           [java.sql Date Timestamp]))

;;; ── Temp DB fixture ──────────────────────────────────────────────────────────

(defn- tempfile-cfg []
  (let [f (File/createTempFile "oca-test-" ".duckdb")]
    (.delete f)
    {:db {:path (.getAbsolutePath f)}}))

;;; ── Seed helpers ─────────────────────────────────────────────────────────────

(defn- seed-bar! [ds sym close]
  (jdbc/execute! ds
    ["INSERT INTO bars_daily (symbol, bar_date, close) VALUES (?, ?, ?)"
     sym (Date/valueOf "2026-05-07") (double close)]))

(defn- seed-option!
  [ds sym expiry strike right bid ask volume oi]
  (jdbc/execute! ds
    ["INSERT INTO option_chain
        (symbol, expiry, strike, opt_right, fetched_at, bid, ask, volume, open_interest)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
     sym
     (Date/valueOf expiry)
     (double strike)
     right
     (Timestamp/valueOf "2026-05-08 09:30:00")
     (when bid (double bid))
     (when ask (double ask))
     (when volume (int volume))
     (when oi (int oi))]))

(defn- fetch-row [ds sym]
  (first (jdbc/execute! ds
           [(str "SELECT * FROM latest_indicators WHERE symbol = '" sym "'")]
           {:builder-fn rs/as-unqualified-lower-maps})))

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) 0.001))

;;; ── Tests ────────────────────────────────────────────────────────────────────

(deftest happy-path-test
  (testing "AAPL: two expirations × five strikes, ATM=100, window={98,100,102}"
    ;; close=100.0, strikes 97 98 100 102 103 → ATM rank=3, ±1 = ranks 2-4 → {98,100,102}
    ;; Front exp 2099-01-01, Back exp 2099-02-01
    ;; Window = {98,100,102} × {C,P} × {front,back} = 12 rows
    ;; option_oi_total = 500+400+600+550+300+700 + 250+200+350+320+180+430 = 4780
    ;; option_vol_total = 100+90+150+140+80+110 + 50+45+70+60+40+55 = 990
    ;; atm_straddle_price: front C100 mid=(1.5+1.7)/2=1.6, P100 mid=1.6 → 3.2
    ;; implied_move_pct = 3.2/100.0 = 0.032
    ;; put_call_oi_ratio = (400+550+700+200+320+430)/(500+600+300+250+350+180) = 2600/2180
    ;; put_call_volume_ratio = (90+140+110+45+60+55)/(100+150+80+50+70+40) = 500/490
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-bar! ds "AAPL" 100.0)
      ;; Front expiry (2099-01-01) — strikes 97 98 100 102 103
      (seed-option! ds "AAPL" "2099-01-01" 97  "C" 3.0  3.2  60  200)
      (seed-option! ds "AAPL" "2099-01-01" 97  "P" 0.5  0.7  30  150)
      (seed-option! ds "AAPL" "2099-01-01" 98  "C" 2.5  2.7  100 500)
      (seed-option! ds "AAPL" "2099-01-01" 98  "P" 0.8  1.0  90  400)
      (seed-option! ds "AAPL" "2099-01-01" 100 "C" 1.5  1.7  150 600)
      (seed-option! ds "AAPL" "2099-01-01" 100 "P" 1.5  1.7  140 550)
      (seed-option! ds "AAPL" "2099-01-01" 102 "C" 0.8  1.0  80  300)
      (seed-option! ds "AAPL" "2099-01-01" 102 "P" 2.5  2.7  110 700)
      (seed-option! ds "AAPL" "2099-01-01" 103 "C" 0.5  0.7  40  100)
      (seed-option! ds "AAPL" "2099-01-01" 103 "P" 3.0  3.2  20  180)
      ;; Back expiry (2099-02-01)
      (seed-option! ds "AAPL" "2099-02-01" 97  "C" 3.5  3.7  25  120)
      (seed-option! ds "AAPL" "2099-02-01" 97  "P" 0.8  1.0  15  80)
      (seed-option! ds "AAPL" "2099-02-01" 98  "C" 3.0  3.2  50  250)
      (seed-option! ds "AAPL" "2099-02-01" 98  "P" 1.2  1.4  45  200)
      (seed-option! ds "AAPL" "2099-02-01" 100 "C" 2.0  2.2  70  350)
      (seed-option! ds "AAPL" "2099-02-01" 100 "P" 2.0  2.2  60  320)
      (seed-option! ds "AAPL" "2099-02-01" 102 "C" 1.2  1.4  40  180)
      (seed-option! ds "AAPL" "2099-02-01" 102 "P" 3.0  3.2  55  430)
      (seed-option! ds "AAPL" "2099-02-01" 103 "C" 0.8  1.0  20  90)
      (seed-option! ds "AAPL" "2099-02-01" 103 "P" 3.5  3.7  10  150)
      (oca/refresh-option-chain-agg! ds)
      (let [row (fetch-row ds "AAPL")]
        (testing "row exists in latest_indicators"
          (is (some? row)))
        (testing "all 7 option-chain columns are non-null"
          (is (some? (:option_oi_total row))       "option_oi_total")
          (is (some? (:option_vol_total row))      "option_vol_total")
          (is (some? (:atm_straddle_price row))    "atm_straddle_price")
          (is (some? (:implied_move_pct row))      "implied_move_pct")
          (is (some? (:put_call_oi_ratio row))     "put_call_oi_ratio")
          (is (some? (:put_call_volume_ratio row)) "put_call_volume_ratio")
          (is (some? (:bid_ask_spread_pct row))    "bid_ask_spread_pct"))
        (testing "option_oi_total = 4780.0"
          (is (approx= (:option_oi_total row) 4780.0)))
        (testing "option_vol_total = 990.0"
          (is (approx= (:option_vol_total row) 990.0)))
        (testing "atm_straddle_price = 3.2"
          (is (approx= (:atm_straddle_price row) 3.2)))
        (testing "implied_move_pct = 0.032"
          (is (approx= (:implied_move_pct row) 0.032)))
        (testing "put_call_oi_ratio = 2600/2180"
          (is (approx= (:put_call_oi_ratio row) (/ 2600.0 2180.0))))
        (testing "put_call_volume_ratio = 500/490"
          (is (approx= (:put_call_volume_ratio row) (/ 500.0 490.0))))
        ;; bid_ask_spread_pct: all 12 rows have spread 0.2, midpoints vary per strike/exp
        (testing "bid_ask_spread_pct matches hand-computed AVG"
          (let [spreads [(/ 0.2 2.6) (/ 0.2 0.9) (/ 0.2 1.6)
                         (/ 0.2 1.6) (/ 0.2 0.9) (/ 0.2 2.6)
                         (/ 0.2 3.1) (/ 0.2 1.3) (/ 0.2 2.1)
                         (/ 0.2 2.1) (/ 0.2 1.3) (/ 0.2 3.1)]
                expected (/ (apply + spreads) 12.0)]
            (is (approx= (:bid_ask_spread_pct row) expected))))))))

(deftest one-sided-chain-test
  (testing "ONESIDED: NULL ask on all options → NULL bid_ask_spread_pct and straddle"
    ;; close=50.0, one expiry 2099-03-15, strikes {49,50,51}
    ;; ATM=50 (rank 2 of 3), ±1 = all three strikes (ranks 1-3)
    ;; All asks are NULL → bid_ask_spread_pct = NULL, atm mids = NULL
    ;; OI and volume are valid → option_oi_total and ratios are non-NULL
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-bar! ds "ONESIDED" 50.0)
      (seed-option! ds "ONESIDED" "2099-03-15" 49 "C" 1.5 nil 30 100)
      (seed-option! ds "ONESIDED" "2099-03-15" 49 "P" 0.6 nil 25 90)
      (seed-option! ds "ONESIDED" "2099-03-15" 50 "C" 1.0 nil 40 120)
      (seed-option! ds "ONESIDED" "2099-03-15" 50 "P" 1.0 nil 35 110)
      (seed-option! ds "ONESIDED" "2099-03-15" 51 "C" 0.6 nil 20 80)
      (seed-option! ds "ONESIDED" "2099-03-15" 51 "P" 1.5 nil 15 130)
      (oca/refresh-option-chain-agg! ds)
      (let [row (fetch-row ds "ONESIDED")]
        (testing "row exists"
          (is (some? row)))
        (testing "bid_ask_spread_pct is NULL (all asks are NULL)"
          (is (nil? (:bid_ask_spread_pct row))))
        (testing "atm_straddle_price is NULL (mid requires valid bid+ask)"
          (is (nil? (:atm_straddle_price row))))
        (testing "implied_move_pct is NULL (depends on straddle price)"
          (is (nil? (:implied_move_pct row))))
        (testing "option_oi_total = 630.0 (OI is valid)"
          ;; 100+90+120+110+80+130 = 630
          (is (approx= (:option_oi_total row) 630.0)))
        (testing "option_vol_total = 165.0"
          ;; 30+25+40+35+20+15 = 165
          (is (approx= (:option_vol_total row) 165.0)))
        (testing "put_call_oi_ratio = 330/300 = 1.1"
          ;; put OI = 90+110+130=330, call OI = 100+120+80=300
          (is (approx= (:put_call_oi_ratio row) (/ 330.0 300.0))))
        (testing "put_call_volume_ratio = 75/90 ≈ 0.833"
          ;; put vol = 25+35+15=75, call vol = 30+40+20=90
          (is (approx= (:put_call_volume_ratio row) (/ 75.0 90.0))))))))

(deftest no-chain-rows-test
  (testing "NOCHAIN: bars_daily row but no option_chain rows → all 7 columns NULL"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-bar! ds "NOCHAIN" 200.0)
      (oca/refresh-option-chain-agg! ds)
      (let [row (fetch-row ds "NOCHAIN")]
        (testing "row is upserted into latest_indicators"
          (is (some? row)))
        (testing "option_oi_total is NULL"
          (is (nil? (:option_oi_total row))))
        (testing "option_vol_total is NULL"
          (is (nil? (:option_vol_total row))))
        (testing "atm_straddle_price is NULL"
          (is (nil? (:atm_straddle_price row))))
        (testing "implied_move_pct is NULL"
          (is (nil? (:implied_move_pct row))))
        (testing "put_call_oi_ratio is NULL"
          (is (nil? (:put_call_oi_ratio row))))
        (testing "put_call_volume_ratio is NULL"
          (is (nil? (:put_call_volume_ratio row))))
        (testing "bid_ask_spread_pct is NULL"
          (is (nil? (:bid_ask_spread_pct row))))))))

(deftest idempotent-test
  (testing "calling refresh-option-chain-agg! twice upserts, not duplicates"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-bar! ds "IDEM" 75.0)
      (seed-option! ds "IDEM" "2099-06-01" 74 "C" 1.0 1.2 20 100)
      (seed-option! ds "IDEM" "2099-06-01" 74 "P" 0.8 1.0 15 80)
      (seed-option! ds "IDEM" "2099-06-01" 75 "C" 0.5 0.7 30 150)
      (seed-option! ds "IDEM" "2099-06-01" 75 "P" 0.5 0.7 25 120)
      (seed-option! ds "IDEM" "2099-06-01" 76 "C" 0.8 1.0 18 90)
      (seed-option! ds "IDEM" "2099-06-01" 76 "P" 1.0 1.2 12 110)
      (oca/refresh-option-chain-agg! ds)
      (oca/refresh-option-chain-agg! ds)
      (let [rows (jdbc/execute! ds
                   ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (:n (first rows))) "upsert must not duplicate rows")))))
