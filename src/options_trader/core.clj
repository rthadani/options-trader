(ns options-trader.core
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [options-trader.config :as config]
            [options-trader.db.duckdb :as db]
            [options-trader.logging :as log]
            [options-trader.tui.main :as tui])
  (:gen-class))

(def ^:private tui-client-id
  ;; The interactive TUI and the CLI/cron refreshers can hold IB API
  ;; connections at the same time; IB rejects a second client that reuses an
  ;; in-use client id. Keep the TUI on its own id, distinct from the CLI's
  ;; (config :ibkr :client-id, default 1).
  7)

(def cli-options
  [[nil  "--headless"        "Run without TUI"]
   [nil  "--check-config"    "Validate resources/config.edn and exit 0/1"]
   ["-p" "--profile PROFILE" "Config profile" :default "dev"]
   ["-h" "--help"            "Show help"]])

(defn- init! [cfg]
  (println "Initializing Options Trader...")
  (.mkdirs (io/file "cache/logs"))
  (.mkdirs (io/file "cache/backups"))
  (.mkdirs (io/file "db/migrations"))
  (db/bootstrap! cfg)
  (println "Database initialized at" (get-in cfg [:db :path]))
  (println "\nNext steps:")
  (println "  1. Copy .envrc.example to .envrc and fill in API keys")
  (println "  2. Run: bb repl  (start the nREPL)")
  (println "  3. Run: clojure -M:run --headless"))

(defn -main [& args]
  (log/setup!)
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options :in-order true)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (:help options)
      (println summary)
      (System/exit 0))
    (when (:check-config options)
      (try
        (config/load-config (:profile options))
        (println "Config OK")
        (System/exit 0)
        (catch Throwable t
          (println "Config INVALID —" (.getMessage t))
          (System/exit 1))))
    (let [cfg (config/load-config (:profile options))]
      (when (= (first arguments) "init")
        (init! cfg)
        (System/exit 0))
      (when (:headless options)
        (println "Headless mode — use clojure -M:cli <subcommand>")
        (System/exit 0))
      (try
        (db/bootstrap! cfg)
        (catch Throwable t
          (log/error t "database migration failed")
          (println "ERROR: database migration failed —" (.getMessage t))
          (println "The database at" (get-in cfg [:db :path])
                   "could not be migrated; refusing to start on an unmigrated schema.")
          (System/exit 1)))
      (let [ds (db/datasource cfg)]
        (tui/start! {:ds              ds
                     :ibkr-config     (assoc (:ibkr cfg) :client-id tui-client-id)
                     :initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."})))))
