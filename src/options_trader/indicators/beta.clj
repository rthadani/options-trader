(ns options-trader.indicators.beta
  "Cross-symbol 252-day OLS beta vs a benchmark (default SPY).
   Source SQL: resources/sql/indicators.sql (beta-recompute)."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [options-trader.db.queries.indicators :as q]))

(defn ensure-schema! [ds]
  (q/ensure-double-columns! ds [:beta_252d]))

(defn- benchmark-symbol
  "Read :indicators :beta-benchmark from config.edn (aero resolves
   #env / #or tags), defaulting to 'SPY'."
  []
  (try
    (let [cfg (aero/read-config (io/resource "config.edn") {:profile :dev})]
      (or (get-in cfg [:indicators :beta-benchmark])
          (get-in cfg [:dev :indicators :beta-benchmark])
          "SPY"))
    (catch Throwable _ "SPY")))

(defn refresh-beta!
  "Compute 252-day OLS beta for every symbol against the benchmark and
   upsert into latest_indicators. Requires >= 252 paired observations;
   NULL otherwise."
  ([ds] (refresh-beta! ds (benchmark-symbol)))
  ([ds benchmark-sym]
   (ensure-schema! ds)
   (jdbc/execute! ds (q/beta-recompute-sqlvec {:benchmark benchmark-sym}))))
