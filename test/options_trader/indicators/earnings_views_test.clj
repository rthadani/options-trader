(ns options-trader.indicators.earnings-views-test
  "Tests for earnings-derived indicator columns."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.earnings-views :as ev]
            [options-trader.test-util :as tu])
  (:import [java.sql Date]
           [java.time LocalDate]))

(defn- seed-event!
  [ds sym period ^LocalDate reported-at
   eps-actual eps-estimate rev-actual rev-estimate
   implied-move gross-margin operating-margin guidance]
  (jdbc/execute! ds
    ["INSERT INTO earnings_events
      (symbol, period, reported_at,
       eps_actual, eps_estimate, rev_actual, rev_estimate,
       implied_move_pct_pre_event, gross_margin, operating_margin, guidance_direction)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
     sym period (Date/valueOf reported-at)
     eps-actual eps-estimate rev-actual rev-estimate
     implied-move gross-margin operating-margin guidance]))

(defn- seed-bar!
  "Insert a bar_daily row. open/close are the key values; high=open+2, low=open-2."
  [ds sym ^LocalDate bar-date open close volume]
  (jdbc/execute! ds
    ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
      VALUES (?, ?, ?, ?, ?, ?, ?)"
     sym (Date/valueOf bar-date)
     open (+ open 2.0) (- open 2.0) close (long volume)]))

(defn- seed-calendar! [ds sym ^LocalDate report-date]
  (jdbc/execute! ds
    ["INSERT INTO earnings_calendar (symbol, report_date) VALUES (?, ?)"
     sym (Date/valueOf report-date)]))



