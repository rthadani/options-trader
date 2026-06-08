(ns options-trader.core
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [options-trader.config :as config]
            [options-trader.data.edgar :as edgar]
            [options-trader.db.duckdb :as db]
            [options-trader.logging :as log]
            [options-trader.paths :as paths]
            [options-trader.tui.main :as tui]
            [options-trader.util :as util])
  (:gen-class))

(def ^:private tui-client-id
  ;; The interactive TUI and the CLI/cron refreshers can hold IB API
  ;; connections at the same time; IB rejects a second client that reuses an
  ;; in-use client id. Keep the TUI on its own id, distinct from the CLI's
  ;; (config :ibkr :client-id, default 1).
  ;;
  ;; Override with TUI_CLIENT_ID=<n> if 7 collides with another tool you run.
  (or (some-> (System/getenv "TUI_CLIENT_ID") not-empty parse-long) 7))

(def cli-options
  [[nil  "--headless"        "Run without TUI"]
   [nil  "--check-config"    "Validate resources/config.edn and exit 0/1"]
   ["-p" "--profile PROFILE" "Config profile" :default "dev"]
   [nil  "--compact-threshold N"
         "Auto-compact when scope input-tokens exceed N (default 100000)"
         :parse-fn parse-long
         :validate [pos? "must be a positive integer"]]
   ["-h" "--help"            "Show help"]])

(defn- seed-from-resource!
  "Copy resource-path to target-file iff target doesn't exist. Returns the
   target path. Used to seed packaged runtime config into the per-user
   config root, without overwriting user edits."
  [resource-path ^java.io.File target-file]
  (.mkdirs (.getParentFile target-file))
  (when-not (.exists target-file)
    (when-let [src (io/resource resource-path)]
      (with-open [in (io/input-stream src)]
        (io/copy in target-file))))
  (.getAbsolutePath target-file))

(defn- seed-manifest!
  "Read a manifest.edn from `resource-prefix/manifest.edn`, then for each
   entry seed `<resource-prefix>/<relative-path>` to `<target-dir>/<relative-path>`.
   `relative-path-fn` converts a manifest entry (a string) to the file/dir
   path under both prefix and target. Returns the list of entries seeded."
  [resource-prefix ^java.io.File target-dir relative-path-fn]
  (when-let [manifest-res (io/resource (str resource-prefix "/manifest.edn"))]
    (let [names (util/safe-edn-read (slurp manifest-res))]
      (doseq [nm names
              :let [rel (relative-path-fn nm)]]
        (seed-from-resource! (str resource-prefix "/" rel)
                             (io/file target-dir rel)))
      names)))

(defn- link-claude-credentials!
  "Symlink the user's existing claude credentials into runtime-claude so the
   spawned agent inherits the Pro/Max plan login. Without this, every spawn
   prompts /login because CLAUDE_CONFIG_DIR points away from ~/.claude.

   Uses a symlink (not a copy) so token refreshes performed by the user's
   own `claude` CLI propagate automatically. Skips silently if the user
   isn't logged in yet (no ~/.claude/.credentials.json) — the user can
   run /login in their own claude shell once, then restart the TUI."
  [runtime-dir]
  (let [src (io/file (System/getProperty "user.home") ".claude" ".credentials.json")
        dst (io/file runtime-dir ".credentials.json")]
    (when (and (.exists src) (not (.exists dst)))
      (try
        (java.nio.file.Files/createSymbolicLink
          (.toPath dst) (.toPath src)
          (make-array java.nio.file.attribute.FileAttribute 0))
        (catch Throwable _
          (try (io/copy src dst) (catch Throwable _ nil)))))
    dst))

