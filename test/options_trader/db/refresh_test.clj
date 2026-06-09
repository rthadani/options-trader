(ns options-trader.db.refresh-test
  (:require [clojure.test            :refer [deftest is testing use-fixtures]]
            [next.jdbc               :as jdbc]
            [options-trader.db.duckdb  :as duckdb]
            [options-trader.test-util    :as tu]
            [options-trader.db.refresh :as refresh]
            [options-trader.util         :refer [as-lower]]
            [options-trader.data.ibkr  :as ibkr])
  (:import [java.time LocalDate]
           [java.sql Timestamp]))

(def ^:dynamic *ds* nil)


(use-fixtures :each
  (fn [f]
    (let [cfg (tu/tempfile-cfg)]
      (duckdb/bootstrap! cfg)
      (binding [*ds* (duckdb/datasource cfg)]
        (try (f)
             (finally
               (try (.delete ^java.io.File (:_file cfg)) (catch Throwable _))))))))


(deftest duration-for-gap-first-fetch-is-5-years
  (is (= "5 Y" (refresh/duration-for-gap nil))))

(deftest duration-for-gap-recent-uses-short-window
  (is (= "2 D" (refresh/duration-for-gap (LocalDate/now)))))

(deftest duration-for-gap-week-old
  (is (= "1 W" (refresh/duration-for-gap (.minusDays (LocalDate/now) 5)))))

(deftest duration-for-gap-month-old
  (is (= "1 M" (refresh/duration-for-gap (.minusDays (LocalDate/now) 20)))))

(deftest duration-for-gap-quarter-old
  (is (= "3 M" (refresh/duration-for-gap (.minusDays (LocalDate/now) 60)))))

(deftest duration-for-gap-very-old
  (is (= "1 Y" (refresh/duration-for-gap (.minusDays (LocalDate/now) 300)))))


(deftest latest-bar-date-returns-nil-when-empty
  (is (nil? (refresh/latest-bar-date *ds* "AAPL"))))

(deftest latest-bar-date-returns-max-after-insert
  (jdbc/execute! *ds*
    ["INSERT INTO bars_daily (symbol, bar_date, close) VALUES ('AAPL', ?, 100.0)"
     (LocalDate/of 2025 5 1)])
  (jdbc/execute! *ds*
    ["INSERT INTO bars_daily (symbol, bar_date, close) VALUES ('AAPL', ?, 110.0)"
     (LocalDate/of 2025 5 5)])
  (is (= (LocalDate/of 2025 5 5) (refresh/latest-bar-date *ds* "AAPL"))))


(deftest intraday-duration-first-fetch-by-bar-size
  (is (= "2 D"  (refresh/intraday-duration-for-gap nil "1 min")))
  (is (= "1 W"  (refresh/intraday-duration-for-gap nil "5 mins")))
  (is (= "2 W"  (refresh/intraday-duration-for-gap nil "15 mins")))
  (is (= "1 M"  (refresh/intraday-duration-for-gap nil "1 hour"))))

(deftest intraday-duration-incremental-uses-short-window
  (let [recent (Timestamp. (System/currentTimeMillis))]
    (is (= "1 D" (refresh/intraday-duration-for-gap recent "15 mins")))))


