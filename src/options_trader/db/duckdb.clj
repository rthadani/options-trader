(ns options-trader.db.duckdb
  "DuckDB connection pool and migration runner.
   Exposes datasource, bootstrap!, and applied-migrations."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defmulti coerce-row
  "Coerce a raw JDBC row map to the domain representation.
   Dispatch value is :table-key."
  :table-key)

(defmethod coerce-row :default [{:keys [row]}] row)

(defmulti build-query
  "Build a HoneySQL query map for the named query-key and params."
  :query-key)

(defmethod build-query :default [spec]
  {:error :unknown-query-key :query-key (:query-key spec)})

;;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const schema-version
  "Current DuckDB schema version. Increment when applying new migrations."
  1)

(def ^:const db-path
  "Default path for the DuckDB database file."
  "cache/options_trader.duckdb")

(def as-lower {:builder-fn rs/as-unqualified-lower-maps})

;;; ── Internal helpers ─────────────────────────────────────────────────────────

(defn- jdbc-url [{:keys [db]}]
  (let [path (get db :path db-path)]
    (if (= path ":memory:")
      "jdbc:duckdb:"
      (str "jdbc:duckdb:" path))))

(defn- split-statements [sql]
  (->> (str/split sql #";")
       (map str/trim)
       (remove str/blank?)))

(defn- migration-version [filename]
  (-> (re-find #"^(\d+)_" filename)
      (nth 1)
      (Integer/parseInt)))

(defn- migration-name [filename]
  (str/replace filename #"\.sql$" ""))

(defn- migration-files []
  (let [dir (io/file "db/migrations")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".sql"))
           (sort-by #(.getName %))
           vec))))

;;; ── Public API ───────────────────────────────────────────────────────────────

(defn datasource
  "Return a next.jdbc datasource backed by org.duckdb.DuckDBDriver.
   cfg must contain {:db {:path <file-path>}}; use \":memory:\" for ephemeral DBs."
  [cfg]
  (Class/forName "org.duckdb.DuckDBDriver")
  (jdbc/get-datasource {:jdbcUrl (jdbc-url cfg)}))

(defn applied-migrations
  "Query _schema_migrations and return a set of applied version integers."
  [ds]
  (->> (jdbc/execute! ds
         ["SELECT version FROM _schema_migrations ORDER BY version"]
         as-lower)
       (map :version)
       set))

(defn bootstrap!
  "Create _schema_migrations if absent, then apply every unapplied
   db/migrations/<n>_<name>.sql in ascending version order.
   Idempotent: re-running against an already-migrated DB is a no-op."
  [cfg]
  (let [ds (datasource cfg)]
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS _schema_migrations (
          version    INT PRIMARY KEY,
          name       TEXT NOT NULL,
          applied_at TIMESTAMP DEFAULT current_timestamp
        )"])
    (let [applied (applied-migrations ds)
          files   (migration-files)]
      (doseq [f files]
        (let [fname   (.getName f)
              version (migration-version fname)
              mname   (migration-name fname)]
          (when-not (contains? applied version)
            (jdbc/with-transaction [tx ds]
              (doseq [stmt (split-statements (slurp f))]
                (jdbc/execute! tx [stmt]))
              (jdbc/execute! tx
                ["INSERT INTO _schema_migrations (version, name) VALUES (?, ?)"
                 version mname]))))))))
