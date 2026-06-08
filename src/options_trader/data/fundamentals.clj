(ns options-trader.data.fundamentals
  (:require [cheshire.core            :as json]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.data.sources :as sources]
            [options-trader.db.queries.research-cache :as qcache]
            [options-trader.util      :as util]
            [edgar.api                :as edgar]
            [taoensso.timbre          :as log]))

(defonce ^:private edgar-init-once! (atom nil))

;;; ── Canonical fundamentals schema ───────────────────────────────────────────
;;;
;;; Same keys regardless of source. When IBKR ReportSnapshot is wired the
;;; Reuters codes (TTMNIAC, TTMREVPS, etc.) map onto these same keywords.

(def ^:private edgar-column->canonical
  "EDGAR/edgarjure column name → canonical field keyword."
  {;; income statement
   "Revenue"               :revenue
   "Cost of Revenue"       :cost-of-revenue
   "Gross Profit"          :gross-profit
   "Operating Expenses"    :operating-expenses
   "R&D Expense"           :rnd-expense
   "SG&A Expense"          :sga-expense
   "Operating Income"      :operating-income
   "Non-Operating Income"  :non-operating-income
   "Pre-Tax Income"        :pre-tax-income
   "Income Tax Expense"    :income-tax-expense
   "Net Income"            :net-income
   "EPS Basic"             :eps-basic
   "EPS Diluted"           :eps-diluted
   "Shares Basic"          :shares-basic
   "Shares Diluted"        :shares-diluted
   ;; balance sheet
   "Total Assets"          :total-assets
   "Current Assets"        :current-assets
   "Non-Current Assets"    :non-current-assets
   "Cash and Equivalents"  :cash
   "Short-Term Investments" :short-term-investments
   "Accounts Receivable"   :accounts-receivable
   "Inventory"             :inventory
   "PP&E Net"              :ppe-net
   "Goodwill"              :goodwill
   "Intangibles"           :intangibles
   "Total Liabilities"     :total-liabilities
   "Current Liabilities"   :current-liabilities
   "Non-Current Liabilities" :non-current-liabilities
   "Long-Term Debt"        :long-term-debt
   "Stockholders Equity"   :stockholders-equity
   "Retained Earnings"     :retained-earnings
   "Common Stock"          :common-stock
   ;; cash flow
   "Operating Cash Flow"   :operating-cash-flow
   "Investing Cash Flow"   :investing-cash-flow
   "Financing Cash Flow"   :financing-cash-flow
   "Capex"                 :capex
   "D&A"                   :depreciation-amortization
   "Dividends Paid"        :dividends-paid
   "Share Buybacks"        :share-buybacks
   "Acquisitions"          :acquisitions
   "LT Debt Issued"        :lt-debt-issued
   "LT Debt Repaid"        :lt-debt-repaid
   "Net Change in Cash"    :net-change-in-cash})

