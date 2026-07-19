(ns options-trader.portfolio.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.db.queries.portfolio :as q]
            [taoensso.timbre :as log]))

(defprotocol IPortfolioStore
  (read-positions [store account-id])
  (read-account-summary [store account-id])
  (write-positions! [store account-id positions])
  (write-account-summary! [store account-id summary]))

(defprotocol IAccountSource
  (positions [source])
  (account-summary [source])
  (realized-pnl [source from-ms to-ms]))

(def ^:private mock-positions
  [{:symbol "AAPL" :opt-right "" :expiry (java.time.LocalDate/parse "1900-01-01") :strike 0.0
    :qty 100 :avg-cost 185.50 :market-value 19000.0 :unrealized-pnl 450.0}
   {:symbol "AAPL" :opt-right "P" :expiry (java.time.LocalDate/parse "2025-06-20") :strike 180.0
    :qty -1 :avg-cost 3.50 :market-value -250.0 :unrealized-pnl 100.0}
   {:symbol "SPY" :opt-right "" :expiry (java.time.LocalDate/parse "1900-01-01") :strike 0.0
    :qty 50 :avg-cost 520.0 :market-value 26500.0 :unrealized-pnl 500.0}])

(def ^:private mock-account-summary
  {:net-liq 100000.0 :cash 20000.0 :buying-power 80000.0 :day-pl 350.0})

(defrecord MockSource []
  IAccountSource
  (positions [_] mock-positions)
  (account-summary [_] mock-account-summary)
  (realized-pnl [_ _ _] 1234.56))

(defn- expiry->sql-date [expiry]
  (cond
    (instance? java.time.LocalDate expiry)
    (java.sql.Date/valueOf ^java.time.LocalDate expiry)
    (string? expiry)
    (java.sql.Date/valueOf ^String expiry)
    :else
    (java.sql.Date/valueOf "1900-01-01")))

(defn- upsert-positions! [ds account-id pos-seq]
  (doseq [{:keys [symbol opt-right expiry strike qty avg-cost market-value unrealized-pnl conid]
           :as row} pos-seq]
    (if (str/blank? (str symbol))
      (log/warnf "skipping position with no symbol (conid=%s account=%s qty=%s) — IB returned no security definition; row=%s"
                 conid account-id qty (pr-str (dissoc row :type)))
      (q/upsert-position! ds {:account     account-id
                              :symbol      symbol
                              :opt-right   (or opt-right "")
                              :expiry      (expiry->sql-date expiry)
                              :strike      (or strike 0.0)
                              :quantity    qty
                              :avg-cost    avg-cost
                              :market-val  market-value
                              :unrealized  unrealized-pnl}))))

(defn- upsert-account-summary! [ds account-id {:keys [net-liq cash buying-power day-pl]}]
  (when net-liq
    (q/insert-account-summary! ds {:account      account-id
                                    :net-liq      net-liq
                                    :cash         cash
                                    :buying-power buying-power
                                    :day-pl       day-pl})))

(defrecord JdbcStore [ds]
  IPortfolioStore
  (read-positions [_ account-id]
    (mapv (fn [r]
            {:symbol         (:symbol r)
             :opt-right      (or (:opt_right r) "")
             :expiry         (:expiry r)
             :strike         (or (:strike r) 0.0)
             :qty            (:quantity r)
             :avg-cost       (:avg_cost r)
             :market-value   (:market_val r)
             :unrealized-pnl (:unrealized r)})
          (q/select-positions ds account-id)))
  (read-account-summary [_ account-id]
    (when-let [row (q/select-account-summary ds account-id)]
      {:net-liq      (:net_liq row)
       :cash         (:cash row)
       :buying-power (:buying_power row)
       :day-pl       (:day_pl row)}))
  (write-positions! [_ account-id positions]
    (upsert-positions! ds account-id positions))
  (write-account-summary! [_ account-id summary]
    (upsert-account-summary! ds account-id summary)))

(def ^:private price-max-wait-ms 10000)

(defn- apply-price
  "Set market-value + unrealized-pnl on a position from a normalized snapshot.
   Uses last price, falling back to close. Options carry a ×100 multiplier;
   IB avg-cost is already per-contract, so cost basis is qty × avg-cost."
  [{:keys [opt-right qty avg-cost] :as pos} quote]
  (let [price (or (:last quote) (:close quote))
        mult  (if (and opt-right (seq (str opt-right))) 100.0 1.0)]
    (if (and price qty)
      (let [mv (* (double qty) (double price) mult)]
        (assoc pos
               :market-value   mv
               :unrealized-pnl (- mv (* (double qty) (double (or avg-cost 0.0))))))
      pos)))

(defn- price-positions
  "Enrich positions with a one-shot market-data snapshot per contract (by
   conid). Fires every snapshot, waits a single window, then folds — so the
   whole batch costs ~one window rather than N round-trips. reqPositions gives
   no market value, so this is how the P&L columns get a price (current, else
   prior close — mirroring TWS)."
  [ib-client positions]
  (if-not (some :conid positions)
    positions
    (let [accs     (mapv (fn [{:keys [conid]}]
                           (when conid
                             (ibkr/start-snapshot! ib-client
                               {:conid conid :exchange "SMART" :currency "USD"})))
                         positions)
          priced?  (fn [acc]
                     (let [q (ibkr/normalize-snapshot @acc)]
                       (or (:last q) (:close q))))
          deadline (+ (System/currentTimeMillis) price-max-wait-ms)]
      ;; After-hours snapshots trickle in; resolve as soon as every contract has
      ;; a price (fast during market hours), else give up at the deadline.
      (loop []
        (when (and (< (System/currentTimeMillis) deadline)
                   (some (fn [acc] (and acc (not (priced? acc)))) accs))
          (Thread/sleep 250)
          (recur)))
      (mapv (fn [pos acc]
              (apply-price pos (when acc (ibkr/normalize-snapshot @acc))))
            positions accs))))

