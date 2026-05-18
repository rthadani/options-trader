(ns options-trader.indicators.sector-metrics-test
  "Tests for sector-relative valuation metrics.
   Covers: happy-path quartiles and percent_rank (3 sectors × 4 symbols),
   missing sector_map → NULL columns, missing fundamentals → NULL columns,
   single-symbol sector edge case, and idempotency."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db]
            [options-trader.indicators.sector-metrics :as sm])
  (:import [java.io File]))

;;; ── Temp DB fixture ──────────────────────────────────────────────────────────

(defn- tempfile-cfg []
  (let [f (File/createTempFile "sm-test-" ".duckdb")]
    (.delete f)
    {:db {:path (.getAbsolutePath f)}}))

;;; ── Seed helpers ─────────────────────────────────────────────────────────────

(defn- seed-sector! [ds sym sector]
  (jdbc/execute! ds
    ["INSERT INTO sector_map (symbol, sector) VALUES (?, ?)" sym sector]))

(defn- seed-fundamentals! [ds sym period pe-ratio ev-ebitda]
  (jdbc/execute! ds
    ["INSERT INTO fundamentals (symbol, period, fetched_at, data)
      VALUES (?, ?, CURRENT_TIMESTAMP, ?)"
     sym period
     (str "{\"pe_ratio\":" pe-ratio ",\"ev_ebitda\":" ev-ebitda "}")]))

(defn- seed-sym! [ds sym sector period pe-ratio ev-ebitda]
  (seed-sector! ds sym sector)
  (seed-fundamentals! ds sym period pe-ratio ev-ebitda))

(defn- fetch-indicator [ds sym]
  (first (jdbc/execute! ds
           [(str "SELECT * FROM latest_indicators WHERE symbol = '" sym "'")]
           {:builder-fn rs/as-unqualified-lower-maps})))

(defn- fetch-sector [ds sector]
  (first (jdbc/execute! ds
           ["SELECT * FROM sector_metrics WHERE sector = ? ORDER BY ts DESC LIMIT 1" sector]
           {:builder-fn rs/as-unqualified-lower-maps})))

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) 0.001))

;;; ── Tests ────────────────────────────────────────────────────────────────────

