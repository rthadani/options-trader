(ns options-trader.indicators.event-flags
  "Activist / M&A / FDA event-flag BOOLEAN columns on latest_indicators.
   Source SQL: resources/sql/indicators.sql (event-flags). M&A keywords
   come from resources/event-flags.edn and are passed into the query as
   a vector of LIKE patterns."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [options-trader.db.queries.indicators :as q]))

(defn ensure-schema! [ds]
  (doseq [col [:ma_rumor_flag :activist_filing_flag :fda_event_flag]]
    (q/ensure-column-with-type! ds :latest_indicators col "BOOLEAN")))

(defn- ma-patterns
  "Read M&A keywords from event-flags.edn. Each keyword becomes a
   single-element tuple so hugsql's :tuple* can spread them into the
   VALUES list as (?), (?), …"
  []
  (let [cfg (edn/read-string (slurp (io/resource "event-flags.edn")))]
    (mapv (fn [k] [(str "%" (str/lower-case k) "%")]) (:ma-keywords cfg))))

(defn refresh-event-flags!
  "Compute the three event flags by joining news / filings / event_calendars
   against latest_indicators' symbol set. All flags COALESCE to FALSE when
   no source rows match."
  [ds]
  (ensure-schema! ds)
  (q/run-static-recompute! ds (q/event-flags-sqlvec {:ma-patterns (ma-patterns)})))
