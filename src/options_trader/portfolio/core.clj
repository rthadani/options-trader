(ns options-trader.portfolio.core
  (:require [clojure.core.async :as async]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.ibkr :as ibkr]))

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
  (doseq [{:keys [symbol opt-right expiry strike qty avg-cost market-value unrealized-pnl]} pos-seq]
    (jdbc/execute! ds
      ["INSERT INTO positions
          (account, symbol, opt_right, expiry, strike, quantity, avg_cost, market_val, unrealized, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        ON CONFLICT (account, symbol, opt_right, expiry, strike) DO UPDATE SET
          quantity   = excluded.quantity,
          avg_cost   = excluded.avg_cost,
          market_val = excluded.market_val,
          unrealized = excluded.unrealized,
          updated_at = excluded.updated_at"
       account-id
       symbol
       (or opt-right "")
       (expiry->sql-date expiry)
       (or strike 0.0)
       qty
       avg-cost
       market-value
       unrealized-pnl])))

(defn- upsert-account-summary! [ds account-id {:keys [net-liq cash buying-power day-pl]}]
  (when net-liq
    (jdbc/execute! ds
      ["INSERT INTO account_summary (account, fetched_at, net_liq, cash, buying_power, day_pl)
        VALUES (?, current_timestamp, ?, ?, ?, ?)
        ON CONFLICT (account, fetched_at) DO NOTHING"
       account-id net-liq cash buying-power day-pl])))

(defrecord JdbcStore [ds]
  IPortfolioStore
  (read-positions [_ account-id]
    (->> (jdbc/execute! ds
           ["SELECT symbol, opt_right, expiry, strike, quantity, avg_cost, market_val, unrealized
             FROM positions WHERE account = ?" account-id]
           {:builder-fn rs/as-unqualified-lower-maps})
         (mapv (fn [r]
                 {:symbol         (:symbol r)
                  :opt-right      (or (:opt_right r) "")
                  :expiry         (:expiry r)
                  :strike         (or (:strike r) 0.0)
                  :qty            (:quantity r)
                  :avg-cost       (:avg_cost r)
                  :market-value   (:market_val r)
                  :unrealized-pnl (:unrealized r)}))))
  (read-account-summary [_ account-id]
    (when-let [row (first
                     (jdbc/execute! ds
                       ["SELECT net_liq, cash, buying_power, day_pl
                         FROM account_summary WHERE account = ?
                         ORDER BY fetched_at DESC LIMIT 1" account-id]
                       {:builder-fn rs/as-unqualified-lower-maps}))]
      {:net-liq      (:net_liq row)
       :cash         (:cash row)
       :buying-power (:buying_power row)
       :day-pl       (:day_pl row)}))
  (write-positions! [_ account-id positions]
    (upsert-positions! ds account-id positions))
  (write-account-summary! [_ account-id summary]
    (upsert-account-summary! ds account-id summary)))

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
            (do
              (write-positions! store account-id @result)
              (deliver p @result))
            (swap! result conj pos))))
      (deref p 10000 [])))
  (account-summary [_]
    (let [row-atom (atom {})
          p        (promise)
          store    (->JdbcStore ds)]
      (ibkr/req-account-summary
        ib-client
        "NetLiquidation,TotalCashValue,BuyingPower,DayTradesRemaining"
        (fn [row]
          (if (nil? row)
            (let [summary @row-atom]
              (write-account-summary! store account-id summary)
              (deliver p summary))
            (swap! row-atom merge row))))
      (deref p 10000 nil)))
  (realized-pnl [_ _ _]
    (throw (UnsupportedOperationException. "realized-pnl: not implemented for IbkrSource"))))

(defn refresh!
  [source ds account-id]
  (let [pos  (positions source)
        summ (account-summary source)]
    (upsert-positions! ds account-id pos)
    (upsert-account-summary! ds account-id summ)
    {:positions (count pos) :account-id account-id}))

(defn make-source
  [{:keys [portfolio]} ib-client ds]
  (case (get portfolio :source :mock)
    :ibkr (->IbkrSource ib-client ds (get portfolio :account-id "DU123456"))
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
