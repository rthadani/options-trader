(ns options-trader.data.earnings
  "Pluggable earnings source. Config key :data-sources/:earnings."
  (:require [options-trader.data.sources :as sources]
            [options-trader.db.queries.research-cache :as q]
            [taoensso.timbre :as log]))


(defprotocol IEarningsSource
  "Pluggable source contract for earnings and guidance data.
   Implementations are registered via make-source under :data-sources/:earnings."
  (fetch-history [this symbol params]
    "Fetch historical EPS and revenue actuals for symbol.
     params may include :quarters (count), :start-date.
     Returns a seq of maps with :symbol, :period, :eps-actual, :eps-estimate,
     :revenue-actual, :revenue-estimate, :surprise-pct, or :unavailable.")
  (fetch-calendar [this date-range]
    "Fetch upcoming earnings calendar for the given date range map.
     date-range must contain :start and :end as java.time.LocalDate.
     Returns a seq of {:symbol, :report-date, :report-time :bmo/:amc}, or :unavailable.")
  (normalise [this raw-record]
    "Normalise a single raw earnings record to canonical map form.")
  (supported-symbols [this]
    "Return the set of symbols this source covers, or :all."))


(deftype UnavailableEarningsSource []
  IEarningsSource
  (fetch-history    [_ _symbol _params]    :unavailable)
  (fetch-calendar   [_ _date-range]        :unavailable)
  (normalise        [_ raw-record]         raw-record)
  (supported-symbols [_]                   :all))

(def ^:private unavailable-source (UnavailableEarningsSource.))

(defn default-source []
  (or (sources/default-source :earnings) unavailable-source))

(deftype DuckDbEarningsSource [ds fallback]
  IEarningsSource
  (fetch-history [_ symbol params]
    (let [limit (or (:quarters params) 12)
          rows  (try (q/latest-earnings-history ds symbol limit)
                     (catch Throwable t
                       (log/warnf t "duckdb earnings history lookup failed for %s" symbol)
                       nil))]
      (cond
        (seq rows)
        (mapv (fn [r]
                {:symbol           (:symbol r)
                 :period           (:period r)
                 :reported-at      (:reported_at r)
                 :eps-actual       (:eps_actual r)
                 :eps-estimate     (:eps_estimate r)
                 :revenue-actual   (:rev_actual r)
                 :revenue-estimate (:rev_estimate r)
                 :surprise-pct     (:surprise_pct r)
                 :source           :duckdb-cache})
              rows)

        fallback (fetch-history fallback symbol params)
        :else    :unavailable)))

  (fetch-calendar [_ {:keys [start end] :as date-range}]
    (let [rows (try (q/earnings-calendar-range ds {:start start :end end})
                    (catch Throwable _ nil))]
      (cond
        (seq rows)
        (mapv (fn [r] {:symbol       (:symbol r)
                       :report-date  (:report_date r)
                       :report-time  (:report_time r)
                       :eps-estimate (:eps_estimate r)
                       :source       :duckdb-cache})
              rows)

        fallback (fetch-calendar fallback date-range)
        :else    :unavailable)))

  (normalise         [_ raw] raw)
  (supported-symbols [_]     :all))

(defmulti make-source
  "Construct an IEarningsSource from a config map.
   Dispatch on :type — e.g. {:type :alpha-vantage, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  (default-source))

(defmethod make-source :duckdb-cache [{:keys [ds fallback]}]
  (->DuckDbEarningsSource ds fallback))


(defn fetch-earnings-history
  "Fetch historical earnings for symbol using the configured source.
   Config key: :data-sources/:earnings
   Returns a seq of canonical period maps or :unavailable."
  ([symbol]
   (fetch-earnings-history symbol {}))
  ([symbol params]
   (fetch-earnings-history symbol params (default-source)))
  ([symbol params source]
   (fetch-history source symbol params)))

(defn fetch-earnings-calendar
  "Fetch upcoming earnings calendar using the configured source.
   Config key: :data-sources/:earnings
   date-range — map with :start and :end keys."
  ([date-range]
   (fetch-earnings-calendar date-range (default-source)))
  ([date-range source]
   (fetch-calendar source date-range)))