(deftest happy-path-test
  (testing "8 events: hand-computed avg_earnings_move_pct and count_above_implied"
    ;; rn=1..5: close_before=100, close_after=106 → realized=0.06, implied=0.04 → counts
    ;; rn=6..8: close_before=100, close_after=102 → realized=0.02, implied=0.04 → doesn't count
    ;; avg_earnings_move_pct = (5*0.06 + 3*0.02)/8 = 0.36/8 = 0.045
    ;; earnings_move_count_above_implied = 5.0
    ;; Most recent event (rn=1):
    ;;   gap_held_flag: close_after=106 > open_after=103 → true
    ;;   earnings_day_volume_ratio: 2_000_000 / 1_000_000 = 2.0
    ;;   eps_surprise_pct: (1.20-1.00)/1.00*100 = 20.0
    ;;   revenue_surprise_pct: (5500-5000)/5000*100 = 10.0
    ;;   gross_margin_delta_qoq: 0.45-0.40 = 0.05
    ;;   operating_margin_delta_qoq: 0.20-0.18 = 0.02
    (let [cfg      (tu/tempfile-cfg)
          _        (db/bootstrap! cfg)
          ds       (db/datasource cfg)
          _        (ev/refresh-earnings-views! ds)   ; schema only
          ev1      (LocalDate/of 2020 1 15)
          ev2      (LocalDate/of 2019 10 15)
          ev3      (LocalDate/of 2019 7 15)
          ev4      (LocalDate/of 2019 4 15)
          ev5      (LocalDate/of 2019 1 15)
          ev6      (LocalDate/of 2018 10 15)
          ev7      (LocalDate/of 2018 7 15)
          ev8      (LocalDate/of 2018 4 15)]
      ;; Seed 8 events (rn order is determined by reported_at DESC)
      (seed-event! ds "HAPPY" "Q4-2019" ev1 1.20 1.00 5500.0 5000.0 0.04 0.45 0.20 "raised")
      (seed-event! ds "HAPPY" "Q3-2019" ev2 1.10 1.00 5200.0 5000.0 0.04 0.40 0.18 nil)
      (seed-event! ds "HAPPY" "Q2-2019" ev3 1.10 1.00 5200.0 5000.0 0.04 0.42 0.19 nil)
      (seed-event! ds "HAPPY" "Q1-2019" ev4 1.10 1.00 5200.0 5000.0 0.04 0.38 0.17 nil)
      (seed-event! ds "HAPPY" "Q4-2018" ev5 1.10 1.00 5200.0 5000.0 0.04 0.39 0.17 nil)
      (seed-event! ds "HAPPY" "Q3-2018" ev6 1.00 1.00 5000.0 5000.0 0.04 0.36 0.16 nil)
      (seed-event! ds "HAPPY" "Q2-2018" ev7 1.00 1.00 5000.0 5000.0 0.04 0.35 0.15 nil)
      (seed-event! ds "HAPPY" "Q1-2018" ev8 1.00 1.00 5000.0 5000.0 0.04 0.34 0.14 nil)
      ;; Seed 20 bars before ev1 (for avg_vol_20d = 1_000_000); includes the T-1 bar at 2020-01-14
      (doseq [i (range 20)]
        (seed-bar! ds "HAPPY" (.minusDays ev1 (inc i)) 99.0 100.0 1000000))
      ;; Earnings day bar for rn=1 (volume = 2_000_000)
      (seed-bar! ds "HAPPY" ev1 101.0 105.0 2000000)
      ;; T+1 bar for rn=1: close=106 > open=103 → gap_held=true
      (seed-bar! ds "HAPPY" (.plusDays ev1 1) 103.0 106.0 1500000)
      ;; T-1 and T+1 bars for rn=2..5 (realized=0.06, above implied)
      (doseq [d [ev2 ev3 ev4 ev5]]
        (seed-bar! ds "HAPPY" (.minusDays d 1) 99.0 100.0 1000000)
        (seed-bar! ds "HAPPY" (.plusDays  d 1) 102.0 106.0 1500000))
      ;; T-1 and T+1 bars for rn=6..8 (realized=0.02, below implied)
      (doseq [d [ev6 ev7 ev8]]
        (seed-bar! ds "HAPPY" (.minusDays d 1) 99.0 100.0 1000000)
        (seed-bar! ds "HAPPY" (.plusDays  d 1) 102.0 102.0 1500000))
      ;; Future calendar event (always in the future)
      (seed-calendar! ds "HAPPY" (LocalDate/of 2099 1 1))
      (ev/refresh-earnings-views! ds)
      (let [row (tu/query-row ds "HAPPY")]
        (testing "row exists"
          (is (some? row)))
        (testing "avg_earnings_move_pct ≈ 0.045"
          (is (some? (:avg_earnings_move_pct row)))
          (is (tu/approx= (:avg_earnings_move_pct row) 0.045)))
        (testing "earnings_move_count_above_implied = 5.0"
          (is (some? (:earnings_move_count_above_implied row)))
          (is (tu/approx= (:earnings_move_count_above_implied row) 5.0)))
        (testing "days_since_earnings > 0 (event in the past)"
          (is (some? (:days_since_earnings row)))
          (is (pos? (:days_since_earnings row))))
        (testing "days_to_next_earnings > 0 (calendar in 2099)"
          (is (some? (:days_to_next_earnings row)))
          (is (pos? (:days_to_next_earnings row))))
        (testing "gap_held_flag = true (close_after=106 > open_after=103)"
          (is (true? (:gap_held_flag row))))
        (testing "earnings_day_volume_ratio ≈ 2.0"
          (is (some? (:earnings_day_volume_ratio row)))
          (is (tu/approx= (:earnings_day_volume_ratio row) 2.0)))
        (testing "eps_surprise_pct ≈ 20.0"
          (is (some? (:eps_surprise_pct row)))
          (is (tu/approx= (:eps_surprise_pct row) 20.0)))
        (testing "revenue_surprise_pct ≈ 10.0"
          (is (some? (:revenue_surprise_pct row)))
          (is (tu/approx= (:revenue_surprise_pct row) 10.0)))
        (testing "guidance_direction = raised"
          (is (= "raised" (:guidance_direction row))))
        (testing "gross_margin_delta_qoq ≈ 0.05 (0.45 - 0.40)"
          (is (some? (:gross_margin_delta_qoq row)))
          (is (tu/approx= (:gross_margin_delta_qoq row) 0.05)))
        (testing "operating_margin_delta_qoq ≈ 0.02 (0.20 - 0.18)"
          (is (some? (:operating_margin_delta_qoq row)))
          (is (tu/approx= (:operating_margin_delta_qoq row) 0.02)))))))


(deftest partial-history-test
  (testing "<8 events still computes avg_earnings_move_pct"
    ;; 4 events, all realized=0.05 (close_after=105), implied=0.04 → all count
    ;; avg = 0.05, count = 4.0
    (let [cfg  (tu/tempfile-cfg)
          _    (db/bootstrap! cfg)
          ds   (db/datasource cfg)
          _    (ev/refresh-earnings-views! ds)
          base (LocalDate/of 2020 1 15)]
      (doseq [i (range 4)]
        (let [d (.minusMonths base (* 3 i))]
          (seed-event! ds "PART" (str "Q" i) d 1.05 1.00 5000.0 5000.0 0.04 nil nil nil)
          (seed-bar! ds "PART" (.minusDays d 1) 99.0 100.0 1000000)
          (seed-bar! ds "PART" (.plusDays  d 1) 102.0 105.0 1000000)))
      (ev/refresh-earnings-views! ds)
      (let [row (tu/query-row ds "PART")]
        (testing "row exists"
          (is (some? row)))
        (testing "avg_earnings_move_pct ≈ 0.05 (4 events, all realized=0.05)"
          (is (some? (:avg_earnings_move_pct row)))
          (is (tu/approx= (:avg_earnings_move_pct row) 0.05)))
        (testing "earnings_move_count_above_implied = 4.0 (all 4 above 0.04)"
          (is (some? (:earnings_move_count_above_implied row)))
          (is (tu/approx= (:earnings_move_count_above_implied row) 4.0)))))))


