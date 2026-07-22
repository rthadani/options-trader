(ns options-trader.logging
  "Timbre logging configuration. Call setup! once from -main before any logging."
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.java.io :as io]))

(def log-file "cache/logs/options-trader.log")

(defn setup!
  "Wire timbre to write to cache/logs/options-trader.log.
   opts: :console? (default true) — set false in stdio subprocesses like the
   MCP server where writing to stdout/stderr would corrupt the protocol."
  ([] (setup! {}))
  ([{:keys [console?] :or {console? true}}]
   (.mkdirs (io/file "cache/logs"))
   (timbre/merge-config!
     {:appenders
      (cond-> {:spit (appenders/spit-appender {:fname log-file})}
        (not console?) (assoc :println {:enabled? false}))})))

(defmacro info  [& args] `(timbre/info  ~@args))
(defmacro warn  [& args] `(timbre/warn  ~@args))
(defmacro error [& args] `(timbre/error ~@args))
