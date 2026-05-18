(ns options-trader.tui.runtime-context
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def base-marker    "<!-- base-layer -->")
(def overlay-marker "<!-- user-overlay -->")
(def dynamic-marker "<!-- dynamic -->")

(defn- load-base []
  (some-> (io/resource "runtime/CLAUDE.md") slurp))

(defn- load-user-overlay [config-dir]
  (when config-dir
    (let [f (io/file config-dir "runtime" "CLAUDE.md")]
      (when (.exists f) (slurp f)))))

(defn build
  "Return a context string concatenating up to three layers.
   Opts:
     :config-dir    — directory from which to load a user overlay (optional)
     :overlay       — inline overlay string, takes precedence over :config-dir
     :dynamic-block — per-spawn content appended as the third layer"
  [{:keys [config-dir overlay dynamic-block]
    :or   {dynamic-block ""}}]
  (let [base    (or (load-base) "")
        overlay (or overlay (load-user-overlay config-dir))
        parts   (cond-> [(str base-marker "\n\n" base)]
                  overlay (conj (str overlay-marker "\n\n" overlay))
                  true    (conj (str dynamic-marker "\n\n" dynamic-block)))]
    (str/join "\n\n" parts)))
