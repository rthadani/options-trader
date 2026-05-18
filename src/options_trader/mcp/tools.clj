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

(defn call-tool
  "Dispatch a tool call by name. ctx map may contain :ds :account-id :allow-orders?.
   Returns a result map."
  [tool-name arguments ctx]
  (let [{:keys [ds account-id allow-orders?]
         :or   {allow-orders? false}} ctx]
    (case tool-name
      "portfolio_summary"
      (let [acct (or (:account_id arguments) account-id "DU123456")
            src  (portfolio/->MockSource)]
        {:positions       (portfolio/positions src)
         :account-summary (portfolio/account-summary src)
         :account-id      acct})

      "list_screens"
      (if ds
        {:screens (screener/list-screens ds)}
        {:screens []})

      "run_screen"
      (if ds
        (screener/run-screen ds (:screen_id arguments))
        {:error "no datasource" :results []})

      "run_sql"
      (if ds
        (screener/run-query ds (:query arguments))
        {:error "no datasource" :results []})

      "get_indicators"
      (if ds
        (let [syms (vec (:symbols arguments []))]
          (if (seq syms)
            (let [ph   (str/join "," (repeat (count syms) "?"))
                  rows (jdbc/execute! ds
                         (into [(str "SELECT * FROM latest_indicators WHERE symbol IN (" ph ")")]
                               syms)
                         {:builder-fn rs/as-unqualified-lower-maps})]
              {:symbols syms :indicators rows})
            {:error "symbols list is empty" :indicators []}))
        {:error "no datasource" :indicators []})

      "place_order"
      (if allow-orders?
        (actions/handle-action {:type :place-order
                                :symbol    (:symbol arguments)
                                :action    (:action arguments)
                                :quantity  (:quantity arguments)
                                :order-type (:order_type arguments)
                                :limit-price (:limit_price arguments)})
        (orders-disabled))

      "cancel_order"
      (if allow-orders?
        (actions/handle-action {:type :cancel-order
                                :order-id (:order_id arguments)})
        (orders-disabled))

      "fetch_news"
      (actions/handle-action
        {:type    :research/fetch-news
         :symbol  (:symbol arguments)
         :include (mapv keyword (:include arguments ["headlines" "sentiment"]))
         :params  (select-keys arguments [:limit :start_date :end_date])
         :source  (:news-source ctx)})

      "fetch_filings"
      (actions/handle-action
        {:type   :research/fetch-filings
         :symbol (:symbol arguments)
         :params (select-keys arguments [:form_type :start_date :end_date])
         :source (:edgar-source ctx)})

      "fetch_filing_body"
      (actions/handle-action
        {:type      :research/fetch-filing-body
         :accession (:accession arguments)
         :format    (keyword (:format arguments "text"))
         :source    (:edgar-source ctx)})

      "fetch_filing_item"
      (actions/handle-action
        {:type      :research/fetch-filing-item
         :accession (:accession arguments)
         :item-id   (:item_id arguments)
         :source    (:edgar-source ctx)})

      "fetch_xbrl_facts"
      (actions/handle-action
        {:type     :research/fetch-xbrl-facts
         :cik      (:cik arguments)
         :taxonomy (keyword (:taxonomy arguments "us-gaap"))})

      "fetch_corporate_actions"
      (actions/handle-action
        {:type    :research/fetch-corporate-actions
         :symbol  (:symbol arguments)
         :include (mapv keyword (:include arguments ["all"]))
         :source  (:events-source ctx)})

      "fetch_fundamentals"
      (actions/handle-action
        {:type   :research/fetch-fundamentals
         :symbol (:symbol arguments)
         :params (select-keys arguments [:report])
         :source (:fundamentals-source ctx)})

      "fetch_earnings_history"
      (actions/handle-action
        {:type   :research/fetch-earnings-history
         :symbol (:symbol arguments)
         :params (select-keys arguments [:limit])
         :source (:earnings-source ctx)})

      "fetch_short_interest"
      (actions/handle-action
        {:type    :research/fetch-short-interest
         :symbol  (:symbol arguments)
         :include (mapv keyword (:include arguments ["history" "borrow"]))
         :source  (:short-interest-source ctx)})

      "fetch_option_chain"
      (actions/handle-action
        {:type          :research/fetch-option-chain
         :symbol        (:symbol arguments)
         :expiry-prefix (:expiry_prefix arguments)
         :source        (:options-source ctx)})

      "fetch_option_quote"
      (actions/handle-action
        {:type       :research/fetch-option-quote
         :ib-client  (:ib-client ctx)
         :symbol     (:symbol arguments)
         :strike     (:strike arguments)
         :expiry     (:expiry arguments)
         :right      (:right arguments)
         :exchange   (:exchange arguments)
         :currency   (:currency arguments)
         :tick-types (:tick_types arguments)})

      {:error (str "unknown tool: " tool-name)})))