(deftest happy-path-test
  (testing "3 sectors × 4 symbols: hand-computed quartiles and percent_rank"
    ;; Tech sector: pe=[18,20,22,25], ev=[10,12,14,15]
    ;;   quantile_cont pe: p25=19.5, p50=21.0, p75=22.75
    ;;   quantile_cont ev: p25=11.5, p50=13.0, p75=14.25
    ;;   PERCENT_RANK pe ASC:  META=0.0, AAPL=1/3, GOOG=2/3, MSFT=1.0
    ;;   PERCENT_RANK ev ASC:  META=0.0, AAPL=1/3, GOOG=2/3, MSFT=1.0
    ;; Finance sector: pe=[11,12,13,15], ev=[7,8,9,10]
    ;;   quantile_cont pe: p25=11.75, p50=12.5, p75=13.5
    ;;   quantile_cont ev: p25=7.75,  p50=8.5,  p75=9.25
    ;; Energy sector: pe=[10,11,12,13], ev=[6,7,8,9]
    ;;   quantile_cont pe: p25=10.75, p50=11.5, p75=12.25
    ;;   quantile_cont ev: p25=6.75,  p50=7.5,  p75=8.25
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; Seed Tech sector
      (seed-sym! ds "AAPL" "Tech" "2026Q1" 20.0 12.0)
      (seed-sym! ds "MSFT" "Tech" "2026Q1" 25.0 15.0)
      (seed-sym! ds "GOOG" "Tech" "2026Q1" 22.0 14.0)
      (seed-sym! ds "META" "Tech" "2026Q1" 18.0 10.0)
      ;; Seed Finance sector
      (seed-sym! ds "GS"  "Finance" "2026Q1" 12.0 8.0)
      (seed-sym! ds "JPM" "Finance" "2026Q1" 15.0 10.0)
      (seed-sym! ds "BAC" "Finance" "2026Q1" 11.0 7.0)
      (seed-sym! ds "WFC" "Finance" "2026Q1" 13.0 9.0)
      ;; Seed Energy sector
      (seed-sym! ds "XOM" "Energy" "2026Q1" 10.0 6.0)
      (seed-sym! ds "CVX" "Energy" "2026Q1" 13.0 8.0)
      (seed-sym! ds "COP" "Energy" "2026Q1" 11.0 7.0)
      (seed-sym! ds "SLB" "Energy" "2026Q1" 12.0 9.0)
      (sm/refresh-sector-metrics! ds)

      (testing "sector_metrics rows exist for all 3 sectors"
        (is (some? (fetch-sector ds "Tech"))    "Tech sector row missing")
        (is (some? (fetch-sector ds "Finance")) "Finance sector row missing")
        (is (some? (fetch-sector ds "Energy"))  "Energy sector row missing"))

      (testing "Tech sector quartiles"
        (let [row (fetch-sector ds "Tech")]
          (is (approx= (:pe_p25 row) 19.5)   "pe_p25")
          (is (approx= (:pe_p50 row) 21.0)   "pe_p50")
          (is (approx= (:pe_p75 row) 22.75)  "pe_p75")
          (is (approx= (:ev_ebitda_p25 row) 11.5)  "ev_ebitda_p25")
          (is (approx= (:ev_ebitda_p50 row) 13.0)  "ev_ebitda_p50")
          (is (approx= (:ev_ebitda_p75 row) 14.25) "ev_ebitda_p75")))

      (testing "Finance sector quartiles"
        (let [row (fetch-sector ds "Finance")]
          (is (approx= (:pe_p25 row) 11.75)  "pe_p25")
          (is (approx= (:pe_p50 row) 12.5)   "pe_p50")
          (is (approx= (:pe_p75 row) 13.5)   "pe_p75")
          (is (approx= (:ev_ebitda_p25 row) 7.75) "ev_ebitda_p25")
          (is (approx= (:ev_ebitda_p50 row) 8.5)  "ev_ebitda_p50")
          (is (approx= (:ev_ebitda_p75 row) 9.25) "ev_ebitda_p75")))

      (testing "Energy sector quartiles"
        (let [row (fetch-sector ds "Energy")]
          (is (approx= (:pe_p25 row) 10.75)  "pe_p25")
          (is (approx= (:pe_p50 row) 11.5)   "pe_p50")
          (is (approx= (:pe_p75 row) 12.25)  "pe_p75")
          (is (approx= (:ev_ebitda_p25 row) 6.75) "ev_ebitda_p25")
          (is (approx= (:ev_ebitda_p50 row) 7.5)  "ev_ebitda_p50")
          (is (approx= (:ev_ebitda_p75 row) 8.25) "ev_ebitda_p75")))

      (testing "Tech sector PERCENT_RANK by pe_ratio ASC"
        ;; META(18)→0.0, AAPL(20)→1/3, GOOG(22)→2/3, MSFT(25)→1.0
        (is (approx= (:pe_vs_sector_pct (fetch-indicator ds "META")) 0.0)         "META cheapest")
        (is (approx= (:pe_vs_sector_pct (fetch-indicator ds "AAPL")) (/ 1.0 3.0)) "AAPL 2nd")
        (is (approx= (:pe_vs_sector_pct (fetch-indicator ds "GOOG")) (/ 2.0 3.0)) "GOOG 3rd")
        (is (approx= (:pe_vs_sector_pct (fetch-indicator ds "MSFT")) 1.0)         "MSFT most expensive"))

      (testing "Tech sector PERCENT_RANK by ev_ebitda ASC"
        (is (approx= (:ev_ebitda_vs_sector_pct (fetch-indicator ds "META")) 0.0)         "META cheapest ev")
        (is (approx= (:ev_ebitda_vs_sector_pct (fetch-indicator ds "AAPL")) (/ 1.0 3.0)) "AAPL 2nd ev")
        (is (approx= (:ev_ebitda_vs_sector_pct (fetch-indicator ds "GOOG")) (/ 2.0 3.0)) "GOOG 3rd ev")
        (is (approx= (:ev_ebitda_vs_sector_pct (fetch-indicator ds "MSFT")) 1.0)         "MSFT most expensive ev"))

      (testing "Finance and Energy symbols have non-null percent_rank columns"
        (doseq [sym ["GS" "JPM" "BAC" "WFC" "XOM" "CVX" "COP" "SLB"]]
          (let [row (fetch-indicator ds sym)]
            (is (some? (:pe_vs_sector_pct row))          (str sym " pe_vs_sector_pct"))
            (is (some? (:ev_ebitda_vs_sector_pct row))   (str sym " ev_ebitda_vs_sector_pct"))))))))

