(ns options-trader.core
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.logging :as log]
            [options-trader.tui.main :as tui])
  (:gen-class))

(def ^:private default-cfg
  {:db   {:path "cache/options_trader.duckdb"}
   :ibkr {:host "127.0.0.1" :port 7497 :client-id 7}})

(def cli-options
  [[nil  "--headless"     "Run without TUI"]
   [nil  "--check-config" "Validate configuration and exit 0"]
   ["-h" "--help"         "Show help"]])

(defonce ^:private db-conn (atom nil))

(defn- close-db! []
  (when-let [conn @db-conn]
    (log/info "shutting down")
    (.close conn)
    (reset! db-conn nil)))

(defn- apply-migrations! [conn migrations-dir]
  (let [dir   (io/file migrations-dir)
        files (when (.isDirectory dir)
                (->> (file-seq dir)
                     (filter #(and (.isFile %) (str/ends-with? (.getName %) ".sql")))
                     (sort-by #(.getName %))))]
    (doseq [f (or files [])]
      (jdbc/execute! conn [(slurp f)]))))

(defn- init! []
  (println "Initializing Options Trader...")
  (.mkdirs (io/file "cache/logs"))
  (.mkdirs (io/file "cache/backups"))
  (.mkdirs (io/file "db/migrations"))
  (let [db-path "cache/options_trader.duckdb"
        conn    (java.sql.DriverManager/getConnection (str "jdbc:duckdb:" db-path))]
    (reset! db-conn conn)
    (apply-migrations! conn "db/migrations")
    (println "Database initialized at" db-path))
  (println "\nNext steps:")
  (println "  1. Copy .envrc.example to .envrc and fill in API keys")
  (println "  2. Run: bb repl  (start the nREPL)")
  (println "  3. Run: clojure -M:run --headless"))

(defn -main [& args]
  (log/setup!)
  (.addShutdownHook (Runtime/getRuntime)
    (Thread. #(close-db!)))
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options :in-order true)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (:help options)
      (println summary)
      (System/exit 0))
    (when (:check-config options)
      (println "Config OK")
      (System/exit 0))
    (when (= (first arguments) "init")
      (init!)
      (System/exit 0))
    (when (:headless options)
      (println "Headless mode — use clojure -M:cli <subcommand>")
      (System/exit 0))
    (try (db/bootstrap! default-cfg) (catch Throwable _))
    (let [ds (db/datasource default-cfg)]
      (tui/start! {:ds              ds
                   :ibkr-config     (:ibkr default-cfg)
                   :initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}))))