(defn- ds->canonical-rows
  "Convert a tech.ml.dataset of statement rows (one row per period) into a
   seq of canonical-keyed maps with :period-end."
  [ds]
  (when ds
    (try
      (require 'tech.v3.dataset)
      (let [rows-fn (resolve 'tech.v3.dataset/rows)]
        (when rows-fn
          (->> (rows-fn ds)
               (map (fn [row]
                      (-> (reduce-kv
                            (fn [m k v]
                              (cond
                                (= k :end)            (assoc m :period-end v)
                                (= k "fp")            (assoc m :fiscal-period v)
                                (= k "fy")            (assoc m :fiscal-year v)
                                :else (if-let [ck (edgar-column->canonical k)]
                                        (assoc m ck v)
                                        m)))
                            {} row)))))))
      (catch Throwable _ nil))))

(defn- with-free-cash-flow
  "Free cash flow = operating cash flow − |capex|. edgarjure reports capex
   as a positive magnitude; subtract to get FCF."
  [m]
  (let [ocf (:operating-cash-flow m)
        cap (:capex m)]
    (cond-> m
      (and (number? ocf) (number? cap))
      (assoc :free-cash-flow (- ocf (Math/abs (double cap)))))))

(defn- merge-period
  "Merge income + balance + cashflow rows that share a :period-end."
  [rows]
  (let [grouped (group-by :period-end rows)]
    (->> grouped
         (map (fn [[end maps]] (with-free-cash-flow (apply merge maps))))
         (sort-by :period-end)
         reverse
         vec)))

(defn normalize-edgar
  "Canonicalize an EDGAR fundamentals payload (the map produced by
   EdgarFundamentalsSource.fetch) into a single uniform shape:

     {:symbol :source :as-of :period :metadata
      :revenue :gross-profit :operating-income :net-income :eps-diluted ...
      :total-assets :total-liabilities :stockholders-equity :cash ...
      :operating-cash-flow :free-cash-flow :capex :dividends-paid ...
      :history [{...} ...]}

   The top-level fields are the most recent period; :history holds the same
   shape for every prior period available, newest first."
  [{:keys [symbol metadata income balance cashflow] :as _payload}]
  (let [periods (merge-period (concat (ds->canonical-rows income)
                                      (ds->canonical-rows balance)
                                      (ds->canonical-rows cashflow)))
        latest  (or (first periods) {})]
    (-> latest
        (assoc :symbol   symbol
               :source   :edgar
               :as-of    (:period-end latest)
               :period   :annual
               :metadata metadata
               :history  (vec (rest periods))))))

(defn- ensure-edgar-init!
  "Call edgar.api/init! exactly once per JVM. Resolution order:
     1. explicit `user-agent` arg
     2. EDGAR_USER_AGENT env var
   Returns true on success, false when no User-Agent is configured."
  ([] (ensure-edgar-init! nil))
  ([user-agent]
   (when-let [ua (or @edgar-init-once!
                     (not-empty user-agent)
                     (some-> (System/getenv "EDGAR_USER_AGENT") not-empty))]
     (when (compare-and-set! edgar-init-once! nil ua)
       (edgar/init! ua))
     true)))

(defprotocol IFundamentalsSource
  "Protocol for fundamental data sources."
  (fetch [this symbol params callback]
    "Fetch fundamental data for symbol. Invokes callback with each result map.
     Returns the allocated req-id (IBKR) or :unavailable (stub).")
  (normalise [this raw-record]
    "Normalise a raw fundamentals record to the canonical map. Returns the normalised map.")
  (supported-symbols [this]
    "Return :all or the set of supported ticker symbols.")
  (supported-periods [this]
    "Return the set of supported period keywords (:annual, :quarterly)."))

(deftype UnavailableFundamentalsSource []
  IFundamentalsSource
  (fetch             [_ _symbol _params _callback] :unavailable)
  (normalise         [_ raw-record]                raw-record)
  (supported-symbols [_]                           :all)
  (supported-periods [_]                           #{:annual :quarterly}))

(def ^:private unavailable-source (UnavailableFundamentalsSource.))

(defn default-source []
  (or (sources/default-source :fundamentals) unavailable-source))

(deftype IbkrFundamentalsSource [ib-client]
  IFundamentalsSource
  (fetch [_ symbol params callback]
    (let [report-type (get params :report-type "ReportSnapshot")
          contract    {:symbol symbol :sec-type :STK}]
      (ibkr/req-fundamentals ib-client contract report-type callback)))
  (normalise         [_ raw] raw)
  (supported-symbols [_]     :all)
  (supported-periods [_]     #{:annual :quarterly}))

(deftype EdgarFundamentalsSource [user-agent]
  IFundamentalsSource
  (fetch [_ symbol params callback]
    (try
      (when-not (ensure-edgar-init! (or (:user-agent params) user-agent))
        (throw (ex-info "EDGAR User-Agent not configured — pass :user-agent or set EDGAR_USER_AGENT"
                        {:symbol symbol})))
      (let [shape (or (:shape params) :wide)]
        (callback [{:type     :fundamental-data
                    :symbol   symbol
                    :source   :edgar
                    :metadata (edgar/company-metadata symbol)
                    :income   (edgar/income   symbol :shape shape)
                    :balance  (edgar/balance  symbol :shape shape)
                    :cashflow (edgar/cashflow symbol :shape shape)}])
        :ok)
      (catch Throwable t
        (log/warnf t "edgar fundamentals failed for %s" symbol)
        (callback [{:type :error :symbol symbol :message (.getMessage t)}])
        :unavailable)))
  (normalise         [_ raw] (if (:income raw) (normalize-edgar raw) raw))
  (supported-symbols [_]     :all)
  (supported-periods [_]     #{:annual :quarterly}))

(deftype DuckDbFundamentalsSource [ds fallback]
  IFundamentalsSource
  (fetch [_ symbol _params callback]
    (let [raw-data (try (qcache/latest-fundamentals ds symbol)
                        (catch Throwable t
                          (log/warnf t "duckdb fundamentals lookup failed for %s" symbol)
                          nil))
          payload  (when raw-data
                     (util/safe-json-parse raw-data))]
      (cond
        payload  (do (callback [(assoc payload :source :duckdb-cache)]) :ok)
        fallback (fetch fallback symbol _params callback)
        :else    (do (callback [{:type :error :symbol symbol
                                 :message "no cached fundamentals; run refresh-fundamentals"}])
                     :unavailable))))
  ;; Cached payload was written post-normalise — pass through unchanged.
  (normalise         [_ raw] raw)
  (supported-symbols [_]     :all)
  (supported-periods [_]     #{:annual :quarterly}))

(defmulti make-source
  "Construct a fundamentals source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  (default-source))

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (IbkrFundamentalsSource. ib-client))

(defmethod make-source :duckdb-cache [{:keys [ds fallback]}]
  (->DuckDbFundamentalsSource ds fallback))

(defmethod make-source :edgar [{:keys [user-agent]}]
  (EdgarFundamentalsSource. user-agent))

(defn fetch-fundamentals
  "Fetch fundamental data for symbol using source and callback.
   Returns the allocated req-id or :unavailable."
  ([symbol]
   (fetch-fundamentals symbol {} (default-source) (fn [_])))
  ([symbol params]
   (fetch-fundamentals symbol params (default-source) (fn [_])))
  ([symbol params source]
   (fetch-fundamentals symbol params source (fn [_])))
  ([symbol params source callback]
   (fetch source symbol params callback)))

(defn normalise-fundamentals
  "Normalise raw-record using source's normaliser. Returns the normalised map."
  ([raw-record]
   (normalise-fundamentals raw-record (default-source)))
  ([raw-record source]
   (normalise source raw-record)))
