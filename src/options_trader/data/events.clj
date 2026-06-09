(ns options-trader.data.events
  "Pluggable corporate-events source (dividends, splits, M&A). Config
   key :data-sources/:events."
  )


(defprotocol IEventsSource
  "Pluggable source contract for corporate events (dividends, splits, M&A, etc.).
   Implementations are registered via make-source under :data-sources/:events."
  (fetch-dividends [this symbol params]
    "Fetch dividend history and upcoming ex-dates for symbol.
     params may include :start-date, :end-date, :include-special.
     Returns a seq of maps with :symbol, :ex-date, :pay-date, :amount,
     :dividend-type, or :unavailable.")
  (fetch-splits [this symbol params]
    "Fetch stock split history for symbol.
     Returns a seq of maps with :symbol, :ex-date, :ratio, or :unavailable.")
  (fetch-corporate-actions [this symbol params]
    "Fetch all corporate actions (M&A, spin-offs, name changes) for symbol.
     Returns a seq of maps with :symbol, :action-type, :effective-date, :details,
     or :unavailable.")
  (normalise [this raw-event]
    "Normalise a raw corporate event record to canonical map form.")
  (supported-symbols [this]
    "Return the set of symbols this source covers, or :all."))


(deftype UnavailableEventsSource []
  IEventsSource
  (fetch-dividends         [_ _symbol _params] :unavailable)
  (fetch-splits            [_ _symbol _params] :unavailable)
  (fetch-corporate-actions [_ _symbol _params] :unavailable)
  (normalise               [_ raw-event]       raw-event)
  (supported-symbols       [_]                 :all))

(def ^:private default-source
  "Fallback source returned when no implementation is configured."
  (UnavailableEventsSource.))


(defmulti make-source
  "Construct an IEventsSource from a config map.
   Dispatch on :type — e.g. {:type :polygon-events, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  default-source)


(defn fetch-dividend-calendar
  "Fetch dividend schedule for symbol using the configured source.
   Config key: :data-sources/:events
   Returns a seq of canonical dividend maps or :unavailable."
  ([symbol]
   (fetch-dividend-calendar symbol {}))
  ([symbol params]
   (fetch-dividend-calendar symbol params default-source))
  ([symbol params source]
   (fetch-dividends source symbol params)))

(defn fetch-split-history
  "Fetch split history for symbol using the configured source.
   Config key: :data-sources/:events
   Returns a seq of canonical split maps or :unavailable."
  ([symbol]
   (fetch-split-history symbol {}))
  ([symbol params]
   (fetch-splits default-source symbol params)))

(defn fetch-actions
  "Fetch all corporate actions for symbol using the configured source.
   Config key: :data-sources/:events
   Returns a seq of canonical event maps or :unavailable."
  ([symbol]
   (fetch-actions symbol {}))
  ([symbol params]
   (fetch-corporate-actions default-source symbol params)))
