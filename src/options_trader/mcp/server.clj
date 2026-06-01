(ns options-trader.mcp.server
  (:require [options-trader.config       :as config]
            [options-trader.data.edgar   :as edgar]
            [options-trader.db.duckdb    :as duckdb]
            [options-trader.mcp.protocol :as protocol]
            [options-trader.mcp.tools    :as tools])
  (:gen-class))

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

(defn- apply-edgar-source! [cfg]
  (when-let [edgar-cfg (get-in cfg [:data-sources :edgar])]
    (edgar/set-default-source! (edgar/make-source edgar-cfg))))

(defn -main [& _args]
  (let [profile (or (System/getenv "OPTIONS_TRADER_PROFILE") "dev")
        cfg     (try (config/load-config profile)
                     (catch Throwable t
                       (binding [*out* *err*]
                         (println "warning: failed to load config —" (.getMessage t)))
                       nil))]
    (when cfg
      (apply-edgar-source! cfg))
    (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
          wtr (java.io.PrintWriter. System/out true)
          ds  (when cfg
                (try (duckdb/datasource cfg)
                     (catch Throwable t
                       (binding [*out* *err*]
                         (println "warning: failed to open DB —" (.getMessage t)))
                       nil)))
          ctx (cond-> {}
                ds (assoc :ds ds))]
      (run-stdio-server rdr wtr ctx))))
