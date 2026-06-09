(ns options-trader.data.short-interest
  "Pluggable short-interest / borrow-rate source. Config key
   :data-sources/:short-interest."
  (:require [options-trader.data.sources :as sources]
            [options-trader.db.queries.research-cache :as q]
            [taoensso.timbre :as log]))


(defprotocol IShortInterestSource
  "Pluggable source contract for short-interest and borrow-rate data.
   Implementations are registered via make-source under :data-sources/:short-interest."
  (fetch-short-interest [this symbol params]
    "Fetch short-interest data for symbol.
     params may include :settlement-date, :exchange.
     Returns a seq of maps with :symbol, :settlement-date, :short-interest,
     :float-shares, :days-to-cover, :short-pct-float, or :unavailable.")
  (fetch-borrow-rate [this symbol]
    "Fetch the current borrow/rebate rate for symbol.
     Returns a map with :symbol, :rate, :fee-rate, :availability, or :unavailable."))


(deftype UnavailableShortInterestSource []
  IShortInterestSource
  (fetch-short-interest [_ _symbol _params] :unavailable)
  (fetch-borrow-rate    [_ _symbol]         :unavailable))

(def ^:private unavailable-source (UnavailableShortInterestSource.))

(defn default-source []
  (or (sources/default-source :short-interest) unavailable-source))

(deftype DuckDbShortInterestSource [ds fallback]
  IShortInterestSource
  (fetch-short-interest [_ symbol params]
    (let [limit (or (:limit params) 12)
          rows  (try (q/latest-short-interest ds symbol limit)
                     (catch Throwable t
                       (log/warnf t "duckdb short_interest lookup failed for %s" symbol)
                       nil))]
      (cond
        (seq rows)
        (mapv (fn [r]
                {:symbol          (:symbol r)
                 :settlement-date (:settlement_date r)
                 :short-interest  (:short_interest r)
                 :float-shares    (:float_shares r)
                 :days-to-cover   (:days_to_cover r)
                 :short-pct-float (:short_pct_float r)
                 :source          :duckdb-cache})
              rows)

        fallback (fetch-short-interest fallback symbol params)
        :else    :unavailable)))

  (fetch-borrow-rate [_ symbol]
    (if fallback (fetch-borrow-rate fallback symbol) :unavailable)))

(defmulti make-source
  "Construct an IShortInterestSource from a config map.
   Dispatch on :type — e.g. {:type :finra, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  (default-source))

(defmethod make-source :duckdb-cache [{:keys [ds fallback]}]
  (->DuckDbShortInterestSource ds fallback))


(defn fetch-si
  "Fetch short-interest data for symbol using the configured source.
   Config key: :data-sources/:short-interest
   Returns a seq of canonical SI maps or :unavailable."
  ([symbol]
   (fetch-si symbol {}))
  ([symbol params]
   (fetch-si symbol params (default-source)))
  ([symbol params source]
   (fetch-short-interest source symbol params)))

(defn fetch-borrow
  "Fetch borrow rate for symbol using the configured source.
   Config key: :data-sources/:short-interest
   Returns a borrow-rate map or :unavailable."
  ([symbol]
   (fetch-borrow symbol (default-source)))
  ([symbol source]
   (fetch-borrow-rate source symbol)))
