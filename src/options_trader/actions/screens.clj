(ns options-trader.actions.screens
  "Action handler for persisting a screener definition to the DB and to a
   .screen file under paths/screens-dir. Confirm-gated to match the
   :add-indicator / :place-order contract."
  (:require [clojure.string :as str]
            [options-trader.actions.core :as actions]
            [options-trader.paths :as paths]
            [options-trader.screener.registry :as registry]))

(defn- blank? [s] (or (nil? s) (str/blank? (str s))))

(defn- screen-file-path [nm]
  (str (paths/screens-dir) "/"
       (str/replace (str nm) #"\s+" "-") ".screen"))

(defn- build-preview [{:keys [name description sql universe tags]} existing]
  {:preview {:name        name
             :universe    (or universe "latest_indicators")
             :description description
             :sql         sql
             :tags        (or tags "[]")
             :existing?   (boolean existing)
             :file        (screen-file-path name)}})

(defn- write! [ds {:keys [name description sql universe tags]}]
  (let [id (registry/save-screen! ds
             {:name        name
              :description description
              :dsl         sql
              :universe    (or universe "latest_indicators")
              :tags        (or tags "[]")})]
    {:saved? true
     :id     id
     :name   name
     :file   (screen-file-path name)}))

(defmethod actions/handle-action :save-screen
  [{:keys [ds name description sql overwrite? confirm?] :as action}]
  (cond
    (nil? ds)
    {:error :no-datasource :action (dissoc action :ds)}

    (blank? name)
    {:error :name-required}

    (and (blank? description) (blank? sql))
    {:error :body-required
     :message "Provide either :description (NL body) or :sql (inline body)."}

    :else
    (let [existing (registry/get-screen ds name)]
      (cond
        (and existing (not overwrite?) (not confirm?))
        (assoc (build-preview action existing)
               :warning :name-exists
               :message (str "A screen named " (pr-str name) " already exists. "
                             "Pass overwrite? true (or overwrite: true from MCP) "
                             "to replace it."))

        (and existing (not overwrite?))
        {:error :name-exists
         :message (str "Refusing to overwrite existing screen " (pr-str name)
                       " — pass overwrite? true to replace.")}

        (not confirm?)
        (assoc (build-preview action existing)
               :message (str "Will "
                             (if existing "REPLACE " "create ")
                             "screen " (pr-str name)
                             ". Re-call with confirm? true to write."))

        :else
        (write! ds action)))))
