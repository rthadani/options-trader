(ns options-trader.screener.registry
  (:require [next.jdbc :as jdbc]
            [cheshire.core :as json]
            [hawk.core :as hawk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [options-trader.util :refer [as-lower]]
            [options-trader.paths :as paths]
            [options-trader.screener.nl :as nl])
  (:import [java.util UUID]
           [java.security MessageDigest]))

(defmulti  run-source   :source)
(defmethod run-source   :default [{:keys [source]}] {:error :unknown-source :source source})
(defmulti  coerce-criteria :source)
(defmethod coerce-criteria :default [c] c)

(def ^:const default-source :cache)
(def ^:const built-in-sources #{:ibkr :cache :static :edgar})


(defn- sha256-hex [^String s]
  (when (and s (seq s))
    (let [md (MessageDigest/getInstance "SHA-256")
          bs (.digest md (.getBytes s "UTF-8"))]
      (apply str (map #(format "%02x" %) bs)))))


(defn- parse-screen-file
  "Parse YAML front-matter + SQL body from a .screen file string.
   The body may be empty when the screen is description-driven."
  [content]
  (let [s (str/trim content)]
    (if (str/starts-with? s "---")
      (let [after     (subs s 3)
            end-idx   (str/index-of after "\n---")
            fm-text   (if end-idx (subs after 0 end-idx) "")
            sql-body  (if end-idx (str/trim (subs after (+ end-idx 4))) "")
            meta-map  (into {}
                        (for [line  (str/split-lines fm-text)
                              :let  [ci (str/index-of line ":")]
                              :when ci
                              :let  [k (str/trim (subs line 0 ci))
                                     v (str/trim (subs line (inc ci)))]]
                          [(keyword k) v]))]
        (assoc meta-map :dsl sql-body))
      {:dsl s})))

(defn- screen->file-content
  "Render a screen map as a .screen file string."
  [{:keys [name description universe dsl tags]}]
  (str "---\n"
       "name: " (or name "") "\n"
       "description: " (or description "") "\n"
       "universe: " (or universe "latest_indicators") "\n"
       "tags: " (or tags "[]") "\n"
       "---\n"
       (or dsl "") "\n"))

(defn- screen-dir
  "Return the user-writable screens directory (creating it if absent).
   Lives under paths/screens-dir so screens can be edited outside the
   source tree. Seeded from resources/screens/ on init."
  ^java.io.File []
  (let [d (io/file (paths/screens-dir))]
    (.mkdirs d)
    d))

(defn- screen-file
  ^java.io.File [screen-name]
  (io/file (screen-dir) (str (str/replace screen-name #"\s+" "-") ".screen")))


(defn- row->screen [row]
  (when row
    (let [criteria (try
                     (if-let [c (:criteria row)]
                       (json/parse-string (str c) true)
                       {})
                     (catch Exception _ {}))]
      (merge {:id               (:id row)
              :name             (:name row)
              :universe         (:universe row)
              :description-hash (:description_hash row)
              :cached-sql       (:cached_sql row)
              :created-at       (:created_at row)
              :updated-at       (:updated_at row)}
             criteria))))

(def ^:private screen-cols
  "SELECT id, name, universe, criteria, description_hash, cached_sql, created_at, updated_at FROM screens")


(defn list-screens
  "Return a vector of all screen maps from the screens table."
  [ds]
  (->> (jdbc/execute! ds [(str screen-cols " ORDER BY name")]
                      as-lower)
       (mapv row->screen)))

(defn get-screen
  "Return screen map for id-or-name, or nil if not found."
  [ds id-or-name]
  (let [s (str id-or-name)
        row (or (first (jdbc/execute! ds
                         [(str screen-cols " WHERE id = ?") s]
                         as-lower))
                (first (jdbc/execute! ds
                         [(str screen-cols " WHERE name = ?") s]
                         as-lower)))]
    (row->screen row)))

(defn save-screen!
  "Upsert screen to the screens table and write a .screen file.
   screen-map must contain :name and at least one of :dsl or :description.
   Returns the screen id."
  [ds {:keys [name universe dsl description tags] :as screen-map}]
  (let [existing (get-screen ds name)
        id       (or (:id existing) (str (UUID/randomUUID)))
        criteria (json/generate-string {:dsl dsl :description description :tags tags})
        d-hash   (sha256-hex description)
        ;; Invalidate cached SQL when the description has changed.
        cache    (when (and d-hash (= d-hash (:description-hash existing)))
                   (:cached-sql existing))]
    (jdbc/execute! ds
      ["INSERT INTO screens (id, name, universe, criteria, description_hash, cached_sql)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
          name             = excluded.name,
          universe         = excluded.universe,
          criteria         = excluded.criteria,
          description_hash = excluded.description_hash,
          cached_sql       = excluded.cached_sql,
          updated_at       = now()"
       id name (or universe "latest_indicators") criteria d-hash cache])
    (spit (screen-file name) (screen->file-content (assoc screen-map :dsl dsl)))
    id))

(defn- store-cached-sql! [ds screen-id sql]
  (jdbc/execute! ds
    ["UPDATE screens SET cached_sql = ?, updated_at = now() WHERE id = ?"
     sql screen-id]))

(defn delete-screen!
  "Delete screen by id-or-name from DB and remove .screen file."
  [ds id-or-name]
  (when-let [s (get-screen ds id-or-name)]
    (jdbc/execute! ds ["DELETE FROM screens WHERE id = ?" (:id s)])
    (let [f (screen-file (:name s))]
      (when (.exists f) (.delete f))))
  nil)

(defn run-query
  "Execute a raw SQL string against datasource ds.
   Returns {:results [row-maps...]} or {:error msg :results []}."
  [ds query-str]
  (try
    {:results (jdbc/execute! ds [query-str]
                             as-lower)}
    (catch Exception e
      {:error (.getMessage e) :results []})))

(defn run-screen
  "Look up screen by id-or-name and execute it against ds.

   Resolution order:
     1. Inline SQL body (legacy `.screen` files) — runs immediately.
     2. Cached SQL whose description-hash matches the current description.
     3. Generates SQL from the description via the LLM, caches it, then runs.

   When the LLM signals an indicator that is not in the schema, returns
   {:missing col :screen screen-map :results []} so the caller can route to
   the add-indicator action.

   opts may include :model and :sh-fn (forwarded to nl/description->sql)."
  ([ds id-or-name] (run-screen ds id-or-name {}))
  ([ds id-or-name opts]
   (if-let [s (get-screen ds id-or-name)]
     (let [sql-body (some-> s :dsl str/trim)
           descr    (some-> s :description str/trim)
           d-hash   (sha256-hex descr)
           cached   (some-> s :cached-sql str/trim not-empty)]
       (cond
         (seq sql-body)
         (run-query ds sql-body)

         (not (seq descr))
         {:error "screen has neither SQL body nor description" :results []}

         (and cached (= d-hash (:description-hash s)))
         (run-query ds cached)

         :else
         (let [r (nl/description->sql ds descr opts)]
           (cond
             (:sql r)     (do (store-cached-sql! ds (:id s) (:sql r))
                              (run-query ds (:sql r)))
             (:missing r) {:missing (:missing r) :screen s :results []}
             :else        {:error (:error r) :results []}))))
     {:error (str "screen not found: " id-or-name) :results []})))


(defn- load-screen-file!
  "Parse a .screen file and upsert it into the DB.
   Accepts either an inline SQL body or a description-only screen."
  [ds ^java.io.File f]
  (try
    (let [content (slurp f)
          parsed  (parse-screen-file content)
          nm      (or (:name parsed)
                      (str/replace (.getName f) #"\.screen$" ""))]
      (when (or (seq (:dsl parsed)) (seq (:description parsed)))
        (save-screen! ds (assoc parsed :name nm))))
    (catch Exception e
      (println "screen-watcher: error loading" (.getName f) "-" (.getMessage e)))))

(defonce ^:private watcher-state (atom nil))

(defn start-watcher!
  "Watch the screens directory for .screen file changes and upsert to DB.
   Returns the watcher handle."
  [ds]
  (let [dir (screen-dir)
        w   (hawk/watch! [{:paths   [(.getAbsolutePath dir)]
                           :filter  (fn [_ e]
                                      (str/ends-with? (str (:file e)) ".screen"))
                           :handler (fn [ctx e]
                                      (when (#{:create :modify} (:kind e))
                                        (load-screen-file! ds (:file e)))
                                      ctx)}])]
    (reset! watcher-state w)
    w))

(defn stop-watcher!
  "Stop the file watcher."
  ([] (stop-watcher! @watcher-state))
  ([watcher]
   (when watcher
     (hawk/stop! watcher)
     (reset! watcher-state nil))
   nil))

(defn load-screens-from-dir!
  "Scan the screens directory and upsert all .screen files to the DB."
  [ds]
  (let [dir (screen-dir)]
    (doseq [f (.listFiles dir)
            :when (str/ends-with? (.getName f) ".screen")]
      (load-screen-file! ds f))))
