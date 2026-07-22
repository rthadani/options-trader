(ns options-trader.mcp.server
  (:require [options-trader.config           :as config]
            [options-trader.data.earnings    :as earnings]
            [options-trader.data.edgar       :as edgar]
            [options-trader.data.fundamentals :as fundamentals]
            [options-trader.data.ibkr        :as ibkr]
            [options-trader.data.market-data :as md]
            [options-trader.data.news        :as news]
            [options-trader.data.options     :as options]
            [options-trader.data.orders      :as orders]
            [options-trader.data.short-interest :as short-interest]
            [options-trader.data.sources     :as sources]
            [options-trader.db.duckdb        :as duckdb]
            [options-trader.logging          :as logging]
            [options-trader.mcp.protocol     :as protocol]
            [options-trader.mcp.tools        :as tools])
  (:gen-class))

(def ^:private mcp-client-id
  "Distinct from the TUI (7) and the CLI (1) so the MCP server can hold its
   own TWS connection without collision. Override with MCP_CLIENT_ID."
  (or (some-> (System/getenv "MCP_CLIENT_ID") not-empty parse-long) 9))

(defmulti tool-call :tool-name)
(defmethod tool-call :default [{:keys [tool-name]}]
  {:error :unknown-tool :tool-name tool-name})

(defmulti tool-schema identity)
(defmethod tool-schema :default [tool-name]
  {:error :no-schema-registered :tool-name tool-name})

(def ^:const server-version "0.1.0")

(def ^:const tool-names
  #{:portfolio-summary :list-screens :run-screen
    :get-indicators :place-order :cancel-order})

(defn- make-initialize-result []
  {:protocolVersion "2024-11-05"
   :serverInfo      {:name "options-trader-mcp" :version server-version}
   :capabilities    {:tools {}}})

(defn- handle-request
  "Route a single JSON-RPC request through the tool registry."
  [registry ctx {:keys [method params]}]
  (case method
    "initialize"
    (make-initialize-result)

    "tools/list"
    {:tools (tools/list-tools registry)}

    "tools/call"
    (let [tool-name  (:name params)
          arguments  (or (:arguments params) {})]
      (if (string? tool-name)
        {:content [{:type "text"
                    :text (pr-str (tools/call-tool tool-name arguments ctx))}]}
        {:isError true :content [{:type "text" :text "missing tool name"}]}))

    {:error (str "method not found: " method)}))

(defn run-stdio-server
  "Start the MCP stdio loop reading from rdr and writing to wtr.
   ctx map may contain :ds :account-id :allow-orders?"
  [^java.io.BufferedReader rdr ^java.io.PrintWriter wtr ctx]
  (let [registry (tools/load-registry)
        handler  (partial handle-request registry ctx)]
    (protocol/stdio-loop rdr wtr handler)))

(defn- open-ib! [cfg]
  (when-let [{:keys [host port]} (:ibkr cfg)]
    (try
      (let [c (ibkr/connect! host port mcp-client-id)]
        (when (not= :unavailable c) c))
      (catch Throwable t
        (binding [*out* *err*]
          (println "warning: MCP failed to open IB connection —" (.getMessage t)))
        nil))))

(defn -main [& _args]
  (logging/setup! {:console? false})
  (let [profile (or (System/getenv "OPTIONS_TRADER_PROFILE") "prod")
        cfg     (try (config/load-config profile)
                     (catch Throwable t
                       (binding [*out* *err*]
                         (println "warning: failed to load config —" (.getMessage t)))
                       nil))]
    (when cfg
      (edgar/apply-edgar-source! cfg))
    (let [rdr       (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
          wtr       (java.io.PrintWriter. System/out true)
          ds        (when cfg
                      (try (duckdb/datasource cfg)
                           (catch Throwable t
                             (binding [*out* *err*]
                               (println "warning: failed to open DB —" (.getMessage t)))
                             nil)))
          ;; Own TWS connection (default client-id 9) so IB-backed tools
          ;; work. The TUI uses client-id 7; the two coexist.
          ib-client (when cfg (open-ib! cfg))]
      (when ds
        (let [edgar-cfg      (some-> cfg (get-in [:data-sources :edgar]))
              edgar-live     (when edgar-cfg (edgar/make-source edgar-cfg))
              ibkr-news-live (when ib-client
                               (news/make-source {:type :ibkr :ib-client ib-client}))]
          (sources/install-all!
            {:fundamentals   (fundamentals/make-source
                               {:type :duckdb-cache :ds ds
                                :fallback (when edgar-cfg
                                            (fundamentals/make-source edgar-cfg))})
             :edgar          (edgar/make-source
                               {:type :duckdb-cache :ds ds :fallback edgar-live})
             :news           (news/make-source
                               {:type :duckdb-cache :ds ds :fallback ibkr-news-live})
             :earnings       (earnings/make-source
                               {:type :duckdb-cache :ds ds})
             :short-interest (short-interest/make-source
                               {:type :duckdb-cache :ds ds})})))
      (let [ctx (cond-> {:options-source (if ib-client
                                           (options/make-source {:type :ibkr :ib-client ib-client})
                                           (options/make-source {:type :yahoo}))
                         :md-source      (if ib-client
                                           (md/make-source {:type :ibkr :ib-client ib-client})
                                           (md/make-source {:type :unavailable}))
                         :order-source   (orders/make-source
                                           {:type :ibkr :ib-client ib-client})}
                  ds        (assoc :ds ds)
                  ib-client (assoc :ib-client ib-client))]
        (run-stdio-server rdr wtr ctx)))))
