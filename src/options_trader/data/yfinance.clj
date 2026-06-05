(ns options-trader.data.yfinance
  "Yahoo Finance source adapter via clj-yfinance. Fills the
   short_interest / earnings_events / earnings_calendar tables that
   EDGAR and IB don't cover. Yahoo wraps numerics as {:raw N :fmt s};
   the `raw` helper unwraps."
  (:require [clj-yfinance.experimental.fundamentals :as yff]
            [taoensso.timbre :as log])
  (:import [java.time Instant ZoneOffset]
           [java.sql Date]))

(defn- raw [v]
  (cond
    (nil? v)    nil
    (map? v)    (:raw v)
    (number? v) v
    :else       nil))

(defn- epoch->sql-date [secs]
  (when (number? secs)
    (-> (Instant/ofEpochSecond (long secs))
        (.atZone ZoneOffset/UTC)
        .toLocalDate
        Date/valueOf)))

(defn fetch-short-interest
  "Snapshot from Yahoo's defaultKeyStatistics module. Returns a
   short_interest row map or nil. `fetch-quotesummary*` is the verbose
   variant — payload is under :data."
  [symbol]
  (try
    (let [resp (yff/fetch-quotesummary* symbol "defaultKeyStatistics")
          ks   (get-in resp [:data :defaultKeyStatistics])]
      (when (and (:ok? resp) ks)
        {:symbol          symbol
         :settlement-date (epoch->sql-date (raw (:dateShortInterest ks)))
         :short-interest  (some-> (raw (:sharesShort ks)) long)
         :float-shares    (some-> (raw (:floatShares ks)) long)
         :days-to-cover   (raw (:shortRatio ks))
         :short-pct-float (raw (:shortPercentOfFloat ks))
         :source          :yahoo}))
    (catch Throwable t
      (log/warnf "yahoo short-interest fetch failed for %s: %s"
                 symbol (.getMessage t))
      nil)))

(defn fetch-earnings-history
  "Last ~4 quarters from Yahoo's earningsHistory module. Returns a seq
   of earnings_events rows or nil on error."
  [symbol]
  (try
    (let [resp (yff/fetch-analyst symbol)
          hist (get-in resp [:earningsHistory :history])]
      (when (seq hist)
        (->> hist
             (keep (fn [{:keys [epsActual epsEstimate quarter surprisePercent period]}]
                     (when-let [reported (epoch->sql-date (raw quarter))]
                       {:symbol       symbol
                        :period       (or period "")
                        :reported-at  reported
                        :eps-actual   (raw epsActual)
                        :eps-estimate (raw epsEstimate)
                        :surprise-pct (some-> (raw surprisePercent) (* 100.0))})))
             vec)))
    (catch Throwable t
      (log/warnf "yahoo earnings-history fetch failed for %s: %s"
                 symbol (.getMessage t))
      nil)))

(defn fetch-earnings-calendar
  "Next upcoming earnings date from Yahoo's calendar module."
  [symbol]
  (try
    (let [resp       (yff/fetch-calendar symbol)
          ev         (get-in resp [:earnings :earningsDate])
          first-date (some-> ev first raw epoch->sql-date)
          eps-est    (raw (get-in resp [:earnings :earningsAverage]))]
      (when first-date
        {:symbol       symbol
         :report-date  first-date
         :report-time  nil
         :eps-estimate eps-est}))
    (catch Throwable t
      (log/warnf "yahoo earnings-calendar fetch failed for %s: %s"
                 symbol (.getMessage t))
      nil)))
