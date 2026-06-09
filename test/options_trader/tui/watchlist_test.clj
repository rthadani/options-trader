(ns options-trader.tui.watchlist-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [options-trader.paths :as paths]
            [options-trader.tui.state :as st]
            [options-trader.tui.watchlist :as wl]))

;; Each test runs against an isolated temp config dir so the user's real
;; watchlist.edn is untouched. OPTIONS_TRADER_CONFIG_DIR is the documented
;; override (see paths.clj).

(defn- with-temp-config [t]
  (let [dir (java.io.File/createTempFile "ot-watchlist-test-" "")
        _   (.delete dir)
        _   (.mkdirs dir)]
    (try
      (binding [paths/*config-root-override* (.getAbsolutePath dir)]
        (reset! st/state st/initial-state)
        (t))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete f))))))

(use-fixtures :each with-temp-config)

(deftest persist-then-load-round-trips
  (swap! st/state assoc :watchlist ["AAPL" "NVDA" "TSLA"])
  (wl/persist!)
  (is (.exists (io/file (paths/watchlist-file)))
      "persist! creates the file")
  (is (= ["AAPL" "NVDA" "TSLA"] (wl/load-symbols))
      "load-symbols returns the same vector"))

(deftest load-missing-file-returns-empty
  (is (= [] (wl/load-symbols))
      "no file on disk → empty vec, not crash"))

(deftest load-malformed-file-returns-empty
  (spit (paths/watchlist-file) "{not edn at all")
  (is (= [] (wl/load-symbols))
      "garbage in file → empty vec, not crash"))

(deftest persist-overwrites-prior-contents
  (swap! st/state assoc :watchlist ["AAPL"])
  (wl/persist!)
  (swap! st/state assoc :watchlist ["MSFT" "GOOG"])
  (wl/persist!)
  (is (= ["MSFT" "GOOG"] (wl/load-symbols))
      "later persist replaces earlier list"))

(deftest empty-watchlist-persists-as-empty-vec
  (swap! st/state assoc :watchlist [])
  (wl/persist!)
  (is (= [] (wl/load-symbols))
      "empty watchlist round-trips as []"))

;; Row layout in panel-lines (per `top-gap` reserved row):
;;   row 0 — empty top-gap (aligns watchlist below the portfolio status row)
;;   row 1 — table header (SYM LAST CHG% …)
;;   row 2+ — quote rows

(deftest panel-lines-renders-persisted-symbols-without-quotes
  ;; The case where TWS hasn't connected yet: symbols loaded from disk
  ;; but no quotes have arrived. Each row should still render with the
  ;; symbol visible and dashes in every numeric column.
  (swap! st/state assoc :watchlist ["AAPL" "NVDA"] :watchlist-quotes {})
  (let [lines (wl/panel-lines @st/state 6)
        body  (drop 2 lines)]                  ; drop top-gap + header
    (is (clojure.string/starts-with? (first body)  "AAPL"))
    (is (clojure.string/starts-with? (second body) "NVDA"))
    (testing "no-quote rows render dashes, not crash"
      (is (clojure.string/includes? (first body) "-")))))

(deftest quote-row-colours-chg-by-sign
  ;; Coloured rows must include the ANSI SGR escape; uncoloured ones must not.
  (let [esc (str (char 27) "[")
        state (-> @st/state
                  (assoc :watchlist ["UP" "DN" "FLAT" "NONE"]
                         :watchlist-quotes
                         {"UP"   {:last 102.0 :close 100.0}
                          "DN"   {:last  98.0 :close 100.0}
                          "FLAT" {:last 100.0 :close 100.0}
                          "NONE" {:last 50.0}}))
        rows (wl/panel-lines state 7)]        ; top-gap + header + 4 quotes + slack
    (is (clojure.string/includes? (nth rows 2) (str esc "32m"))
        "up row coloured green")
    (is (clojure.string/includes? (nth rows 3) (str esc "31m"))
        "down row coloured red")
    (is (not (clojure.string/includes? (nth rows 4) esc))
        "flat row has no ANSI colouring")
    (is (not (clojure.string/includes? (nth rows 5) esc))
        "row with no :close gets the '-' placeholder, no colour")))

(deftest panel-lines-empty-watchlist-renders-placeholder
  ;; Regression: cond->> + constantly composed wrong, blowing up panel-lines
  ;; on every fresh launch with no symbols. The placeholder row should
  ;; render, not throw.
  (swap! st/state assoc :watchlist [] :watchlist-quotes {})
  (let [lines (wl/panel-lines @st/state 5)]
    (is (= 5 (count lines)) "height honoured even when watchlist is empty")
    ;; Top-gap (row 0), header (row 1), placeholder (row 2).
    (is (clojure.string/includes? (nth lines 2) "/add-to-watchlist")
        "placeholder row points the user at the slash command")))

;; update-quote: pure tick → quote-map folder. Locks the field-code map so
;; bid/ask/last/sizes/volume stop being silently dropped.

(deftest update-quote-folds-bid-ask-last-prices
  (let [m0 {}
        m1 (wl/update-quote m0 {:type :tick-price :field 1 :price 100.50})
        m2 (wl/update-quote m1 {:type :tick-price :field 2 :price 100.52})
        m3 (wl/update-quote m2 {:type :tick-price :field 4 :price 100.51})]
    (is (= 100.50 (:bid  m3)))
    (is (= 100.52 (:ask  m3)))
    (is (= 100.51 (:last m3)))))

(deftest update-quote-also-accepts-delayed-field-codes
  ;; IB sends 66/67/68 for delayed bid/ask/last when the account has no
  ;; live entitlement. Watchlist should still populate the columns.
  (let [m (-> {}
              (wl/update-quote {:type :tick-price :field 66 :price 50.0})
              (wl/update-quote {:type :tick-price :field 67 :price 50.5})
              (wl/update-quote {:type :tick-price :field 68 :price 50.2}))]
    (is (= 50.0 (:bid  m)))
    (is (= 50.5 (:ask  m)))
    (is (= 50.2 (:last m)))))

(deftest update-quote-rejects-no-data-price-sentinel
  ;; IB sends -1.0 to mean "no data" — do not store it as a real price.
  (let [m (-> {:bid 100.0}
              (wl/update-quote {:type :tick-price :field 1 :price -1.0}))]
    (is (= 100.0 (:bid m)) "prior bid survives the no-data sentinel")))

(deftest update-quote-folds-sizes
  ;; bid/ask sizes pass through. tick-size :volume is intentionally NOT
  ;; a source — its lot multiplier varies per contract. Volume is
  ;; sourced from bars_daily on add!, not from the tick stream.
  (let [m (-> {}
              (wl/update-quote {:type :tick-size :field 0 :size 200})
              (wl/update-quote {:type :tick-size :field 3 :size 150})
              (wl/update-quote {:type :tick-size :field 8 :size 88000}))]
    (is (= 200.0 (:bid-size m)))
    (is (= 150.0 (:ask-size m)))
    (is (nil?    (:volume   m)) "tick stream never writes :volume")))

(deftest update-quote-preserves-volume-set-by-add!
  ;; Regression: now that :volume is set by add! (from bars_daily) and
  ;; never overwritten by the tick stream, a tick must not zero it out.
  (let [m (-> {:volume 936000.0 :avg-volume 500000.0}
              (wl/update-quote {:type :tick-price :field 4 :price 100.0})
              (wl/update-quote {:type :tick-size  :field 8 :size 99999}))]
    (is (= 936000.0 (:volume m)) "warehouse-sourced volume survives ticks")))

(deftest update-quote-captures-prev-close
  ;; TWS sends field 9 (live close) or 75 (delayed close) as part of the
  ;; default tick set. Stash it so the CHG% column can compute against it.
  (let [m1 (wl/update-quote {} {:type :tick-price :field 9  :price 234.10})
        m2 (wl/update-quote {} {:type :tick-price :field 75 :price 234.10})]
    (is (= 234.10 (:close m1)))
    (is (= 234.10 (:close m2)) "delayed-close field 75 maps to :close")))

(deftest update-quote-coerces-non-number-sizes
  ;; Regression: newer IB API delivers tick-size :size as com.ib.client.Decimal,
  ;; which is not a Number — (double v) used to throw and the swap was
  ;; silently dropped, leaving BSZ/ASZ columns blank forever.
  (let [m (wl/update-quote {} {:type :tick-size :field 0 :size "200.5"})]
    (is (= 200.5 (:bid-size m)) "string-typed sizes coerce correctly"))
  (testing "unparseable size leaves the field nil, doesn't throw"
    (let [m (wl/update-quote {} {:type :tick-size :field 0 :size "not-a-number"})]
      (is (nil? (:bid-size m))))))

(deftest update-quote-ignores-rt-volume-tick-string
  ;; RT_VOLUME (tickString 48) used to source :volume; that path was
  ;; removed because IB reports lot units for some stocks and the
  ;; warehouse value is more trustworthy. The handler should just
  ;; pass through without writing :volume.
  (let [m (wl/update-quote {} {:type :tick-string :tick-type 48
                               :value "208.08;4;1780953098044;1375710;208.35;false"})]
    (is (nil? (:volume m)) "tickString 48 no longer touches :volume")))

(deftest update-quote-preserves-existing-fields
  ;; Each tick should fold INTO the existing map, not replace it. Without
  ;; this property avg-volume (set by add!) would get wiped on the first tick.
  (let [m0 {:avg-volume 1e6}
        m1 (wl/update-quote m0 {:type :tick-price :field 4 :price 234.5})]
    (is (= 1e6 (:avg-volume m1)) "avg-volume survives a tick fold")
    (is (= 234.5 (:last m1)))))

(deftest update-quote-ignores-irrelevant-events
  ;; Things like :tick-snapshot-end or non-bid/ask/last fields should
  ;; pass through without touching real fields, only bumping :updated-at.
  (let [m0 {:bid 100.0 :avg-volume 1e6}
        m1 (wl/update-quote m0 {:type :tick-snapshot-end})
        m2 (wl/update-quote m0 {:type :tick-price :field 6 :price 105.0})]
    (is (= 100.0 (:bid m1)) ":bid untouched by snapshot-end")
    (is (= 1e6   (:avg-volume m1)))
    (is (= 100.0 (:bid m2)) ":bid untouched by tick on field 6 (high)")
    (is (not (contains? m2 :high)) "high tick is ignored, not stored")))
