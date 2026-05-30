(ns options-trader.config
  "Single source of truth for resources/config.edn, used by both the TUI
   (core) and the headless CLI so they always resolve the same settings."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn load-config
  "Read resources/config.edn and return the settings for `profile`.
   The file is a map of profile -> settings ({:dev {...} :prod {...}}), so the
   profile is selected by key here — aero's :profile option only drives the
   #profile reader tag, which this file does not use."
  [profile]
  (let [all (aero/read-config (io/resource "config.edn"))]
    (or (get all (keyword profile))
        (throw (ex-info (str "unknown config profile: " (pr-str profile))
                        {:profile profile :available (vec (keys all))})))))