(defn- seed-runtime-claude!
  "Seed the TUI's claude config dir from packaged resources, so the agent
   gets an isolated, opinionated starting point on first run:
   - settings.json     — MCP server + permission allowlist
   - agents/*.md       — sub-agent definitions (see manifest)
   - skills/*/SKILL.md — skill definitions (see manifest)
   - .credentials.json — symlinked from ~/.claude so Pro/Max login carries over
   The user-overlay CLAUDE.md (separate concern) lives at config-root, not
   inside runtime-claude — see seed-overlay-md!."
  []
  (let [dir (paths/ensure-dir! (paths/runtime-claude-dir))]
    (seed-from-resource! "runtime/claude/settings.json"
                         (io/file dir "settings.json"))
    (paths/ensure-dir! (paths/runtime-claude-agents-dir))
    (seed-manifest! "runtime/claude/agents"
                    (io/file (paths/runtime-claude-agents-dir))
                    #(str % ".md"))
    (paths/ensure-dir! (paths/runtime-claude-skills-dir))
    (let [skills-dir  (io/file (paths/runtime-claude-skills-dir))
          skill-names (seed-manifest! "runtime/claude/skills" skills-dir
                                      #(str % "/SKILL.md"))]
      (doseq [nm skill-names
              :let [files-res (io/resource (str "runtime/claude/skills/" nm "/files.edn"))]
              :when files-res
              rel (util/safe-edn-read (slurp files-res))]
        (seed-from-resource! (str "runtime/claude/skills/" nm "/" rel)
                             (io/file skills-dir nm rel))))
    (link-claude-credentials! dir)
    dir))

(defn- seed-overlay-md!
  "Seed <config-root>/CLAUDE.md from the .example resource on first run.
   The TUI's runtime-context loader picks it up as the overlay layer
   between the packaged base prompt and the per-spawn dynamic block."
  []
  (let [target (io/file (paths/runtime-claude-overlay-md))]
    (when-not (.exists target)
      (when-let [src (io/resource "runtime/claude/CLAUDE.md.example")]
        (.mkdirs (.getParentFile target))
        (with-open [in (io/input-stream src)]
          (io/copy in target))))
    (.getAbsolutePath target)))

(defn- seed-runtime-pi!
  "Seed <config-root>/runtime-pi/mcp.json from the packaged resource and
   ensure the sessions dir exists, so the TUI's pi subprocess can be
   spawned with --mcp-config and --session-dir pointing into our config."
  []
  (let [dir (paths/ensure-dir! (paths/runtime-pi-dir))]
    (paths/ensure-dir! (paths/pi-session-dir))
    (seed-from-resource! "runtime/pi/mcp.json"
                         (io/file dir "mcp.json"))
    dir))

(defn- seed-packaged-screens!
  "Seed <config-root>/screens/<name>.screen from resources/screens/<name>.screen
   for every entry in resources/screens/manifest.edn. Never overwrites: a user
   may freely edit any seeded file, and their edits survive upgrades."
  []
  (let [dir (paths/ensure-dir! (paths/screens-dir))]
    (when-let [manifest-res (io/resource "screens/manifest.edn")]
      (let [names (util/safe-edn-read (slurp manifest-res))]
        (doseq [nm names
                :let [fname (str nm ".screen")]]
          (seed-from-resource! (str "screens/" fname)
                               (io/file dir fname)))))
    dir))

(defn- seed-indicators!
  "Seed <config-root>/indicators.edn from the packaged resource. Never
   overwrites — once the user copy exists, every reader (engine, screener
   NL, action handler) consumes it, and propose/add-indicator writes back
   to it."
  []
  (seed-from-resource! "indicators.edn"
                       (io/file (paths/indicators-file))))

(defn- init! [cfg]
  (println "Initializing Options Trader...")
  (.mkdirs (io/file "cache/logs"))
  (.mkdirs (io/file "cache/backups"))
  (.mkdirs (io/file "db/migrations"))
  (paths/ensure-dir! (paths/config-root))
  (println "Runtime claude config seeded at" (seed-runtime-claude!))
  (println "Runtime pi config seeded at" (seed-runtime-pi!))
  (println "User overlay seeded at" (seed-overlay-md!))
  (println "Screens seeded at" (seed-packaged-screens!))
  (println "Indicators seeded at" (seed-indicators!))
  (db/bootstrap! cfg)
  (println "Database initialized at" (get-in cfg [:db :path]))
  (println "\nNext steps:")
  (println "  1. Copy .envrc.example to .envrc and fill in API keys")
  (println "  2. Run: bb repl  (start the nREPL)")
  (println "  3. Run: clojure -M:run --headless"))

(defn -main [& args]
  (log/setup!)
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options :in-order true)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (:help options)
      (println summary)
      (System/exit 0))
    (when (:check-config options)
      (try
        (config/load-config (:profile options))
        (println "Config OK")
        (System/exit 0)
        (catch Throwable t
          (println "Config INVALID —" (.getMessage t))
          (System/exit 1))))
    (let [cfg (config/load-config (:profile options))]
      (when (= (first arguments) "init")
        (init! cfg)
        (System/exit 0))
      (when (:headless options)
        (println "Headless mode — use clojure -M:cli <subcommand>")
        (System/exit 0))
      (seed-runtime-claude!)
      (seed-runtime-pi!)
      (seed-overlay-md!)
      (seed-packaged-screens!)
      (seed-indicators!)
      (edgar/apply-edgar-source! cfg)
      (try
        (db/bootstrap! cfg)
        (catch Throwable t
          (log/error t "database migration failed")
          (println "ERROR: database migration failed —" (.getMessage t))
          (println "The database at" (get-in cfg [:db :path])
                   "could not be migrated; refusing to start on an unmigrated schema.")
          (System/exit 1)))
      (let [ds (db/datasource cfg)]
        (tui/start! (cond-> {:ds              ds
                             :profile         (:profile options)
                             :ibkr-config     (assoc (:ibkr cfg) :client-id tui-client-id)
                             :initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}
                      (:compact-threshold options)
                      (assoc :compact-threshold (:compact-threshold options))))))))
