(ns options-trader.mcp.tools
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.actions.core :as actions]
            ;; Registers research tool defmethods via actions/handle-action.
            [options-trader.actions.research]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.screener.registry :as screener]))

(def ^:private schema-dir "mcp/tools")

(defn- load-schema [tool-name]
  (when-let [res (io/resource (str schema-dir "/" tool-name ".json"))]
    (json/parse-string (slurp res) true)))

(def ^:private tool-names
  ["portfolio_summary" "list_screens" "run_screen" "run_sql"
   "get_indicators" "place_order" "cancel_order"
   ;; Read-only research tools for single-instrument deep dives.
   "fetch_news" "fetch_filings" "fetch_filing_body" "fetch_filing_item"
   "fetch_xbrl_facts" "fetch_corporate_actions" "fetch_fundamentals"
   "fetch_earnings_history" "fetch_short_interest"
   "fetch_option_chain" "fetch_option_quote"])

(defn load-registry
  "Load all tool schemas from resources/mcp/tools/*.json.
   Returns a map of tool-name-string -> schema-map."
  []
  (reduce (fn [acc nm]
            (if-let [schema (load-schema nm)]
              (assoc acc nm schema)
              acc))
          {}
          tool-names))

(defn list-tools
  "Return a vector of {:name s :description s :inputSchema m} maps."
  [registry]
  (mapv (fn [[_ schema]]
          {:name        (:name schema)
           :description (:description schema)
           :inputSchema (:inputSchema schema)})
        registry))

(defn- orders-disabled []
  {:error "orders_disabled"
   :message "Order execution is disabled. Set :allow-orders? true in config to enable."})

(defn- no-ds-error [defaults]
  (merge {:error "no datasource"} defaults))

;;; ── Tool handler registry ───────────────────────────────────────────────────

(defn- handle-list-screens [ds _args]
  (if ds {:screens (screener/list-screens ds)} {:screens []}))

(defn- handle-run-screen [ds args]
  (if ds
    (screener/run-screen ds (:screen_id args))
    (no-ds-error {:results []})))

(defn- handle-run-sql [ds args]
  (if ds
    (screener/run-query ds (:query args))
    (no-ds-error {:results []})))

(defn- handle-get-indicators [ds args]
  (if ds
    (let [syms (vec (:symbols args []))]
      (if (seq syms)
        (let [ph   (str/join "," (repeat (count syms) "?"))
              rows (jdbc/execute! ds
                     (into [(str "SELECT * FROM latest_indicators WHERE symbol IN (" ph ")")]
                           syms)
                     {:builder-fn rs/as-unqualified-lower-maps})]
          {:symbols syms :indicators rows})
        {:error "symbols list is empty" :indicators []}))
    (no-ds-error {:indicators []})))

(defn- handle-portfolio-summary [ds account-id args]
  (let [acct  (or (:account_id args) account-id)
        store (when ds (portfolio/->JdbcStore ds))]
    {:positions       (when store (portfolio/read-positions store acct))
     :account-summary (when store (portfolio/read-account-summary store acct))
     :account-id      acct}))

(defn- handle-place-order [allow-orders? args]
  (if allow-orders?
    (actions/handle-action {:type :place-order
                            :symbol    (:symbol args)
                            :action    (:action args)
                            :quantity  (:quantity args)
                            :order-type (:order_type args)
                            :limit-price (:limit_price args)})
    (orders-disabled)))

(defn- handle-cancel-order [allow-orders? args]
  (if allow-orders?
    (actions/handle-action {:type :cancel-order
                            :order-id (:order_id args)})
    (orders-disabled)))

;;; ── Research tool dispatch ──────────────────────────────────────────────────

