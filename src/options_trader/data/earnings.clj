(ns options-trader.data.earnings
  "Skeleton namespace for earnings data ingestion.
   Config key: :data-sources/:earnings
   Protocol:   IEarningsSource — pluggable backend contract.

   Loads without any network or file-system side-effects at namespace init time.
   No ib-re-actor or HTTP calls are made until an implementation is registered
   and explicitly invoked."
  )

;;; ── Protocol ────────────────────────────────────────────────────────────────

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

;;; ── Stub / unavailable implementation ──────────────────────────────────────

(deftype UnavailableEarningsSource []
  IEarningsSource
  (fetch-history    [_ _symbol _params]    :unavailable)
  (fetch-calendar   [_ _date-range]        :unavailable)
  (normalise        [_ raw-record]         raw-record)
  (supported-symbols [_]                   :all))

(def ^:private default-source
  "Fallback source returned when no implementation is configured."
  (UnavailableEarningsSource.))

;;; ── Dispatch ────────────────────────────────────────────────────────────────

(defmulti make-source
  "Construct an IEarningsSource from a config map.
   Dispatch on :type — e.g. {:type :alpha-vantage, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

;;; ── Public API (delegates to configured source) ─────────────────────────────

(defn fetch-earnings-history
  "Fetch historical earnings for symbol using the configured source.
   Config key: :data-sources/:earnings
   Returns a seq of canonical period maps or :unavailable."
  ([symbol]
   (fetch-earnings-history symbol {}))
  ([symbol params]
   (fetch-earnings-history symbol params default-source))
  ([symbol params source]
   (fetch-history source symbol params)))

(defn fetch-earnings-calendar
  "Fetch upcoming earnings calendar using the configured source.
   Config key: :data-sources/:earnings
   date-range — map with :start and :end keys."
  ([date-range]
   (fetch-earnings-calendar date-range default-source))
  ([date-range source]
   (fetch-calendar source date-range)))