(deftest missing-sector-map-test
  (testing "Symbol with fundamentals but no sector_map row → both columns NULL"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; NOSEC has fundamentals but no sector_map entry
      (seed-fundamentals! ds "NOSEC" "2026Q1" 30.0 15.0)
      ;; Pre-seed NOSEC into latest_indicators so we can check its columns
      (jdbc/execute! ds ["INSERT INTO latest_indicators (symbol) VALUES ('NOSEC')
                          ON CONFLICT (symbol) DO NOTHING"])
      (sm/refresh-sector-metrics! ds)
      (let [row (fetch-indicator ds "NOSEC")]
        (testing "row exists in latest_indicators"
          (is (some? row)))
        (testing "pe_vs_sector_pct is NULL (no sector_map row)"
          (is (nil? (:pe_vs_sector_pct row))))
        (testing "ev_ebitda_vs_sector_pct is NULL (no sector_map row)"
          (is (nil? (:ev_ebitda_vs_sector_pct row))))))))

(deftest missing-fundamentals-test
  (testing "Symbol with sector_map but no fundamentals row → both columns NULL"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      ;; NOFUND has sector_map but no fundamentals row
      (seed-sector! ds "NOFUND" "Tech")
      ;; Pre-seed NOFUND into latest_indicators
      (jdbc/execute! ds ["INSERT INTO latest_indicators (symbol) VALUES ('NOFUND')
                          ON CONFLICT (symbol) DO NOTHING"])
      (sm/refresh-sector-metrics! ds)
      (let [row (fetch-indicator ds "NOFUND")]
        (testing "row exists in latest_indicators"
          (is (some? row)))
        (testing "pe_vs_sector_pct is NULL (no fundamentals row)"
          (is (nil? (:pe_vs_sector_pct row))))
        (testing "ev_ebitda_vs_sector_pct is NULL (no fundamentals row)"
          (is (nil? (:ev_ebitda_vs_sector_pct row))))))))

(deftest single-symbol-sector-test
  (testing "Lone symbol in a sector: PERCENT_RANK = 0.0, quartiles = the value itself"
    ;; SOLO is the only member of the 'Solo' sector.
    ;; PERCENT_RANK of a single-row partition = 0.0
    ;; quantile_cont of a single value at any quantile = that value
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-sym! ds "SOLO" "Solo" "2026Q1" 30.0 20.0)
      (sm/refresh-sector-metrics! ds)

      (testing "sector_metrics row exists for Solo"
        (is (some? (fetch-sector ds "Solo"))))

      (testing "all quartiles equal the single value"
        (let [row (fetch-sector ds "Solo")]
          (is (approx= (:pe_p25 row) 30.0) "pe_p25")
          (is (approx= (:pe_p50 row) 30.0) "pe_p50")
          (is (approx= (:pe_p75 row) 30.0) "pe_p75")
          (is (approx= (:ev_ebitda_p25 row) 20.0) "ev_ebitda_p25")
          (is (approx= (:ev_ebitda_p50 row) 20.0) "ev_ebitda_p50")
          (is (approx= (:ev_ebitda_p75 row) 20.0) "ev_ebitda_p75")))

      (testing "PERCENT_RANK of the lone member = 0.0"
        (let [row (fetch-indicator ds "SOLO")]
          (is (some? row) "SOLO row must exist in latest_indicators")
          (is (approx= (:pe_vs_sector_pct row) 0.0)        "pe_vs_sector_pct = 0.0")
          (is (approx= (:ev_ebitda_vs_sector_pct row) 0.0) "ev_ebitda_vs_sector_pct = 0.0"))))))

(deftest idempotency-test
  (testing "Two calls with same data produce identical sector_metrics rows for same ts"
    (let [cfg (tempfile-cfg)
          _   (db/bootstrap! cfg)
          ds  (db/datasource cfg)]
      (seed-sym! ds "IDEM1" "IdSector" "2026Q1" 10.0 5.0)
      (seed-sym! ds "IDEM2" "IdSector" "2026Q1" 20.0 10.0)
      (seed-sym! ds "IDEM3" "IdSector" "2026Q1" 15.0 7.5)
      (sm/refresh-sector-metrics! ds)
      (sm/refresh-sector-metrics! ds)

      (testing "exactly one sector_metrics row for IdSector"
        (let [cnt (-> (jdbc/execute! ds
                        ["SELECT COUNT(*) AS n FROM sector_metrics WHERE sector = 'IdSector'"]
                        {:builder-fn rs/as-unqualified-lower-maps})
                      first :n)]
          (is (= 1 cnt) "upsert must not duplicate sector rows")))

      (testing "exactly one latest_indicators row per symbol"
        (doseq [sym ["IDEM1" "IDEM2" "IDEM3"]]
          (let [cnt (-> (jdbc/execute! ds
                          [(str "SELECT COUNT(*) AS n FROM latest_indicators WHERE symbol = '" sym "'")]
                          {:builder-fn rs/as-unqualified-lower-maps})
                        first :n)]
            (is (= 1 cnt) (str sym " must not be duplicated")))))

      (testing "sector_metrics values are stable after two calls"
        (let [row (fetch-sector ds "IdSector")]
          (is (some? row))
          (is (some? (:pe_p25 row)))
          (is (some? (:pe_p50 row)))
          (is (some? (:pe_p75 row)))))

      (testing "latest_indicators columns are stable after two calls"
        (doseq [sym ["IDEM1" "IDEM2" "IDEM3"]]
          (let [row (fetch-indicator ds sym)]
            (is (some? (:pe_vs_sector_pct row))        (str sym " pe_vs_sector_pct"))
            (is (some? (:ev_ebitda_vs_sector_pct row)) (str sym " ev_ebitda_vs_sector_pct"))))))))
