(ns options-trader.mcp.tools
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.actions.core :as actions]
            ;; Registers research / orders / screens defmethods via actions/handle-action.
            [options-trader.actions.research]
            [options-trader.actions.orders]
            [options-trader.actions.screens]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.db.queries.indicators :as qi]
            [options-trader.db.queries.portfolio :as qp]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.screener.registry :as screener]))

(def ^:private schema-dir "mcp/tools")

(defn- load-schema [tool-name]
  (when-let [res (io/resource (str schema-dir "/" tool-name ".json"))]
    (json/parse-string (slurp res) true)))

(def ^:private tool-names
  ["portfolio_summary" "list_screens" "run_screen" "save_screen" "run_sql"
   "get_indicators" "place_order" "cancel_order"
   ;; Read-only research tools for single-instrument deep dives.
   "fetch_news" "fetch_filings" "fetch_filing_body" "fetch_filing_item"
   "fetch_xbrl_facts" "fetch_corporate_actions" "fetch_fundamentals"
   "fetch_earnings_history" "fetch_short_interest"
   "fetch_option_chain" "fetch_option_quote" "fetch_detailed_quote"])

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

(defn- handle-save-screen [ds args]
  (actions/handle-action
    {:type        :save-screen
     :ds          ds
     :name        (:name args)
     :description (:description args)
     :sql         (:sql args)
     :universe    (:universe args)
     :tags        (:tags args)
     :overwrite?  (boolean (:overwrite args))
     :confirm?    (boolean (:confirm args))}))

(defn- handle-get-indicators [ds args]
  (if ds
    (let [syms (vec (:symbols args []))]
      (if (seq syms)
        {:symbols syms :indicators (qi/select-latest-indicators ds syms)}
        {:error "symbols list is empty" :indicators []}))
    (no-ds-error {:indicators []})))

(defn- read-db-portfolio
  "Plain DB-cache read; nil when ds is absent."
  [ds acct]
  (when ds
    (let [store (portfolio/->JdbcStore ds)]
      {:positions       (portfolio/read-positions       store acct)
       :account-summary (portfolio/read-account-summary store acct)})))

(defn- live-portfolio
  "Live IB read; nil on failure. Writes through to DuckDB via JdbcStore so the
   next DB read picks up the freshest data."
  [ds ib-client acct]
  (when (and ds ib-client acct)
    (let [src (portfolio/make-source
                {:portfolio {:source :ibkr :account-id acct}}
                ib-client ds)]
      (try
        {:positions       (portfolio/positions       src)
         :account-summary (portfolio/account-summary src)}
        (catch Throwable t
          {:error :live-read-failed :message (.getMessage t)})))))

(defn- empty-result? [{:keys [positions account-summary]}]
  (and (or (nil? positions) (and (sequential? positions) (empty? positions)))
       (nil? account-summary)))

(defn- handle-portfolio-summary
  "Resolve current portfolio state for the agent. Tries live IB first, then
   falls back to the DB cache when the live read returns empty (which happens
   when the MCP server's IB session is up but events stop flowing, or the
   cache simply has fresher data). Returns explicit :source so the agent
   knows where the numbers came from."
  [ds account-id ib-client args]
  ;; Resolution order mirrors the TUI's default-account-id so the agent picks
  ;; up the same account the TUI's portfolio panel is showing.
  (let [acct (or (:account_id args)
                 account-id
                 (when ib-client (ibkr/default-account))
                 (when ds        (qp/default-account-from-positions ds))
                 (when ds        (qp/default-account-from-summary   ds)))
        live (live-portfolio ds ib-client acct)
        db   (read-db-portfolio ds acct)]
    (cond
      (and live (not (:error live)) (not (empty-result? live)))
      (merge {:source :live :account-id acct} live)

      (and db (not (empty-result? db)))
      (merge {:source     :db
              :account-id acct
              :live-error (when live (:error live))
              :live-note  (when (and live (empty-result? live))
                            "live read returned empty; serving cached data")}
             db)

      ds
      {:source          :db
       :account-id      acct
       :positions       []
       :account-summary nil
       :error           :no-cached-data
       :message         (str "DuckDB has no positions or account_summary "
                             "rows for account " (pr-str acct) ". Run "
                             "`clojure -M:cli refresh-portfolio` to seed "
                             "the warehouse, or start TWS and restart the "
                             "agent so MCP gets a live IB connection.")}

      :else
      {:source          :none
       :account-id      acct
       :positions       []
       :account-summary nil
       :error           :no-datasource
       :message         "MCP ctx has neither :ds nor :ib-client — cannot return portfolio."})))

(defn- handle-place-order [allow-orders? order-source args]
  (actions/handle-action
    {:type          :place-order
     :allow-orders? allow-orders?
     :order-source  order-source
     :side          (or (:side args) (:action args))
     :symbol        (:symbol args)
     :sec-type      (:sec_type args)
     :exchange      (:exchange args)
     :currency      (:currency args)
     :quantity      (:quantity args)
     :order-type    (:order_type args)
     :limit-price   (:limit_price args)
     :tif           (:tif args)
     :outside-rth?  (boolean (:outside_rth args))
     :combo-legs    (:combo_legs args)
     :confirm?      (boolean (:confirm args))}))

(defn- handle-cancel-order [allow-orders? order-source args]
  (actions/handle-action {:type          :cancel-order
                          :allow-orders? allow-orders?
                          :order-source  order-source
                          :order-id      (:order_id args)
                          :confirm?      (boolean (:confirm args))}))


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
                               :source :options-source}
  "fetch_quote"              {:type   :research/fetch-quote
                               :keys   {:ib-client :ib-client, :symbol identity }
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


(def ^:private db-tool-handlers
  {"list_screens"  #'handle-list-screens
   "run_screen"    #'handle-run-screen
   "save_screen"   #'handle-save-screen
   "run_sql"       #'handle-run-sql
   "get_indicators" #'handle-get-indicators})

(defn call-tool
  "Dispatch a tool call by name. ctx map may contain :ds :account-id :allow-orders?.
   Returns a result map."
  [tool-name arguments ctx]
  (let [{:keys [ds account-id ib-client order-source allow-orders?]
         :or   {allow-orders? false}} ctx]
    (cond
      (= "portfolio_summary" tool-name)
      (handle-portfolio-summary ds account-id ib-client arguments)

      (= "place_order" tool-name)
      (handle-place-order allow-orders? order-source arguments)

      (= "cancel_order" tool-name)
      (handle-cancel-order allow-orders? order-source arguments)

      (contains? db-tool-handlers tool-name)
      ((get db-tool-handlers tool-name) ds arguments)

      (contains? research-tool-specs tool-name)
      (dispatch-research-tool tool-name arguments ctx)

      :else
      {:error (str "unknown tool: " tool-name)})))
