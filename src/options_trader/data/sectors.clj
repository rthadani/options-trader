(ns options-trader.data.sectors
  "Pluggable GICS sector / rotation source. Config key :data-sources/:sectors."
  )


(defprotocol ISectorsSource
  "Pluggable source contract for GICS sector and industry classification.
   Implementations are registered via make-source under :data-sources/:sectors."
  (fetch-classification [this symbol]
    "Return the GICS sector classification for symbol.
     Returns a map with :symbol, :sector, :industry-group, :industry,
     :sub-industry, or :unavailable.")
  (fetch-sector-performance [this sector params]
    "Fetch recent performance metrics for a GICS sector.
     params may include :period, :benchmark.
     Returns a map with :sector, :return-1m, :return-3m, :return-ytd, or :unavailable.")
  (list-sectors [this]
    "Return the full list of GICS sector names this source recognises.")
  (normalise [this raw-record]
    "Normalise a raw sector classification record to canonical map form."))


(deftype UnavailableSectorsSource []
  ISectorsSource
  (fetch-classification     [_ _symbol]          :unavailable)
  (fetch-sector-performance [_ _sector _params]  :unavailable)
  (list-sectors             [_]                  :unavailable)
  (normalise                [_ raw-record]        raw-record))

(def ^:private default-source
  "Fallback source returned when no implementation is configured."
  (UnavailableSectorsSource.))


(defmulti make-source
  "Construct an ISectorsSource from a config map.
   Dispatch on :type — e.g. {:type :spdr-etf, :api-key \"...\"}.
   Falls back to the unavailable stub when the type is unrecognised."
  :type)

(defmethod make-source :default [_cfg]
  default-source)


(defn fetch-gics-classification
  "Fetch GICS sector classification for symbol using the configured source.
   Config key: :data-sources/:sectors
   Returns a canonical classification map or :unavailable."
  ([symbol]
   (fetch-gics-classification symbol default-source))
  ([symbol source]
   (fetch-classification source symbol)))

(defn fetch-sector-perf
  "Fetch sector performance metrics using the configured source.
   Config key: :data-sources/:sectors
   Returns a performance map or :unavailable."
  ([sector]
   (fetch-sector-perf sector {}))
  ([sector params]
   (fetch-sector-performance default-source sector params)))

(defn list-gics-sectors
  "List known GICS sectors from the configured source.
   Config key: :data-sources/:sectors"
  []
  (list-sectors default-source))
