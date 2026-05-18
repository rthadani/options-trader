(ns options-trader.data.short-interest
  "Skeleton namespace for short-interest and borrow data ingestion.
   Config key: :data-sources/:short-interest
   Protocol:   IShortInterestSource — pluggable backend contract.

   Loads without any network or file-system side-effects at namespace init time.
   No ib-re-actor or HTTP calls are made until an implementation is registered
   and explicitly invoked."
  )

;;; ── Protocol ────────────────────────────────────────────────────────────────

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
     Returns a map with :symbol, :rate, :fee-rate, :availability, or :unavailable.")
  (normalise [this raw-record]
    "Normalise a raw short-interest record to canonical map form.")
  (supported-symbols [this]
    "Return the set of symbols this source covers, or :all."))

;;; ── Stub / unavailable implementation ──────────────────────────────────────

(deftype UnavailableShortInterestSource []
  IShortInterestSource
  (fetch-short-interest [_ _symbol _params] :unavailable)
  (fetch-borrow-rate    [_ _symbol]         :unavailable)
  (normalise            [_ raw-record]      raw-record)
  (supported-symbols    [_]                 :all))

(def ^:private default-source
  "Fallback source returned when no implementation is configured."
  (UnavailableShortInterestSource.))

;;; ── Dispatch ────────────────────────────────────────────────────────────────

(defmulti make-source
  "Construct an IShortInterestSource from a config map.
   Dispatch on :type — e.g. {:type :finra, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

;;; ── Public API (delegates to configured source) ─────────────────────────────

(defn fetch-si
  "Fetch short-interest data for symbol using the configured source.
   Config key: :data-sources/:short-interest
   Returns a seq of canonical SI maps or :unavailable."
  ([symbol]
   (fetch-si symbol {}))
  ([symbol params]
   (fetch-si symbol params default-source))
  ([symbol params source]
   (fetch-short-interest source symbol params)))

(defn fetch-borrow
  "Fetch borrow rate for symbol using the configured source.
   Config key: :data-sources/:short-interest
   Returns a borrow-rate map or :unavailable."
  ([symbol]
   (fetch-borrow symbol default-source))
  ([symbol source]
   (fetch-borrow-rate source symbol)))
