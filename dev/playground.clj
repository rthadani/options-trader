(ns playground
  (:require [next.jdbc                          :as jdbc]
            [next.jdbc.result-set               :as rs]
            [options-trader.db.duckdb           :as duckdb]
            [options-trader.data.universes      :as universes]
            [options-trader.data.ibkr           :as ibkr]
            [options-trader.data.news           :as news]
            [options-trader.data.fundamentals   :as fund]
            [options-trader.data.edgar          :as edgar]
            [options-trader.data.options        :as opts]
            [options-trader.indicators.engine   :as indicators]
            [options-trader.indicators.iv       :as iv-engine]
            [options-trader.portfolio.core      :as portfolio]
            [options-trader.screener.registry   :as screens]))

(def ^:dynamic *cfg* {:db {:path "cache/playground.duckdb"}})
(def ^:dynamic *tws* {:host "127.0.0.1" :port 7497 :client-id 17})

(defn ds         [] (duckdb/datasource *cfg*))
(defn bootstrap! [] (duckdb/bootstrap! *cfg*))
(defn connect!   []
  (let [{:keys [host port client-id]} *tws*]
    (try (ibkr/connect! host port client-id)
         (catch Throwable _ :unavailable))))

(defonce DS nil)
(defonce IB nil)
(defonce account nil)
(defonce src     nil)

(def ^:dynamic *edgar-user-agent*
  (or (System/getenv "EDGAR_USER_AGENT") "options-trader you@example.com"))

