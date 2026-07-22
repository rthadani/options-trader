(ns options-trader.cli
  (:require [clojure.set                     :as set]
            [clojure.string                  :as str]
            [clojure.tools.cli               :as tools-cli]
            [options-trader.actions.core     :as actions]
            [options-trader.config           :as config]
            [options-trader.data.edgar       :as edgar]
            [options-trader.data.ibkr        :as ibkr]
            [options-trader.data.orders      :as orders]
            [options-trader.data.universes   :as universes]
            [options-trader.db.duckdb        :as duckdb]
            [options-trader.db.refresh       :as refresh]
            [options-trader.portfolio.core   :as portfolio]
            [options-trader.screener.registry :as screener])
  (:gen-class))

(def ^:private cli-options
  [["-h" "--help" "Show usage"]
   ["-p" "--profile PROFILE" "Config profile" :default "prod"]
   ["-u" "--universe NAME"   "Universe (sp500/nasdaq100/<user-file>...)" :default "sp500"]
   ["-s" "--symbols CSV"     "Comma-separated symbols (overrides --universe)"]
   ["-b" "--bar-size SIZE"   "Intraday bar size, e.g. \"15 mins\"" :default "15 mins"]
   ["-a" "--account ACCOUNT" "IBKR account id (auto-detected from TWS handshake if omitted)"]
   [nil  "--parallelism N"   "Concurrent IB requests during refresh (default 16)"
    :parse-fn parse-long :validate [#(<= 1 % 32) "must be 1..32"]]
   [nil  "--only PHASES"      "refresh-all: comma-separated phases to run (overrides --bars and --no-*). Names: portfolio, daily, intraday, news, fundamentals, filings, universes"]
   [nil  "--bars KIND"        "refresh-all: which bars to fetch — daily|intraday|both|none"
    :default "daily" :validate [#{"daily" "intraday" "both" "none"} "must be daily|intraday|both|none"]]
   [nil  "--no-portfolio"     "refresh-all: skip portfolio phase"     :default false]
   [nil  "--no-news"          "refresh-all: skip news phase"          :default false]
   [nil  "--no-fundamentals"  "refresh-all: skip fundamentals phase"  :default false]
   [nil  "--no-filings"       "refresh-all: skip filings phase"       :default false]
   [nil  "--no-universes"     "refresh-all: skip universes phase"     :default false]
   [nil  "--no-indicators"    "refresh-daily: skip derived-indicator recompute (bars only)" :default false]
   [nil  "--allow-orders"     "Enable order execution"                :default false]])

(defn- print-usage [summary]
  (println "Usage: options-trader <subcommand> [options]")
  (println)
  (println "Subcommands:")
  (println "  refresh-all          Run every refresh phase in one JVM, one IB connection")
  (println "  refresh-daily        Bars (daily) + indicator recompute")
  (println "  refresh-intraday     Bars (intraday, --bar-size '15 mins')")
  (println "  refresh-news         News headlines + sentiment")
  (println "  refresh-fundamentals EDGAR-backed fundamentals + ratios")
  (println "  refresh-filings      EDGAR filing list per symbol")
  (println "  refresh-iv-daily     IB daily IV30 + HV30 series → iv_daily")
  (println "  refresh-short-interest  Yahoo short-interest snapshot → short_interest")
  (println "  refresh-earnings     Yahoo earnings history + calendar")
  (println "  refresh-universes    Re-fetch universe membership; log drift")
  (println "  refresh-portfolio    Positions + account summary")
  (println "  ping                 IB + DB connectivity smoke test")
  (println)
  (println "  portfolio            Show current portfolio")
  (println "  screen <name>        Run a saved screen")
  (println "  order BUY|SELL SYM N Place an order (requires --allow-orders)")
  (println)
  (println "Options:")
  (println summary))


(defn- open-ds! [cfg]
  (duckdb/bootstrap! cfg)
  (duckdb/datasource cfg))

(defn- open-ib! [cfg]
  (let [{:keys [host port client-id]} (:ibkr cfg)
        c (ibkr/connect! host port (or client-id 1))]
    (when (= :unavailable c)
      (throw (ex-info "TWS unavailable — is IB Gateway/TWS running?"
                      {:host host :port port :client-id client-id})))
    c))

(defn- resolve-symbols [opts ds]
  (cond
    (:symbols opts)
    (mapv str/trim (str/split (:symbols opts) #","))

    (:universe opts)
    (let [u (str/lower-case (:universe opts))]
      (or (seq (universes/ticker-fetch (keyword u)))
          (throw (ex-info (str "no symbols found for universe " u) {:universe u}))))

    :else
    (throw (ex-info "must supply --symbols or --universe" {}))))


(defmulti run-subcommand (fn [cmd _opts _args] cmd))

(defn- ds-of [opts]
  (or (:ds opts) (open-ds! (config/load-config (:profile opts)))))

(defn- conn-of [opts]
  (or (:conn opts) (open-ib! (config/load-config (:profile opts)))))

(defmethod run-subcommand :refresh-daily [_ opts _]
  (let [ds      (ds-of opts)
        conn    (conn-of opts)
        symbols (resolve-symbols opts ds)]
    (try
      (refresh/refresh-bars-daily!
        {:conn conn :ds ds :symbols symbols
         :compute-indicators? (not (:no-indicators opts))})
      (finally (when-not (:conn opts) (ibkr/disconnect!))))))

(defmethod run-subcommand :refresh-intraday [_ opts _]
  (let [ds      (ds-of opts)
        conn    (conn-of opts)
        symbols (resolve-symbols opts ds)]
    (try
      (refresh/refresh-bars-intraday!
        {:conn conn :ds ds :symbols symbols :bar-size (:bar-size opts)})
      (finally (when-not (:conn opts) (ibkr/disconnect!))))))

(defmethod run-subcommand :refresh-news [_ opts _]
  (let [ds      (ds-of opts)
        conn    (conn-of opts)
        symbols (resolve-symbols opts ds)]
    (try
      (refresh/refresh-news! {:conn conn :ds ds :symbols symbols})
      (finally (when-not (:conn opts) (ibkr/disconnect!))))))

(defmethod run-subcommand :refresh-fundamentals [_ opts _]
  (let [ds   (ds-of opts)
        syms (cond
               (:symbols opts)  (mapv str/trim (str/split (:symbols opts) #","))
               (:universe opts) (resolve-symbols opts ds)
               :else            nil)]
    (refresh/refresh-fundamentals! (cond-> {:ds ds} syms (assoc :symbols syms)))))

(defmethod run-subcommand :refresh-filings [_ opts _]
  (let [ds      (ds-of opts)
        symbols (resolve-symbols opts ds)]
    (refresh/refresh-filings! {:ds ds :symbols symbols})))

(defmethod run-subcommand :refresh-iv-daily [_ opts _]
  (let [cfg     (when-not (and (:ibkr-config opts) (:conn opts) (:ds opts))
                  (config/load-config (:profile opts)))
        ds      (or (:ds opts) (open-ds! cfg))
        conn    (or (:conn opts) (open-ib! cfg))
        symbols (resolve-symbols opts ds)
        ibkr    (or (:ibkr-config opts) (:ibkr cfg))]
    (try
      (refresh/refresh-iv-daily! {:conn        conn
                                  :ds          ds
                                  :symbols     symbols
                                  :ibkr-config ibkr})
      (finally (when-not (:conn opts) (try (ibkr/disconnect!) (catch Throwable _)))))))

(defmethod run-subcommand :refresh-short-interest [_ opts _]
  (let [ds      (ds-of opts)
        symbols (resolve-symbols opts ds)]
    (refresh/refresh-short-interest! {:ds ds :symbols symbols})))

(defmethod run-subcommand :refresh-earnings [_ opts _]
  (let [ds      (ds-of opts)
        symbols (resolve-symbols opts ds)]
    (refresh/refresh-earnings! {:ds ds :symbols symbols})))

(defmethod run-subcommand :refresh-universes [_ opts _]
  (refresh/refresh-universes! {:ds (ds-of opts)}))

(defmethod run-subcommand :refresh-portfolio [_ opts _]
  (let [ds      (ds-of opts)
        conn    (conn-of opts)
        account (or (:account opts) (ibkr/default-account))]
    (when (nil? account)
      (throw (ex-info "no account-id; TWS handshake didn't supply one — pass --account explicitly" {})))
    (try
      (refresh/refresh-portfolio! {:conn conn :ds ds :account-id account})
      (finally (when-not (:conn opts) (ibkr/disconnect!))))))

(def ^:private valid-phases
  #{"portfolio" "daily" "intraday" "news" "fundamentals" "filings"
    "iv-daily" "short-interest" "earnings" "universes"})

(defmethod run-subcommand :refresh-all [_ opts args]
  ;; Positional universe wins over --universe when no --symbols was given.
  ;; `bb refresh-data my-watchlist --profile prod` should refresh my-watchlist,
  ;; not the default sp500.
  (let [opts         (cond-> opts
                       (and (first args) (not (:symbols opts)))
                       (assoc :universe (first args)))
        cfg          (config/load-config (:profile opts))
        ds           (or (:ds opts) (open-ds! cfg))
        only-set     (when (:only opts)
                       (into #{} (map str/trim) (str/split (:only opts) #",")))
        unknown      (when only-set (set/difference only-set valid-phases))
        _            (when (seq unknown)
                       (throw (ex-info (str "unknown phase(s) in --only: "
                                            (str/join ", " unknown)
                                            ". Valid: " (str/join ", " (sort valid-phases)))
                                       {:unknown unknown :valid valid-phases})))
        bars-kind    (or (:bars opts) "daily")
        in-only?     (fn [phase] (contains? only-set phase))
        do-portfolio? (if only-set (in-only? "portfolio")      (not (:no-portfolio opts)))
        do-daily?     (if only-set (in-only? "daily")          (#{"daily" "both"}    bars-kind))
        do-intraday?  (if only-set (in-only? "intraday")       (#{"intraday" "both"} bars-kind))
        do-news?      (if only-set (in-only? "news")           (not (:no-news opts)))
        do-fund?      (if only-set (in-only? "fundamentals")   (not (:no-fundamentals opts)))
        do-filings?   (if only-set (in-only? "filings")        (not (:no-filings opts)))
        do-ivdaily?   (if only-set (in-only? "iv-daily")       (not (:no-iv-daily opts)))
        do-short?     (if only-set (in-only? "short-interest") (not (:no-short-interest opts)))
        do-earnings?  (if only-set (in-only? "earnings")       (not (:no-earnings opts)))
        do-univ?      (if only-set (in-only? "universes")      (not (:no-universes opts)))
        needs-ib?     (or do-portfolio? do-daily? do-intraday? do-news? do-ivdaily?)
        run-start     (System/currentTimeMillis)
        conn          (when needs-ib? (open-ib! cfg))
        opts'         (cond-> (assoc opts :ds ds :ibkr-config (:ibkr cfg))
                        conn (assoc :conn conn))
        run-phase!    (fn [label subcmd]
                        (let [t0 (System/currentTimeMillis)]
                          (try
                            (let [r (run-subcommand subcmd opts' nil)]
                              {:phase label :status :ok
                               :elapsed-ms (- (System/currentTimeMillis) t0)
                               :result r})
                            (catch Throwable t
                              (binding [*out* *err*]
                                (println (format "phase %s failed: %s" label (.getMessage t))))
                              {:phase label :status :error
                               :elapsed-ms (- (System/currentTimeMillis) t0)
                               :error (.getMessage t)})
                            (finally
                              (System/gc)))))]
    (println (format "refresh-all starting (ib=%s, bars=%s, universe=%s)"
                     (boolean conn) bars-kind (or (:universe opts) (:symbols opts) "?")))
    (let [results (try
                    (cond-> []
                      do-portfolio? (conj (run-phase! :portfolio       :refresh-portfolio))
                      do-daily?     (conj (run-phase! :bars-daily      :refresh-daily))
                      do-intraday?  (conj (run-phase! :bars-intraday   :refresh-intraday))
                      do-news?      (conj (run-phase! :news            :refresh-news))
                      do-fund?      (conj (run-phase! :fundamentals    :refresh-fundamentals))
                      do-filings?   (conj (run-phase! :filings         :refresh-filings))
                      do-short?     (conj (run-phase! :short-interest  :refresh-short-interest))
                      do-earnings?  (conj (run-phase! :earnings        :refresh-earnings))
                      do-univ?      (conj (run-phase! :universes       :refresh-universes))
                      ;; iv-daily is gated by IB's 60-per-10-min historical window and is the
                      ;; phase most likely to need a re-run after partial failures. Keep it
                      ;; last so `refresh-iv-daily` alone covers any retry.
                      do-ivdaily?   (conj (run-phase! :iv-daily        :refresh-iv-daily)))
                    (finally
                      (when conn (try (ibkr/disconnect!) (catch Throwable _)))))
          elapsed (/ (- (System/currentTimeMillis) run-start) 1000.0)]
      (println (format "refresh-all done in %.1fs" elapsed))
      {:subcommand :refresh-all
       :elapsed-sec elapsed
       :phases     results})))

(defmethod run-subcommand :ping [_ opts _]
  (let [ds   (ds-of opts)
        conn (or (:conn opts)
                 (try (conn-of opts) (catch Throwable _ nil)))]
    (try
      (refresh/ping {:conn conn :ds ds})
      (finally (when (and conn (not (:conn opts))) (ibkr/disconnect!))))))

(defmethod run-subcommand :portfolio [_ opts _]
  (let [src (portfolio/->MockSource)]
    {:subcommand      :portfolio
     :account-id      (:account opts)
     :positions       (portfolio/positions src)
     :account-summary (portfolio/account-summary src)}))

(defmethod run-subcommand :screen [_ opts args]
  (let [ds (:ds opts)]
    (if (seq args)
      (let [r (if ds
                (screener/run-screen ds (first args))
                {:results []})]
        {:subcommand :screen :results (:results r []) :count (count (:results r []))})
      {:subcommand :screen :screens (if ds (screener/list-screens ds) [])})))

(defmethod run-subcommand :refresh [_ opts _]
  (let [r (actions/handle-action {:type :refresh-quotes :account-id (:account opts)})]
    {:subcommand :refresh :result r}))

(defmethod run-subcommand :order [_ opts args]
  ;; Usage:
  ;;   order BUY|SELL SYM QTY [MKT|LMT] [PRICE]   ← single-call preview
  ;;   order BUY|SELL SYM QTY [MKT|LMT] [PRICE] confirm
  ;; --allow-orders is required for the second form to actually send.
  (let [[side sym qty otype price tail] args
        confirm? (= "confirm" (some-> tail str/lower-case))
        conn     (when (:allow-orders opts)
                   (try (conn-of opts) (catch Throwable _ nil)))
        src      (orders/make-source {:type :ibkr :ib-client conn})]
    (try
      (actions/handle-action
        {:type          :place-order
         :allow-orders? (boolean (:allow-orders opts))
         :order-source  src
         :side          (some-> side str/upper-case)
         :symbol        sym
         :quantity      (some-> qty parse-long)
         :order-type    (or (some-> otype str/upper-case) "MKT")
         :limit-price   (some-> price parse-double)
         :confirm?      confirm?})
      (finally (when (and conn (not (:conn opts)))
                 (try (ibkr/disconnect!) (catch Throwable _)))))))

(defmethod run-subcommand :default [cmd _ _]
  {:error (str "unknown subcommand: " (name cmd))})

(defn dispatch [argv]
  (let [{:keys [options arguments summary errors]}
        (tools-cli/parse-opts argv cli-options :in-order false)]
    (cond
      errors            {:error (first errors)}
      (:help options)   (do (print-usage summary) {:help true})
      (empty? arguments) (do (print-usage summary) {:error "no subcommand given"})
      :else
      (let [cmd (keyword (first arguments))]
        (try
          (binding [refresh/*parallelism* (or (:parallelism options)
                                              refresh/*parallelism*)]
            (run-subcommand cmd options (vec (rest arguments))))
          (catch Throwable t
            {:error (.getMessage t) :ex-data (ex-data t)}))))))

(defn -main [& args]
  (let [{:keys [options]} (tools-cli/parse-opts (vec args) cli-options :in-order false)]
    (try
      (edgar/apply-edgar-source! (config/load-config (:profile options)))
      (catch Throwable t
        (binding [*out* *err*]
          (println "warning: failed to apply EDGAR config —" (.getMessage t))))))
  (let [result (dispatch (vec args))]
    (println result)
    (when (:error result) (System/exit 1))))
