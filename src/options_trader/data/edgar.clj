(ns options-trader.data.edgar
  "SEC EDGAR data ingestion backed by clojure-finance/edgarjure (edgar.api).
   Config key: :data-sources/:edgar
   Protocol:   IEdgarSource — pluggable backend contract.

   The edgarjure-backed source requires a SEC User-Agent (\"Name email\").
   Supply it via :user-agent on the source config, or set EDGAR_USER_AGENT
   in the environment. Without a configured User-Agent the namespace falls
   back to UnavailableEdgarSource and every call returns :unavailable —
   no network traffic is issued at namespace load."
  (:require [edgar.api :as e]
            [taoensso.timbre :as log]))

;;; ── Protocol ────────────────────────────────────────────────────────────────

(defprotocol IEdgarSource
  "Pluggable source contract for SEC EDGAR filings and metadata.
   Implementations are registered via make-source under :data-sources/:edgar."
  (fetch-filings [this symbol params]
    "Fetch a seq of filing metadata maps for the given symbol.
     params may include :form-type (e.g. \"10-K\"), :start-date, :end-date, :limit.
     Returns a seq of canonical filing maps or :unavailable.")
  (fetch-facts [this cik taxonomy]
    "Fetch XBRL facts for the given CIK and taxonomy (e.g. :us-gaap).
     Returns a map of concept → seq of fact values, or :unavailable.")
  (fetch-filing-body [this filing-or-accession format]
    "Fetch the full filing body. `filing-or-accession` is either an accession
     number string or a filing map (from fetch-filings). format is :text or :html.
     Returns the body string or :unavailable.")
  (fetch-filing-item [this filing-or-accession item-id]
    "Extract a specific Item section (e.g. \"7\" for MD&A, \"1A\" for Risk Factors)
     from a 10-K/10-Q. Returns the section text or :unavailable.")
  (normalise-filing [this raw-filing]
    "Normalise a raw filing map to the canonical schema. Returns a map with
     :symbol :cik :accession :form-type :filed-at :period-of-report :data.")
  (supported-forms [this]
    "Return the set of SEC form types this source supports, or :all."))

;;; ── Unavailable stub ───────────────────────────────────────────────────────

(deftype UnavailableEdgarSource []
  IEdgarSource
  (fetch-filings     [_ _symbol _params]   :unavailable)
  (fetch-facts       [_ _cik _taxonomy]    :unavailable)
  (fetch-filing-body [_ _filing _fmt]      :unavailable)
  (fetch-filing-item [_ _filing _item-id]  :unavailable)
  (normalise-filing  [_ raw-filing]        raw-filing)
  (supported-forms   [_]                   :all))

(def ^:private unavailable-source (UnavailableEdgarSource.))

;;; ── Edgarjure-backed implementation ────────────────────────────────────────

(defn- ->canonical-filing
  "Map an edgarjure filing record to the canonical schema used by the
   `filings` DuckDB table (accession, symbol, cik, form_type, filed_at,
   period, data). edgarjure returns camelCase keys; the raw payload is
   preserved under :data for downstream consumers."
  [symbol raw]
  {:symbol           symbol
   :cik              (:cik raw)
   :accession        (:accessionNumber raw)
   :form-type        (:form raw)
   :filed-at         (:filingDate raw)
   :period-of-report (:reportDate raw)
   :data             raw})

(defn- ->params
  "Translate our protocol params map to edgarjure.filings kwargs."
  [{:keys [form-type start-date end-date limit include-amends?]}]
  (cond-> []
    form-type       (into [:form form-type])
    start-date      (into [:start-date start-date])
    end-date        (into [:end-date   end-date])
    limit           (into [:limit limit])
    include-amends? (into [:include-amends? true])))

(defn- safely
  "Run f; on Throwable, log and return :unavailable so callers never crash
   on a transient SEC error. edgarjure raises ex-info with :status on HTTP
   failures and IllegalArgumentException on unknown tickers."
  [tag f]
  (try (f)
       (catch Throwable t
         (log/warnf t "edgar/%s failed" tag)
         :unavailable)))

(defn- ->filing-map
  "Coerce input to edgarjure's filing-map form. Accepts:
     - a raw filing map (as returned by e/filing/e/filings)
     - one of our canonical filings (lifts :data which holds the raw map)
     - an accession number string (resolves via e/filing-by-accession)"
  [filing-or-accession]
  (cond
    (map? filing-or-accession)    (or (:data filing-or-accession) filing-or-accession)
    (string? filing-or-accession) (e/filing-by-accession filing-or-accession)
    :else (throw (ex-info "invalid filing argument" {:got filing-or-accession}))))

(deftype EdgarjureSource [user-agent]
  IEdgarSource
  (fetch-filings [_ symbol params]
    (safely :fetch-filings
      #(let [raw (apply e/filings symbol (->params params))]
         (mapv (partial ->canonical-filing symbol) raw))))
  (fetch-facts [_ cik _taxonomy]
    ;; edgarjure normalises across us-gaap/ifrs/dei automatically; the
    ;; taxonomy arg is accepted for protocol back-compat but unused.
    (safely :fetch-facts #(e/facts cik)))
  (fetch-filing-body [_ filing-or-accession format]
    (safely :fetch-filing-body
      #(let [fm (->filing-map filing-or-accession)]
         (case format
           :html (e/html fm)
           (e/text fm)))))
  (fetch-filing-item [_ filing-or-accession item-id]
    (safely :fetch-filing-item
      #(e/item (->filing-map filing-or-accession) (str item-id))))
  (normalise-filing [_ raw]
    (->canonical-filing (:symbol raw) raw))
  (supported-forms [_] :all))

;;; ── init! gating ────────────────────────────────────────────────────────────

(defonce ^:private init-once! (atom nil))

(defn- ensure-init!
  "Call edgar.api/init! exactly once per JVM with the supplied User-Agent.
   Required by SEC fair-access policy."
  [user-agent]
  (when (compare-and-set! init-once! nil user-agent)
    (e/init! user-agent)))

;;; ── Dispatch ────────────────────────────────────────────────────────────────

(defmulti make-source
  "Construct an IEdgarSource from a config map. Dispatches on :type.
   Recognised types: :edgar, :unavailable. Unknown / missing → :unavailable stub."
  :type)

(defmethod make-source :default [_cfg]
  unavailable-source)

(defmethod make-source :unavailable [_cfg]
  unavailable-source)

(defmethod make-source :edgar [{:keys [user-agent]}]
  (if (and (string? user-agent) (seq user-agent))
    (do (ensure-init! user-agent)
        (EdgarjureSource. user-agent))
    (do (log/warn "edgar: :edgar source missing :user-agent — falling back to unavailable")
        unavailable-source)))

;;; ── Default-source resolution ──────────────────────────────────────────────

(defonce ^:private default-source-holder (atom nil))

(defn set-default-source!
  "Install src as the process-wide default EDGAR source. Returns src."
  [src]
  (reset! default-source-holder src)
  src)

(defn- env-source []
  (when-let [ua (some-> (System/getenv "EDGAR_USER_AGENT") not-empty)]
    (make-source {:type :edgar :user-agent ua})))

(defn default-source
  "Return the process-wide default EDGAR source. Resolution order:
   (1) explicit set-default-source!  (2) EDGAR_USER_AGENT env var
   (3) UnavailableEdgarSource. Memoises the env-var path so init! only
   fires once per JVM."
  []
  (or @default-source-holder
      (when-let [s (env-source)]
        (reset! default-source-holder s))
      unavailable-source))

;;; ── Public API (delegates to configured source) ─────────────────────────────

(defn fetch-edgar-filings
  "Fetch EDGAR filings for symbol. Returns a seq of canonical filing maps
   or :unavailable."
  ([symbol]                 (fetch-edgar-filings symbol {} (default-source)))
  ([symbol params]          (fetch-edgar-filings symbol params (default-source)))
  ([symbol params source]   (fetch-filings source symbol params)))

(defn fetch-edgar-facts
  "Fetch XBRL facts for the given CIK. Returns a concept-keyed map or :unavailable."
  ([cik]                    (fetch-edgar-facts cik :us-gaap (default-source)))
  ([cik taxonomy]           (fetch-edgar-facts cik taxonomy (default-source)))
  ([cik taxonomy source]    (fetch-facts source cik taxonomy)))

(defn fetch-edgar-filing-body
  "Fetch the full filing body. `filing-or-accession` is either an accession
   number string (e.g. \"0000320193-25-000079\") or a filing map from
   fetch-edgar-filings. format is :text (default) or :html.
   Returns the body string or :unavailable."
  ([filing-or-accession]        (fetch-edgar-filing-body filing-or-accession :text (default-source)))
  ([filing-or-accession format] (fetch-edgar-filing-body filing-or-accession format (default-source)))
  ([filing-or-accession format source] (fetch-filing-body source filing-or-accession format)))

(defn fetch-edgar-filing-item
  "Extract a specific Item section (\"7\" for MD&A, \"1A\" for Risk Factors, …)
   from a 10-K / 10-Q filing. `filing-or-accession` is the accession string or
   the filing map. Returns the section text or :unavailable."
  ([filing-or-accession item-id]        (fetch-edgar-filing-item filing-or-accession item-id (default-source)))
  ([filing-or-accession item-id source] (fetch-filing-item source filing-or-accession item-id)))