(defrecord IbkrSource [ib-client ds account-id]
  IAccountSource
  (positions [_]
    (let [result (atom [])
          p      (promise)
          store  (->JdbcStore ds)]
      (ibkr/req-positions
        ib-client
        (fn [pos]
          (if (nil? pos)
            (deliver p @result)
            (swap! result conj pos))))
      (let [priced (price-positions ib-client (deref p 10000 []))]
        (write-positions! store account-id priced)
        priced)))
  (account-summary [_]
    (let [tags    (atom {})
          p       (promise)
          rid-box (atom nil)
          store   (->JdbcStore ds)
          rid     (ibkr/req-account-summary
                    ib-client
                    "NetLiquidation,TotalCashValue,BuyingPower"
                    (fn [row]
                      (if (nil? row)
                        (let [summary {:net-liq      (get @tags "NetLiquidation")
                                       :cash         (get @tags "TotalCashValue")
                                       :buying-power (get @tags "BuyingPower")
                                       :day-pl       nil}]
                          (write-account-summary! store account-id summary)
                          ;; ALWAYS cancel — reqAccountSummary is a stream
                          ;; and TWS caps simultaneous subscriptions at ~3.
                          ;; Skipping this caused error 322 across reconnects.
                          (when-let [r @rid-box]
                            (try (ibkr/cancel-sub! ib-client r) (catch Throwable _)))
                          (deliver p summary))
                        (when-let [tag (:tag row)]
                          (swap! tags assoc tag (some-> (:value row) parse-double))))))]
      (reset! rid-box rid)
      (let [result (deref p 10000 nil)]
        ;; Safety net: if we timed out without a terminal event, still cancel.
        (when (nil? result)
          (try (ibkr/cancel-sub! ib-client rid) (catch Throwable _)))
        result)))
  (realized-pnl [_ _ _]
    (throw (UnsupportedOperationException. "realized-pnl: not implemented for IbkrSource"))))

(defn- partition-positions
  "Split IB position events into {:valid ... :orphan ...} by symbol presence."
  [pos-seq]
  (reduce (fn [acc row]
            (if (str/blank? (str (:symbol row)))
              (update acc :orphan conj row)
              (update acc :valid conj row)))
          {:valid [] :orphan []}
          pos-seq))

(defn refresh!
  "Swap-in-place: within one transaction, wipe the account's cached positions
   and write the current IB set. Positions IB can't hydrate (no security def →
   blank symbol) are dropped instead of causing a NOT NULL crash; their conids
   are logged so you can chase down the phantom in TWS.

   Safety net: if `positions` returns nil (IB source unavailable or the stream
   timed out), leave the cache alone rather than blanking a healthy portfolio
   on a transient blip."
  [source ds account-id]
  (let [pos  (positions source)
        summ (account-summary source)]
    (if (nil? pos)
      (do (log/warnf "portfolio refresh: positions source returned nil for account=%s — leaving DB cache untouched"
                     account-id)
          {:positions 0 :orphaned 0 :account-id account-id :skipped? true})
      (let [{:keys [valid orphan]} (partition-positions pos)]
        (doseq [row orphan]
          (log/warnf "dropping phantom position (conid=%s account=%s qty=%s) — IB returned no security definition; row=%s"
                     (:conid row) account-id (:qty row) (pr-str (dissoc row :type))))
        (q/delete-positions-for-account! ds account-id)
        (upsert-positions! ds account-id valid)
        (upsert-account-summary! ds account-id summ)
        {:positions (count valid)
         :orphaned  (count orphan)
         :account-id account-id}))))

(defn make-source
  [{:keys [portfolio]} ib-client ds]
  (case (get portfolio :source :mock)
    :ibkr (->IbkrSource ib-client ds (or (get portfolio :account-id)
                                         (ibkr/default-account)))
    :mock (->MockSource)))

(defn refresh-loop
  [source ds account-id interval-ms]
  (let [stop-ch (async/chan)]
    (async/go-loop [backoff-ms interval-ms]
      (let [[_ port] (async/alts! [(async/timeout backoff-ms) stop-ch])]
        (when-not (= port stop-ch)
          (let [next-backoff
                (try
                  (refresh! source ds account-id)
                  interval-ms
                  (catch Exception _
                    (min (* 2 backoff-ms) (* 8 interval-ms))))]
            (recur next-backoff)))))
    stop-ch))

(def ^:const snapshot-version 1)

(def ^:const supported-metrics
  #{:delta :gamma :theta :vega :beta :sharpe})

(defmulti risk-metric :metric)
(defmethod risk-metric :default [{:keys [metric]}]
  {:error :unknown-metric :metric metric})

(defmulti format-snapshot :format)
(defmethod format-snapshot :default [{:keys [format]}]
  {:error :unsupported-format :format format})