(deftest no-events-test
  (testing "symbol with no earnings_events row → all derived columns NULL"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; Ensure schema exists, then pre-seed the symbol
      (ev/refresh-earnings-views! ds)
      (jdbc/execute! ds
        ["INSERT INTO latest_indicators (symbol) VALUES ('NOEV') ON CONFLICT (symbol) DO NOTHING"])
      (ev/refresh-earnings-views! ds)
      (let [row (tu/query-row ds "NOEV")]
        (testing "row exists in latest_indicators"
          (is (some? row)))
        (testing "avg_earnings_move_pct is NULL"
          (is (nil? (:avg_earnings_move_pct row))))
        (testing "earnings_move_count_above_implied is NULL"
          (is (nil? (:earnings_move_count_above_implied row))))
        (testing "days_since_earnings is NULL"
          (is (nil? (:days_since_earnings row))))
        (testing "days_to_next_earnings is NULL"
          (is (nil? (:days_to_next_earnings row))))
        (testing "gap_held_flag is NULL"
          (is (nil? (:gap_held_flag row))))
        (testing "guidance_direction is NULL"
          (is (nil? (:guidance_direction row))))))))


(deftest no-calendar-test
  (testing "earnings events exist but no earnings_calendar row → days_to_next NULL, days_since non-NULL"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)
          _   (ev/refresh-earnings-views! ds)
          d   (LocalDate/of 2020 1 15)]
      (seed-event! ds "NOCAL" "Q4-2019" d 1.0 1.0 1000.0 1000.0 nil nil nil nil)
      (seed-bar! ds "NOCAL" (.minusDays d 1) 99.0 100.0 1000000)
      (seed-bar! ds "NOCAL" (.plusDays  d 1) 102.0 101.0 1000000)
      ;; No earnings_calendar row seeded
      (ev/refresh-earnings-views! ds)
      (let [row (tu/query-row ds "NOCAL")]
        (testing "row exists"
          (is (some? row)))
        (testing "days_since_earnings is non-NULL and positive (past event)"
          (is (some? (:days_since_earnings row)))
          (is (pos? (:days_since_earnings row))))
        (testing "days_to_next_earnings is NULL (no calendar row)"
          (is (nil? (:days_to_next_earnings row))))))))


(deftest gap-held-flag-test
  (testing "gap_held_flag: true when close_after > open_after, false otherwise"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)
          _   (ev/refresh-earnings-views! ds)
          d   (LocalDate/of 2020 1 15)]
      ;; GHELD: T+1 close=106 > open=103 → gap held
      (seed-event! ds "GHELD" "Q4-2019" d 1.0 1.0 1000.0 1000.0 nil nil nil nil)
      (seed-bar! ds "GHELD" (.minusDays d 1) 99.0 100.0 1000000)
      (seed-bar! ds "GHELD" (.plusDays  d 1) 103.0 106.0 1000000)
      ;; GNHLD: T+1 close=102 < open=106 → gap NOT held
      (seed-event! ds "GNHLD" "Q4-2019" d 1.0 1.0 1000.0 1000.0 nil nil nil nil)
      (seed-bar! ds "GNHLD" (.minusDays d 1) 99.0 100.0 1000000)
      (seed-bar! ds "GNHLD" (.plusDays  d 1) 106.0 102.0 1000000)
      (ev/refresh-earnings-views! ds)
      (testing "gap held → gap_held_flag = true"
        (is (true? (:gap_held_flag (tu/query-row ds "GHELD")))))
      (testing "gap NOT held → gap_held_flag = false"
        (is (false? (:gap_held_flag (tu/query-row ds "GNHLD"))))))))


(deftest idempotency-test
  (testing "two refresh calls with same data produce identical rows (no duplicates)"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)
          _   (ev/refresh-earnings-views! ds)
          d   (LocalDate/of 2020 1 15)]
      (seed-event! ds "IDEM" "Q4-2019" d 1.10 1.00 5500.0 5000.0 0.04 nil nil nil)
      (seed-bar! ds "IDEM" (.minusDays d 1) 99.0 100.0 1000000)
      (seed-bar! ds "IDEM" (.plusDays  d 1) 103.0 106.0 1500000)
      (seed-calendar! ds "IDEM" (LocalDate/of 2099 1 1))
      (ev/refresh-earnings-views! ds)
      (ev/refresh-earnings-views! ds)
      (testing "exactly one latest_indicators row for IDEM"
        (let [cnt (-> (jdbc/execute! ds
                        ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                        tu/as-lower)
                      first :n)]
          (is (= 1 cnt) "upsert must not duplicate rows")))
      (testing "avg_earnings_move_pct persists after second call"
        (is (some? (:avg_earnings_move_pct (tu/query-row ds "IDEM")))))
      (testing "days_to_next_earnings persists after second call"
        (is (some? (:days_to_next_earnings (tu/query-row ds "IDEM"))))))))
