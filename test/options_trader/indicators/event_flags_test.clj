(ns options-trader.indicators.event-flags-test
  "Fixture-driven tests for the activist/M&A/FDA event-flag slice.
   Covers: M&A keyword + positive sentiment within 30d → true; old M&A news (>30d) → false;
   13D filing within 90d → true; old 13G (>90d) → false; FDA event 7d out → true;
   FDA event 30d out → false; symbol with no source rows → all three false; idempotency."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.test-util :as tu]
            [options-trader.indicators.event-flags :as ef])
  (:import [java.sql Date Timestamp]
           [java.time LocalDate LocalDateTime]))


;;; ── Seed helpers ─────────────────────────────────────────────────────────────

(defn- ts-days-ago [n]
  (Timestamp/valueOf (.minusDays (LocalDateTime/now) n)))

(defn- date-days-from-now [n]
  (Date/valueOf (.plusDays (LocalDate/now) n)))

(defn- date-days-ago [n]
  (Date/valueOf (.minusDays (LocalDate/now) n)))

(defn- seed-symbol!
  "Insert a symbol row into latest_indicators so it appears in the syms CTE."
  [ds sym]
  (jdbc/execute! ds
    ["INSERT INTO latest_indicators (symbol) VALUES (?) ON CONFLICT (symbol) DO NOTHING" sym]))

(defn- seed-news!
  "Insert a news row. body and sentiment_score are packed into the data JSON column."
  [ds id sym ^Timestamp published-at body sentiment-score]
  (jdbc/execute! ds
    ["INSERT INTO news (id, symbol, published_at, data) VALUES (?, ?, ?, ?)"
     id sym published-at
     (format "{\"body\":\"%s\",\"sentiment_score\":%s}" body (str sentiment-score))]))

(defn- seed-filing!
  [ds accession sym form-type ^Date filed-at]
  (jdbc/execute! ds
    ["INSERT INTO filings (accession, symbol, form_type, filed_at) VALUES (?, ?, ?, ?)"
     accession sym form-type filed-at]))

(defn- seed-fda-event!
  [ds id sym ^Date event-date]
  (jdbc/execute! ds
    ["INSERT INTO event_calendars (id, symbol, event_type, event_date) VALUES (?, ?, 'fda', ?)"
     id sym event-date]))


;;; ── M&A rumor flag: recent news → true ──────────────────────────────────────

(deftest ma-rumor-recent-true-test
  (testing "M&A keyword + sentiment > 0.2 within 30d sets ma_rumor_flag = true"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "MNA1")
      (seed-news! ds "n1" "MNA1" (ts-days-ago 15) "merger talks announced today" 0.5)
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "MNA1")]
        (is (some? row))
        (is (true? (:ma_rumor_flag row)) "recent M&A news with positive sentiment → true")
        (is (false? (:activist_filing_flag row)))
        (is (false? (:fda_event_flag row)))))))

;;; ── M&A rumor flag: old news → false ────────────────────────────────────────

(deftest ma-rumor-old-false-test
  (testing "M&A keyword + positive sentiment but >30d old → ma_rumor_flag = false"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "MNA2")
      (seed-news! ds "n2" "MNA2" (ts-days-ago 45) "acquisition deal closed" 0.6)
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "MNA2")]
        (is (some? row))
        (is (false? (:ma_rumor_flag row)) "stale M&A news (>30d) → false")))))

;;; ── Activist filing flag: 13D within 90d → true ─────────────────────────────

(deftest activist-13d-recent-true-test
  (testing "13D filing within last 90d sets activist_filing_flag = true"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "ACT1")
      (seed-filing! ds "acc-13d-1" "ACT1" "13D" (date-days-ago 45))
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "ACT1")]
        (is (some? row))
        (is (false? (:ma_rumor_flag row)))
        (is (true? (:activist_filing_flag row)) "13D within 90d → true")
        (is (false? (:fda_event_flag row)))))))

;;; ── Activist filing flag: old 13G → false ───────────────────────────────────

(deftest activist-13g-old-false-test
  (testing "13G filing older than 90d → activist_filing_flag = false"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "ACT2")
      (seed-filing! ds "acc-13g-2" "ACT2" "13G" (date-days-ago 100))
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "ACT2")]
        (is (some? row))
        (is (false? (:activist_filing_flag row)) "13G older than 90d → false")))))

;;; ── FDA event flag: event 7d out → true ─────────────────────────────────────

(deftest fda-event-near-true-test
  (testing "FDA event_date 7 days from now sets fda_event_flag = true"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "FDA1")
      (seed-fda-event! ds "ev-fda-1" "FDA1" (date-days-from-now 7))
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "FDA1")]
        (is (some? row))
        (is (false? (:ma_rumor_flag row)))
        (is (false? (:activist_filing_flag row)))
        (is (true? (:fda_event_flag row)) "FDA event 7d out (within 14d window) → true")))))

;;; ── FDA event flag: event 30d out → false ───────────────────────────────────

(deftest fda-event-far-false-test
  (testing "FDA event_date 30 days from now → fda_event_flag = false (outside 14d window)"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "FDA2")
      (seed-fda-event! ds "ev-fda-2" "FDA2" (date-days-from-now 30))
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "FDA2")]
        (is (some? row))
        (is (false? (:fda_event_flag row)) "FDA event 30d out (outside 14d window) → false")))))

;;; ── No source rows → all three false ────────────────────────────────────────

(deftest no-source-rows-all-false-test
  (testing "symbol in latest_indicators with no matching news/filings/events → all flags false"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "QUIET")
      (ef/refresh-event-flags! ds)
      (let [row (tu/query-row ds "QUIET")]
        (is (some? row))
        (is (false? (:ma_rumor_flag row))        "no news → ma_rumor_flag false")
        (is (false? (:activist_filing_flag row)) "no filings → activist_filing_flag false")
        (is (false? (:fda_event_flag row))       "no events → fda_event_flag false")))))

;;; ── Idempotency ──────────────────────────────────────────────────────────────

(deftest idempotency-test
  (testing "two refresh! calls produce exactly one row per symbol with stable values"
    (let [cfg (tu/tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-symbol! ds "IDEM")
      (seed-news! ds "n-idem" "IDEM" (ts-days-ago 5) "takeover bid confirmed" 0.8)
      (seed-filing! ds "acc-idem" "IDEM" "13D" (date-days-ago 30))
      (seed-fda-event! ds "ev-idem" "IDEM" (date-days-from-now 3))
      (ef/refresh-event-flags! ds)
      (ef/refresh-event-flags! ds)
      (let [cnt (-> (jdbc/execute! ds
                      ["SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = 'IDEM'"]
                      tu/as-lower)
                    first :n)
            row (tu/query-row ds "IDEM")]
        (is (= 1 cnt) "upsert must not duplicate rows")
        (is (true? (:ma_rumor_flag row))        "ma_rumor_flag persists after second call")
        (is (true? (:activist_filing_flag row)) "activist_filing_flag persists after second call")
        (is (true? (:fda_event_flag row))       "fda_event_flag persists after second call")))))
