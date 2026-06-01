(ns options-trader.tui.runtime-context
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [options-trader.paths :as paths]))

(def base-marker    "<!-- base-layer -->")
(def overlay-marker "<!-- user-overlay -->")
(def dynamic-marker "<!-- dynamic -->")

(defn- load-base []
  (some-> (io/resource "runtime/CLAUDE.md") slurp))

(defn- load-user-overlay
  "Read the user-editable overlay layer. Resolution order:
   1. explicit :overlay path passed in
   2. paths/runtime-claude-overlay-md (default <config-root>/CLAUDE.md)
   3. legacy: <config-dir>/runtime/CLAUDE.md (kept for tests)
   Returns the file contents or nil."
  [config-dir]
  (let [user-md (io/file (paths/runtime-claude-overlay-md))
        legacy  (when config-dir (io/file config-dir "runtime" "CLAUDE.md"))]
    (cond
      (.exists user-md) (slurp user-md)
      (and legacy (.exists legacy)) (slurp legacy))))

(defn build
  "Return a context string concatenating up to three layers.
   Opts:
     :config-dir    — directory from which to load a user overlay (legacy)
     :overlay       — inline overlay string, takes precedence over file lookup
     :dynamic-block — per-spawn content appended as the third layer"
  [{:keys [config-dir overlay dynamic-block]
    :or   {dynamic-block ""}}]
  (let [base    (or (load-base) "")
        overlay (or overlay (load-user-overlay config-dir))
        parts   (cond-> [(str base-marker "\n\n" base)]
                  overlay (conj (str overlay-marker "\n\n" overlay))
                  true    (conj (str dynamic-marker "\n\n" dynamic-block)))]
    (str/join "\n\n" parts)))
