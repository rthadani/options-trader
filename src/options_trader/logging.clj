(ns options-trader.logging
  "Timbre logging configuration. Call setup! once from -main before any logging."
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.java.io :as io]))

(def log-file "cache/logs/options-trader.log")

(defn setup!
  "Wire timbre to write to cache/logs/options-trader.log in addition to stdout."
  []
  (.mkdirs (io/file "cache/logs"))
  (timbre/merge-config!
    {:appenders
     {:spit (appenders/spit-appender {:fname log-file})}}))

(defmacro info  [& args] `(timbre/info  ~@args))
(defmacro warn  [& args] `(timbre/warn  ~@args))
(defmacro error [& args] `(timbre/error ~@args))
