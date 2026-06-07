#!/usr/bin/env bb
;; First-run install. Verifies prerequisites, then delegates the actual seeding
;; + DB migration to `clojure -M:run init`, which is the single source of truth
;; for the install steps (see core.clj/init!). Friendlier prereq checks and a
;; clearer post-install summary than `clojure -M:run init` alone.

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn- on-path? [bin]
  (some? (fs/which bin)))

(defn- version-of [bin args]
  (try
    (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} bin args)]
      (when (zero? exit) (str/trim (str out err))))
    (catch Throwable _ nil)))

(defn- java-major []
  (when-let [v (or (version-of "java" ["-version"])
                   (version-of "java" ["--version"]))]
    (when-let [m (re-find #"\b(\d+)(?:\.\d+)*" v)]
      (parse-long (second m)))))

(defn- ok  [s] (str "  [32m✓[0m " s))
(defn- bad [s] (str "  [31m✗[0m " s))
(defn- warn [s] (str "  [33m![0m " s))

(println "\n── options-trader install ─────────────────────────────────────")
(println)
(println "Checking prerequisites:")

(def fatal (atom false))

;; java
(let [m (java-major)]
  (cond
    (nil? m)    (do (println (bad "java not found on PATH")) (reset! fatal true))
    (< m 21)    (do (println (bad (str "java " m " — need 21+"))) (reset! fatal true))
    :else       (println (ok (str "java " m)))))

;; clojure
(if (on-path? "clojure")
  (println (ok "clojure CLI"))
  (do (println (bad "clojure CLI not found on PATH")) (reset! fatal true)))

;; claude (optional but expected)
(if (on-path? "claude")
  (println (ok "claude CLI"))
  (println (warn "claude CLI not found — install it before running the TUI (https://docs.claude.com/claude-code)")))

;; pi (optional)
(if (on-path? "pi")
  (println (ok "pi CLI (optional)"))
  (println (warn "pi CLI not found — optional, only needed if you'll use `/agent pi`")))

(when @fatal
  (println)
  (println "Aborting: fix the issues marked ✗ above and re-run `bb install`.")
  (System/exit 1))

(println)
(println "Seeding per-user config + running DB migrations…")
(println)

(let [{:keys [exit]} (p/shell {:continue true} "clojure" "-M:run" "init")]
  (when-not (zero? exit)
    (println)
    (println "Install failed during `clojure -M:run init` (exit" exit "). See output above.")
    (System/exit exit)))

(println)
(println "── Done ───────────────────────────────────────────────────────")
(println)
(println "Per-user config root:")
(println "  ~/.config/options-trader/         (Linux)")
(println "  ~/Library/Application Support/options-trader/  (macOS)")
(println "  or $OPTIONS_TRADER_CONFIG_DIR if you set it")
(println)
(println "What got seeded (skipped if files already exist):")
(println "  CLAUDE.md                  — your personal overlay; edit to taste")
(println "  indicators.edn             — indicator specs (ta4j kinds + composites)")
(println "  screens/*.screen           — 9 starter strategy screens")
(println "  runtime-claude/")
(println "    settings.json            — registers the options-trader MCP server")
(println "    agents/*.md              — 2 sub-agents (earnings-preview,")
(println "                                  position-risk)")
(println "    skills/*/SKILL.md        — 5 skills (option-chain, filings-research,")
(println "                                  iv-analysis, market-sages,")
(println "                                  options-strategy-advisor)")
(println "  runtime-pi/")
(println "    mcp.json                 — registers the same MCP server for pi")
(println)
(println "Next:")
(println "  1. (optional) export EDGAR_USER_AGENT=\"<your name> <your-email@example.com>\"")
(println "  2. Start IB Gateway or TWS (paper: port 7497, live: 7496)")
(println "  3. bb run-tui            # launch")
(println "     /help                  # see all commands")