(defn start!
  ([] (start! {}))
  ([{:keys [tws? market-data-type]
     :or   {tws? true market-data-type :delayed}}]
   (bootstrap!)
   (alter-var-root #'DS (fn [_] (ds)))
   (when tws? (alter-var-root #'IB (fn [_] (connect!))))
   (let [ua (or (System/getenv "EDGAR_USER_AGENT") *edgar-user-agent*)]
     (edgar/set-default-source!
       (edgar/make-source {:type :edgar :user-agent ua})))
   (when (and tws? IB (not= IB :unavailable))
     (try (ibkr/set-market-data-type! market-data-type) (catch Throwable _)))
   (when (and tws? IB (not= IB :unavailable))
     (when-let [acct (ibkr/default-account)]
       (alter-var-root #'account (constantly acct))
       (alter-var-root #'src
         (constantly (portfolio/->IbkrSource IB DS acct)))))
   {:ds         (some? DS)
    :tws        (if tws? (ibkr/is-connected?) :skipped)
    :edgar      (type (edgar/default-source))
    :account    account
    :data-mode  (when tws? market-data-type)}))

(defn stop! []
  (when IB (ibkr/disconnect!))
  (alter-var-root #'IB      (constantly nil))
  (alter-var-root #'DS      (constantly nil))
  (alter-var-root #'src     (constantly nil))
  (alter-var-root #'account (constantly nil))
  :stopped)

(defn set-account! [acct]
  (alter-var-root #'account (constantly acct))
  (alter-var-root #'src     (constantly (portfolio/->IbkrSource IB DS acct)))
  {:account acct :src (some? src)})

(defn diagnose-tws!
  ([] (diagnose-tws! 5000))
  ([timeout-ms]
   (require 'ib-re-actor-976-plus.gateway)
   (let [connect (ns-resolve 'ib-re-actor-976-plus.gateway 'connect)
         is-conn (ns-resolve 'ib-re-actor-976-plus.gateway 'is-connected?)
         {:keys [host port client-id]} *tws*
         events  (atom [])
         fut     (future
                   (try
                     (let [c (connect client-id host port
                                      (fn [evt] (swap! events conj evt)))]
                       {:client c})
                     (catch Throwable t
                       {:error (.getMessage t)
                        :class (str (class t))
                        :ex-data (ex-data t)})))
         r       (deref fut timeout-ms ::timeout)]
     (cond
       (= r ::timeout)
       {:hung true :timeout-ms timeout-ms :events-seen (count @events)
        :first-events (take 10 @events)
        :host host :port port :client-id client-id}

       (:error r)
       (assoc r :events-seen (count @events) :first-events (take 10 @events)
                :host host :port port :client-id client-id)

       :else
       (let [c (:client r)]
         (Thread/sleep 800)
         {:ok            true
          :is-connected? (boolean (is-conn c))
          :events-seen   (count @events)
          :first-events  (take 10 @events)
          :host host :port port :client-id client-id})))))

(defn await-batch
  ([start-fn] (await-batch start-fn 300000))
  ([start-fn timeout-ms]
   (let [p (promise)
         r (start-fn (fn [events] (deliver p events)))]
     (if (= :unavailable r)
       :unavailable
       (let [v (deref p timeout-ms ::timeout)]
         (if (= ::timeout v) {:error :timeout :req-id r} v))))))

(defn await-stream
  ([start-fn] (await-stream start-fn 15000))
  ([start-fn timeout-ms]
   (let [items (atom [])
         done  (promise)
         cb    (fn [ev]
                 (if (nil? ev)
                   (deliver done @items)
                   (swap! items conj ev)))
         r     (start-fn cb)]
     (if (= :unavailable r)
       :unavailable
       (let [v (deref done timeout-ms ::timeout)]
         (if (= ::timeout v) {:error :timeout :received (count @items) :req-id r} v))))))

(defn- ->iso-date [s]
  (let [s (str s)]
    (if (re-matches #"\d{8}" s)
      (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8))
      s)))

(defn insert-bars! [data-source bars]
  (let [rows (->> bars
                  (keep (fn [b]
                          (let [d (or (:bar-date b) (:date b) (:time b))]
                            (when (and d (:close b))
                              [(:symbol b) (->iso-date d)
                               (:open b) (:high b) (:low b) (:close b)
                               (or (:volume b) 0)]))))
                  vec)]
    (when (seq rows)
      (jdbc/execute-batch! data-source
        "INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (symbol, bar_date) DO UPDATE SET
           open   = excluded.open,
           high   = excluded.high,
           low    = excluded.low,
           close  = excluded.close,
           volume = excluded.volume"
        rows {}))
    (count rows)))

(defn bar-count [data-source symbol]
  (-> (jdbc/execute-one! data-source
        ["SELECT COUNT(*) AS n FROM bars_daily WHERE symbol = ?" symbol]
        {:builder-fn rs/as-unqualified-lower-maps})
      :n))

;; ── Bootstrap ───────────────────────────────────────────────────────────────
(comment
  (require '[options-trader.data.ibkr :as ibkr] :reload)
  (start!)
  (start! {:tws? false})
  DS IB
  (ibkr/is-connected?)
  (stop!))

;; ── 1. Historical daily bars ────────────────────────────────────────────────
(comment
  (def tickers (universes/fetch-sp500))
  (count tickers)
  (take 10 tickers)

  (def aapl-bars
    (await-batch
      (fn [cb]
        (ibkr/req-historical-bars IB (ibkr/->contract "AAPL")
                                  "1 day" "1 Y" cb))))
  (count aapl-bars)
  (first aapl-bars)

  (insert-bars! DS (map #(assoc % :symbol "AAPL") aapl-bars))
  (bar-count DS "AAPL")

  (doseq [sym (take 3 tickers)]
    (let [bars (await-batch
                 (fn [cb]
                   (ibkr/req-historical-bars IB (ibkr/->contract sym)
                                             "1 day" "1 Y" cb)))]
      (insert-bars! DS (map #(assoc % :symbol sym) bars))
      (println sym (count bars)))))

;; ── 2. Indicators + screener ────────────────────────────────────────────────
(comment
  (indicators/refresh-derived-indicators! DS)

  (jdbc/execute-one! DS
    ["SELECT symbol, rsi_14, stoch_k, macd_hist FROM latest_indicators
       WHERE symbol = ?" "AAPL"]
    {:builder-fn rs/as-unqualified-lower-maps})

  (screens/load-screens-from-dir! DS)
  (map :name (screens/list-screens DS))

  (screens/run-screen DS "Oversold Mean Revert")
  (screens/run-query DS
    "SELECT symbol FROM latest_indicators
      WHERE rsi_14 < 40 ORDER BY rsi_14 ASC LIMIT 5")

  (indicators/refresh-fundamentals! DS)

  (jdbc/execute-one! DS
    ["SELECT symbol, pe_ratio, fcf_yield, debt_to_equity, gross_margin,
             revenue_growth_yoy, eps_growth_yoy
        FROM latest_indicators WHERE symbol = ?" "AAPL"]
    {:builder-fn rs/as-unqualified-lower-maps})

  (screens/run-screen DS "Value with Growth"))

;; ── 3. News ─────────────────────────────────────────────────────────────────
(comment
  (def news-src (news/make-source {:type :ibkr :ib-client IB}))

  (def aapl-news
    (await-batch
      (fn [cb] (news/fetch-news-headlines "AAPL" {:limit 10} news-src cb))))
  (count aapl-news)
  (first aapl-news)

  (news/fetch-news-sentiment "AAPL" {} news-src))

;; ── 4. Fundamentals (EDGAR) ─────────────────────────────────────────────────
(comment
  (def fund-src (fund/make-source {:type :edgar}))

  (def aapl-fund
    (await-batch
      (fn [cb] (fund/fetch-fundamentals "AAPL" {} fund-src cb))))

  (def aapl (first aapl-fund))
  (keys aapl)
  (:metadata aapl)
  (tech.v3.dataset/head (:income   aapl) 5)
  (tech.v3.dataset/head (:balance  aapl) 5)
  (tech.v3.dataset/head (:cashflow aapl) 5))

;; ── 5. EDGAR filings ────────────────────────────────────────────────────────
(comment
  (def aapl-10ks
    (edgar/fetch-edgar-filings "AAPL" {:form-type "10-K" :limit 3}))
  (count aapl-10ks)
  (map #(select-keys % [:accession :form-type :filed-at :period-of-report])
       aapl-10ks)

  (edgar/fetch-edgar-filings "AAPL" {:form-type "10-Q" :limit 2})

  (def cik (:cik (first aapl-10ks)))
  (def aapl-facts (edgar/fetch-edgar-facts cik))
  (take 3 aapl-facts)

  (type (edgar/default-source)))

;; ── 6. Option chain + quote + IV ────────────────────────────────────────────
(comment
  (require '[options-trader.actions.core :as actions])
  (require 'options-trader.actions.research)

  (def opts-src (opts/make-source {:type :ibkr :ib-client IB}))

  (def aapl-chain
    (:result
      (actions/handle-action
        {:type   :research/fetch-option-chain
         :symbol "AAPL"
         :source opts-src})))
  (count (:expirations aapl-chain))
  (take 5 (:expirations aapl-chain))

  (:expirations
    (:result
      (actions/handle-action
        {:type          :research/fetch-option-chain
         :symbol        "AAPL"
         :expiry-prefix "202609"
         :source        opts-src})))

  (def quote-aapl
    (:result
      (actions/handle-action
        {:type      :research/fetch-option-quote
         :ib-client IB
         :symbol    "AAPL"
         :strike    220.0
         :expiry    "20260918"
         :right     "C"})))
  quote-aapl

  (def quote-aapl-raw
    (await-batch
      (fn [cb]
        (ibkr/req-market-data-snapshot IB
          (ibkr/->contract {:symbol "AAPL" :sec-type "OPT"
                            :last-trade-date-or-contract-month "20260918"
                            :strike 220.0 :right "C"
                            :exchange "SMART" :currency "USD"})
          ibkr/all-generic-ticks
          cb))))
  (ibkr/normalize-snapshot quote-aapl-raw)

  (iv-engine/refresh-iv-indicators! DS)
  (jdbc/execute-one! DS
    ["SELECT symbol, iv_rank_252d, iv_percentile_252d, hv_rank_252d
        FROM latest_indicators WHERE symbol = ?" "AAPL"]
    {:builder-fn rs/as-unqualified-lower-maps}))

;; ── 7. Portfolio ────────────────────────────────────────────────────────────
(comment
  account
  src

  (def positions (portfolio/positions src))
  (count positions)
  (take 3 positions)

  (def summary (portfolio/account-summary src))
  summary

  (portfolio/refresh! src DS account)

  (jdbc/execute! DS
    ["SELECT symbol, quantity, avg_cost, market_val, unrealized
        FROM positions WHERE account = ? ORDER BY market_val DESC LIMIT 10"
     account]
    {:builder-fn rs/as-unqualified-lower-maps})

  (jdbc/execute-one! DS
    ["SELECT * FROM account_summary
       WHERE account = ? ORDER BY fetched_at DESC LIMIT 1" account]
    {:builder-fn rs/as-unqualified-lower-maps}))

;; ── 8. Orders (PAPER ONLY — verify port 7497) ───────────────────────────────
(comment
  (require '[options-trader.data.ibkr :as ibkr] :reload)

  (println "Paper account?  Port:" (:port *tws*))

  (def buy-id
    (ibkr/place-order IB
      (ibkr/->contract "AAPL")
      (ibkr/->order {:action :buy :quantity 1 :type :market})
      (fn [evt] (println 'order-evt (select-keys evt [:type :status :filled :avg-fill-price])))))

  (def limit-buy-id
    (ibkr/place-order IB
      (ibkr/->contract "AAPL")
      (ibkr/->order {:action :buy :quantity 1
                     :type :limit :limit-price 200.0
                     :time-in-force :day})))

  (ibkr/cancel-order IB limit-buy-id)

  (portfolio/refresh! src DS account)
  (jdbc/execute! DS
    ["SELECT symbol, quantity, avg_cost, market_val FROM positions
       WHERE account = ?" account]
    {:builder-fn rs/as-unqualified-lower-maps})

  (def sell-id
    (ibkr/place-order IB
      (ibkr/->contract "AAPL")
      (ibkr/->order {:action :sell :quantity 1 :type :market}))))