(deftest news-start-date-default-is-30-days
  (let [s (refresh/news-start-date-for-gap nil)]
    (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.0" s))))

(deftest news-start-date-uses-last-published
  (let [ts (Timestamp. (- (System/currentTimeMillis) (* 3600 1000)))
        s  (refresh/news-start-date-for-gap ts)]
    (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.0" s))))


(defn- log-row-count [ds]
  (-> (jdbc/execute-one! ds ["SELECT COUNT(*) AS n FROM refresh_log"] as-lower) :n))

(deftest with-log-records-ok-on-success
  (refresh/refresh-fundamentals! {:ds *ds*})
  (is (= 1 (log-row-count *ds*)))
  (let [row (jdbc/execute-one! *ds*
              ["SELECT task, status FROM refresh_log ORDER BY id DESC LIMIT 1"]
              as-lower)]
    (is (= "fundamentals" (:task row)))
    (is (= "ok" (:status row)))))


(defn- fake-bars [days-back symbol]
  (let [today (LocalDate/now)]
    (mapv (fn [n]
            {:symbol  symbol
             :bar-date (.minusDays today n)
             :open 100.0 :high 105.0 :low 95.0 :close 102.0 :volume 1000})
          (range days-back))))

(defn- stub-historical-bars! [bars-by-symbol]
  (fn [_conn contract _bar-size _duration cb]
    (cb (get bars-by-symbol (:symbol contract) []))
    1))

(deftest refresh-bars-daily-inserts-rows
  (with-redefs [ibkr/req-historical-bars
                (stub-historical-bars! {"AAPL" (fake-bars 5 "AAPL")
                                        "MSFT" (fake-bars 3 "MSFT")})]
    (let [r (refresh/refresh-bars-daily!
              {:conn :stub :ds *ds* :symbols ["AAPL" "MSFT"]
               :timeout-ms 1000 :compute-indicators? false})]
      (is (= 2 (:symbols-ok r)))
      (is (= 0 (:symbols-err r)))
      (is (= 8 (:rows r))))))

(deftest refresh-bars-daily-second-run-uses-shorter-window
  (testing "first run with no DB state fetches 5 Y; second run uses incremental window"
    (let [recorded (atom [])
          stub     (fn [_conn contract _bar-size duration cb]
                     (swap! recorded conj {:symbol (:symbol contract) :duration duration})
                     (cb (fake-bars 2 (:symbol contract)))
                     1)]
      (with-redefs [ibkr/req-historical-bars stub]
        (refresh/refresh-bars-daily!
          {:conn :stub :ds *ds* :symbols ["AAPL"]
           :timeout-ms 1000 :compute-indicators? false})
        (let [first-dur (:duration (first @recorded))]
          (is (= "5 Y" first-dur)
              "with no prior bars, first request should backfill 5 Y"))
        (reset! recorded [])
        (refresh/refresh-bars-daily!
          {:conn :stub :ds *ds* :symbols ["AAPL"]
           :timeout-ms 1000 :compute-indicators? false})
        (let [second-dur (:duration (first @recorded))]
          (is (not= "5 Y" second-dur)
              "second run should use the incremental short window, not 5 Y again")
          (is (#{"2 D" "1 W" "1 M" "3 M"} second-dur)))))))

(deftest refresh-bars-daily-failed-symbol-does-not-block-others
  (with-redefs [ibkr/req-historical-bars
                (fn [_conn contract _bar-size _duration cb]
                  (if (= "AAPL" (:symbol contract))
                    (cb :unavailable)
                    (cb (fake-bars 3 (:symbol contract))))
                  1)]
    (let [r (refresh/refresh-bars-daily!
              {:conn :stub :ds *ds* :symbols ["AAPL" "MSFT"]
               :timeout-ms 1000 :compute-indicators? false})]
      (is (= 1 (:symbols-ok r))    "MSFT succeeded")
      (is (= 1 (:unavailable r))   "AAPL was marked unavailable but didn't crash the batch")
      (is (= 0 (:symbols-err r))   "no thrown exceptions"))))

(deftest refresh-bars-daily-next-run-recovers-failed-symbol
  (testing "if a symbol fails on run 1, run 2's incremental fetch still has nil last-date → still backfills"
    (let [call-count (atom 0)
          stub (fn [_conn contract _bar-size _duration cb]
                 (swap! call-count inc)
                 (if (and (= "AAPL" (:symbol contract)) (= 1 @call-count))
                   (cb :unavailable)
                   (cb (fake-bars 5 (:symbol contract))))
                 1)]
      (with-redefs [ibkr/req-historical-bars stub]
        (refresh/refresh-bars-daily!
          {:conn :stub :ds *ds* :symbols ["AAPL"]
           :timeout-ms 1000 :compute-indicators? false})
        (is (zero? (or (-> (jdbc/execute-one! *ds*
                              ["SELECT COUNT(*) AS n FROM bars_daily WHERE symbol = 'AAPL'"]
                              as-lower) :n) 0))
            "run 1 failed → no rows persisted")
        (refresh/refresh-bars-daily!
          {:conn :stub :ds *ds* :symbols ["AAPL"]
           :timeout-ms 1000 :compute-indicators? false})
        (is (pos? (or (-> (jdbc/execute-one! *ds*
                             ["SELECT COUNT(*) AS n FROM bars_daily WHERE symbol = 'AAPL'"]
                             as-lower) :n) 0))
            "run 2 succeeded → rows now persisted, self-healing")))))
