(ns options-trader.util
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn safe-json-parse
  [s]
  (try (json/parse-string s true)
       (catch Exception _ nil)))

(defn safe-edn-read
  [s]
  (try (edn/read-string s)
       (catch Exception _ nil)))

(defn read-edn-resource
  [path]
  (edn/read-string (slurp (io/resource path))))

(defn safe-spit
  [path content]
  (try
    (io/make-parents path)
    (spit path content)
    (catch Exception _ nil)))

(def as-lower {:builder-fn rs/as-unqualified-lower-maps})

(defn query-scalar
  [ds sqlvec kw]
  (some-> (jdbc/execute-one! ds sqlvec as-lower) kw))
