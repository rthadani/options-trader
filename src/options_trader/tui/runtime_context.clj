(ns options-trader.tui.runtime-context
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [options-trader.paths :as paths]))

(def dynamic-marker "<!-- dynamic -->")

(defn- load-prompt
  "Load the runtime system prompt. Resolution order:
   1. explicit :overlay string passed in
   2. <config-root>/CLAUDE.md  — the user's editable copy
   3. legacy: <config-dir>/runtime/CLAUDE.md
   4. packaged resources/runtime/CLAUDE.md (classpath fallback)
   The user file replaces the packaged base entirely — there is no merging."
  [config-dir inline-override]
  (let [user-md (io/file (paths/runtime-claude-overlay-md))
        legacy  (when config-dir (io/file config-dir "runtime" "CLAUDE.md"))]
    (or inline-override
        (when (.exists user-md)  (slurp user-md))
        (when (and legacy (.exists legacy)) (slurp legacy))
        (some-> (io/resource "runtime/CLAUDE.md") slurp)
        "")))

(defn build
  "Assemble the runtime system prompt.
   Opts:
     :config-dir    — legacy fallback directory for prompt lookup
     :overlay       — inline prompt string, takes precedence over file lookup
     :dynamic-block — per-spawn content appended after the main prompt"
  [{:keys [config-dir overlay dynamic-block]
    :or   {dynamic-block ""}}]
  (let [prompt (load-prompt config-dir overlay)]
    (str/join "\n\n" [prompt
                      (str dynamic-marker "\n\n" dynamic-block)])))