(def ^:private research-tool-specs
  "Spec for each research tool: the action type, arg mappings, and ctx-source key."
  {"fetch_news"              {:type   :research/fetch-news
                               :sym?   true :include ["headlines" "sentiment"]
                               :params [:limit :start_date :end_date]
                               :source :news-source}
   "fetch_filings"           {:type   :research/fetch-filings
                               :sym?   true
                               :params [:form_type :start_date :end_date]
                               :source :edgar-source}
   "fetch_filing_body"       {:type   :research/fetch-filing-body
                               :keys   {:accession identity :format #(keyword (or % "text"))}
                               :source :edgar-source}
   "fetch_filing_item"       {:type   :research/fetch-filing-item
                               :keys   {:accession identity :item_id :item_id}
                               :source :edgar-source}
   "fetch_xbrl_facts"        {:type   :research/fetch-xbrl-facts
                               :keys   {:cik identity :taxonomy #(keyword (or % "us-gaap"))}}
   "fetch_corporate_actions" {:type   :research/fetch-corporate-actions
                               :sym?   true :include ["all"]
                               :source :events-source}
   "fetch_fundamentals"      {:type   :research/fetch-fundamentals
                               :sym?   true
                               :params [:report]
                               :source :fundamentals-source}
   "fetch_earnings_history"  {:type   :research/fetch-earnings-history
                               :sym?   true
                               :params [:limit]
                               :source :earnings-source}
   "fetch_short_interest"    {:type   :research/fetch-short-interest
                               :sym?   true :include ["history" "borrow"]
                               :source :short-interest-source}
   "fetch_option_chain"      {:type   :research/fetch-option-chain
                               :keys   {:symbol identity :expiry_prefix :expiry_prefix}
                               :source :options-source}
   "fetch_option_quote"      {:type   :research/fetch-option-quote
                               :keys   {:ib-client :ib-client, :symbol identity
                                        :strike identity, :expiry identity, :right identity
                                        :exchange identity, :currency identity
                                        :tick_types :tick_types}
                               :source :options-source}})

(defn- build-research-request
  "Given a research-tool-spec entry, arguments, and ctx, build the
   actions/handle-action request map."
  [spec args ctx]
  (let [base {:type (:type spec)}
        base (if (:sym? spec)
               (assoc base :symbol (:symbol args))
               base)
        base (if-let [include-default (:include spec)]
               (assoc base :include (mapv keyword (:include args include-default)))
               base)
        base (if (:params spec)
               (assoc base :params (select-keys args (:params spec)))
               base)
        base (if-let [key-spec (:keys spec)]
               (reduce-kv (fn [m arg-kw accessor]
                            (let [v (if (fn? accessor)
                                      (accessor (get args arg-kw))
                                      (get args accessor))]
                              (if (some? v)
                                (assoc m arg-kw v)
                                m)))
                          base key-spec)
               base)
        base (if-let [src-key (:source spec)]
               (if-let [src-val (get ctx src-key)]
                 (assoc base :source src-val)
                 base)
               base)]
    base))

(defn- dispatch-research-tool
  "Build and invoke the research action for the given tool."
  [tool-name args ctx]
  (if-let [spec (get research-tool-specs tool-name)]
    (actions/handle-action (build-research-request spec args ctx))
    {:error (str "unknown research tool: " tool-name)}))

;;; ── Main dispatch ───────────────────────────────────────────────────────────

(def ^:private db-tool-handlers
  {"list_screens"  #'handle-list-screens
   "run_screen"    #'handle-run-screen
   "run_sql"       #'handle-run-sql
   "get_indicators" #'handle-get-indicators})

(defn call-tool
  "Dispatch a tool call by name. ctx map may contain :ds :account-id :allow-orders?.
   Returns a result map."
  [tool-name arguments ctx]
  (let [{:keys [ds account-id allow-orders?]
         :or   {allow-orders? false}} ctx]
    (cond
      (= "portfolio_summary" tool-name)
      (handle-portfolio-summary ds account-id arguments)

      (= "place_order" tool-name)
      (handle-place-order allow-orders? arguments)

      (= "cancel_order" tool-name)
      (handle-cancel-order allow-orders? arguments)

      (contains? db-tool-handlers tool-name)
      ((get db-tool-handlers tool-name) ds arguments)

      (contains? research-tool-specs tool-name)
      (dispatch-research-tool tool-name arguments ctx)

      :else
      {:error (str "unknown tool: " tool-name)})))
