#!/usr/bin/env bb
;; Launch the TUI. Resolves the per-user config root, shows the user which
;; one is in use, hints `bb install` if it's empty, then runs `clojure -M:run`.

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn- mac? []
  (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(defn- config-root []
  (or (some-> (System/getenv "OPTIONS_TRADER_CONFIG_DIR") not-empty)
      (let [home (System/getProperty "user.home")]
        (if (mac?)
          (str home "/Library/Application Support/options-trader")
          (let [xdg (System/getenv "XDG_CONFIG_HOME")]
            (str (if (str/blank? xdg) (str home "/.config") xdg)
                 "/options-trader"))))))

(let [root      (config-root)
      seeded?   (fs/exists? (str root "/runtime-claude/settings.json"))
      override? (some? (System/getenv "OPTIONS_TRADER_CONFIG_DIR"))]
  (println)
  (println (str "\u001b[36m" "Using config:" "\u001b[0m" root
                (when override? (str "\u001b[33m" "(from $OPTIONS_TRADER_CONFIG_DIR)" "\u001b[0m"))))
  (when-not seeded?
    (println)
    (println (str "\u001b[33m" "Config directory not initialized." "\u001b[0m" "Run `bb install` first."))
    (System/exit 1))
  (println)
  (let [argv (vec *command-line-args*)
        {:keys [exit]} (apply p/shell {:continue true} "clojure" "-M:run" argv)]
    (System/exit exit)))
