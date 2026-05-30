#!/usr/bin/env bb
(require '[babashka.process :as p])
(require '[clojure.string :as str])
(require '[clojure.tools.cli :as cli])

(let [args *command-line-args*
      opts (cli/parse-opts args
             [["-u" "--universe NAME"    "Universes" :default "sp500"]
              [nil  "--profile PROFILE"  "Config profile" :default "dev"]
              [nil  "--bars TYPE"        "Bars: daily, intraday, both"]
              [nil  "--no-filings"]
              [nil  "--no-news"]
              [nil  "--no-fundamentals"]
              [nil  "--no-universes"]
              [nil  "--no-portfolio"]
              ["-h" "--help"]])]
  (when (:errors opts)
    (doseq [e (:errors opts)] (println e))
    (System/exit 1))

  (let [options (:options opts)
        profile (:profile options)
        universe (:universe options)
        bars (or (:bars options) "daily")
        help? (or (= "-h" (first args)) (= "--help" (first args)))]

    (if help?
      (do
        (println "refresh-data - Refresh market data for IBKR symbols")
        (println)
        (println "Usage: bb refresh-data [options]")
        (println)
        (println "Options:")
        (println (:summary opts))
        (println)
        (println "Examples:")
        (println "  bb refresh-data                        # daily for SP500")
        (println "  bb refresh-data --universe nasdaq100")
        (println "  bb refresh-data --bars both")
        (System/exit 0))

      (do
        (println "=== refreshing data for" universe "===")

        (when-not (:no-portfolio options)
          (println ":: portfolio...")
          (p/sh "clojure" "-M:cli" "refresh-portfolio" "--profile" profile "--universe" universe {:inherit true}))

        (when (str/includes? bars "daily")
          (println ":: daily bars...")
          (p/sh "clojure" "-M:cli" "refresh-daily" "--profile" profile "--universe" universe {:inherit true}))

        (when (str/includes? bars "intraday")
          (println ":: intraday bars...")
          (p/sh "clojure" "-M:cli" "refresh-intraday" "--profile" profile "--universe" universe {:inherit true}))

        (when-not (:no-news options)
          (println ":: news...")
          (p/sh "clojure" "-M:cli" "refresh-news" "--profile" profile "--universe" universe {:inherit true}))

        (when-not (:no-fundamentals options)
          (println ":: fundamentals...")
          (p/sh "clojure" "-M:cli" "refresh-fundamentals" "--profile" profile {:inherit true}))

        (when-not (:no-filings options)
          (println ":: filings...")
          (p/sh "clojure" "-M:cli" "refresh-filings" "--profile" profile "--universe" universe {:inherit true}))

        (when-not (:no-universes options)
          (println ":: universes...")
          (p/sh "clojure" "-M:cli" "refresh-universes" "--profile" profile {:inherit true}))

        (println "=== done ====")))))