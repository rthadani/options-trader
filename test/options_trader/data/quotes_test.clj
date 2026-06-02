(ns options-trader.data.quotes-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.data.quotes :as quotes]))

;;; ── Test fixture DB ───────────────────────────────────────────────────────

(defn- mem-conn
  "DuckDB in-memory databases are scoped to a single JDBC Connection, so the
   tests share one Connection across CREATE TABLE + INSERT + SELECT. Pass
   the Connection itself as the `ds` arg to next.jdbc — it works the same."
  []
  (jdbc/get-connection {:jdbcUrl "jdbc:duckdb:"}))

(defn- prep-bars! [ds rows]
  (jdbc/execute! ds
    ["CREATE TABLE bars_daily (symbol VARCHAR, bar_date DATE, volume BIGINT)"])
  (doseq [[sym d v] rows]
    (jdbc/execute! ds ["INSERT INTO bars_daily VALUES (?, CAST(? AS DATE), ?)"
                       sym d v])))

(defn- prep-indicators! [ds rows]
  (jdbc/execute! ds
    ["CREATE TABLE latest_indicators (
        symbol VARCHAR PRIMARY KEY,
        put_call_oi_ratio DOUBLE,
        put_call_volume_ratio DOUBLE)"])
  (doseq [[sym oi vol] rows]
    (jdbc/execute! ds ["INSERT INTO latest_indicators VALUES (?, ?, ?)" sym oi vol])))

;;; ── avg-volume ────────────────────────────────────────────────────────────

(deftest avg-volume-uses-last-N-days
  (let [ds (mem-conn)]
    (prep-bars! ds [["AAPL" "2026-05-20" 1000]
                    ["AAPL" "2026-05-21" 2000]
                    ["AAPL" "2026-05-22" 3000]
                    ["AAPL" "2026-05-23" 4000]
                    ["AAPL" "2026-05-24" 5000]])
    (is (= 3500.0 (quotes/avg-volume ds "AAPL" 4))
        "4-day window = (5000+4000+3000+2000)/4 = 3500")
    (is (= 3000.0 (quotes/avg-volume ds "AAPL" 5))
        "5-day window covers all bars")))

(deftest avg-volume-missing-symbol-returns-nil
  (let [ds (mem-conn)]
    (prep-bars! ds [["AAPL" "2026-05-20" 1000]])
    (is (nil? (quotes/avg-volume ds "NOPE" 14)))))

;;; ── pc-ratios ─────────────────────────────────────────────────────────────

(deftest pc-ratios-found
  (let [ds (mem-conn)]
    (prep-indicators! ds [["AAPL" 0.85 0.72]])
    (is (= {:pc-oi-ratio 0.85 :pc-vol-ratio 0.72}
           (quotes/pc-ratios ds "AAPL")))))

(deftest pc-ratios-missing-row-is-nils
  (let [ds (mem-conn)]
    (prep-indicators! ds [])
    (is (= {:pc-oi-ratio nil :pc-vol-ratio nil}
           (quotes/pc-ratios ds "NOPE")))))

;;; ── detailed-quote (assembly with stubbed IB) ─────────────────────────────

(defn- stub-snapshot-events
  "Build an IB-shaped tick event vector that exercises bid/ask/last/volume/
   VWAP-via-RT_VOLUME."
  []
  [{:type :tick-price :field 1 :price 175.10}                     ;; bid
   {:type :tick-price :field 2 :price 175.15}                     ;; ask
   {:type :tick-price :field 4 :price 175.12}                     ;; last
   {:type :tick-price :field 6 :price 176.30}                     ;; high
   {:type :tick-price :field 7 :price 174.20}                     ;; low
   {:type :tick-size  :field 8 :size  25000000}                   ;; day volume
   {:type :tick-string :field 48 :value "175.12;100;1700000000;25000000;174.85;true"}])

(deftest detailed-quote-merges-ib-and-db
  (let [ds (mem-conn)]
    (prep-bars! ds [["AAPL" "2026-05-20" 20000000]
                    ["AAPL" "2026-05-21" 22000000]
                    ["AAPL" "2026-05-22" 24000000]])
    (prep-indicators! ds [["AAPL" 0.85 0.72]])
    (with-redefs [ibkr/req-market-data-snapshot
                  (fn [_conn _contract _ticks cb]
                    (cb (stub-snapshot-events))
                    1)]
      (let [q (quotes/detailed-quote :stub-conn ds "AAPL"
                                     :timeout-ms 100 :avg-window 3)]
        (testing "live IB fields"
          (is (= 175.10 (:bid q)))
          (is (= 175.15 (:ask q)))
          (is (= 175.12 (:last q)))
          (is (= 176.30 (:high q)))
          (is (= 174.20 (:low q)))
          (is (= 174.85 (:day-vwap q))   "VWAP parsed from RT_VOLUME"))
        (testing "RT_VOLUME total-volume overrides tick-size :volume"
          (is (= 25000000.0 (:day-volume q))))
        (testing "DB-derived fields"
          (is (= 22000000.0 (:avg-volume-3d q)))
          (is (= 0.85 (:pc-oi-ratio q)))
          (is (= 0.72 (:pc-vol-ratio q))))
        (testing "computed volume ratio"
          (is (= (double (/ 25000000.0 22000000.0)) (:volume-ratio q))))))))

(deftest detailed-quote-unavailable-when-ib-rejects
  (let [ds (mem-conn)]
    (prep-bars! ds [])
    (prep-indicators! ds [])
    (with-redefs [ibkr/req-market-data-snapshot
                  (fn [_ _ _ _] :unavailable)]
      (is (= :unavailable (quotes/detailed-quote :stub-conn ds "AAPL"))))))
