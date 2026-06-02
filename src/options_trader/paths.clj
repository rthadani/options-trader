(ns options-trader.paths
  "Per-user config & data paths. Resolves a single root for everything
   options-trader owns outside the source tree, so the TUI agents (claude,
   pi) can be isolated from the host machine's ~/.claude and ~/.pi.

   Override with OPTIONS_TRADER_CONFIG_DIR. Otherwise:
     Linux:  $XDG_CONFIG_HOME/options-trader  (default ~/.config/options-trader)
     macOS:  ~/Library/Application Support/options-trader
     other:  ~/.options-trader"
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]))

(defn- mac? []
  (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(def ^:dynamic *config-root-override*
  "Bind in tests to point config-root at a temp dir without setting env vars."
  nil)

(defn config-root
  "Absolute path of the per-user options-trader config root. Does not
   create the directory — use ensure-dir!.
   Resolution: *config-root-override* > OPTIONS_TRADER_CONFIG_DIR env >
   OS default (XDG on Linux, App Support on macOS)."
  []
  (or *config-root-override*
      (some-> (System/getenv "OPTIONS_TRADER_CONFIG_DIR") not-empty)
      (let [home (System/getProperty "user.home")]
        (cond
          (mac?) (str home "/Library/Application Support/options-trader")
          :else  (let [xdg (System/getenv "XDG_CONFIG_HOME")]
                   (str (if (str/blank? xdg) (str home "/.config") xdg)
                        "/options-trader"))))))

(defn runtime-claude-dir
  "Config root for the TUI's claude subprocess. Points CLAUDE_CONFIG_DIR
   here so the agent is fully isolated from ~/.claude."
  []
  (str (config-root) "/runtime-claude"))

(defn runtime-claude-overlay-md
  "User-editable overlay layer for the agent's system prompt. Loaded by
   tui.runtime-context/build between the packaged base prompt and the
   per-spawn dynamic block. Seeded once from
   resources/runtime/claude/CLAUDE.md.example on init."
  []
  (str (config-root) "/CLAUDE.md"))

(defn runtime-claude-agents-dir
  "Where claude looks for sub-agents (auto-discovered when CLAUDE_CONFIG_DIR
   is set). One markdown file per agent with a YAML front-matter block."
  []
  (str (runtime-claude-dir) "/agents"))

(defn runtime-claude-skills-dir
  "Where claude looks for skills (auto-discovered when CLAUDE_CONFIG_DIR
   is set). One subdirectory per skill with a SKILL.md file. Also passed
   to pi via --skill <path> for each subdirectory."
  []
  (str (runtime-claude-dir) "/skills"))

(defn runtime-pi-dir
  "Config root for the TUI's pi subprocess. Holds the MCP config and the
   per-session storage, isolating from ~/.pi."
  []
  (str (config-root) "/runtime-pi"))

(defn pi-mcp-file
  "Path to the pi MCP config file (passed as --mcp-config). Seeded from
   resources/runtime/pi/mcp.json on init."
  []
  (str (runtime-pi-dir) "/mcp.json"))

(defn pi-session-dir
  "Where pi stores its session JSONL files (passed as --session-dir)."
  []
  (str (runtime-pi-dir) "/sessions"))

(defn screens-dir
  "User-writable directory for .screen files. The TUI watches it and
   upserts changes to the screens table. Seeded from resources/screens/
   on init."
  []
  (str (config-root) "/screens"))

(defn indicators-file
  "User-writable indicators.edn — the SOURCE OF TRUTH for indicator
   columns + percentile windows once seeded. Seeded from the packaged
   resources/indicators.edn on init."
  []
  (str (config-root) "/indicators.edn"))

(defn input-history-file
  "TUI input history persisted as an EDN vector of strings — restored on
   startup so ↑/↓ recall survives restarts. Capped to a sliding window."
  []
  (str (config-root) "/input-history.edn"))

(defn tui-prefs-file
  "TUI preferences (agent / model / provider) persisted as EDN so the next
   TUI launch starts in the same configuration as the previous session."
  []
  (str (config-root) "/tui-prefs.edn"))

(defn ensure-dir!
  "Create path (and parents) if missing. Returns the absolute path."
  [^String path]
  (let [f (io/file path)]
    (.mkdirs f)
    (.getAbsolutePath f)))
