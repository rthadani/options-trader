(ns options-trader.data.market-data
  "Pluggable market-data backend. Implementations hide their async
   plumbing; callers get a synchronous quote map back, with any field
   optional. Non-map returns are :unavailable (no connection / rejected)
   and :timeout. Construct via make-source — :type :ibkr / :mock /
   :unavailable."
  (:require [options-trader.data.ibkr :as ibkr]))

(defprotocol IMarketDataSource
  (snapshot-stk [this symbol]
    "One-shot snapshot for a stock symbol. Returns a quote map / :unavailable / :timeout.")
  (snapshot-opt [this opts]
    "One-shot snapshot for an option contract. opts: {:symbol :expiry :strike
     :right [:exchange :currency :multiplier]}. Returns a quote map.")
  (stream-opt [this opts collect-ms]
    "Open a streaming OPT subscription, collect ticks for `collect-ms`,
     cancel. Returns a quote map.")
  (calc-iv [this opts]
    "Compute IV + greeks via reqCalcImpliedVolatility-style flow. opts:
     base option fields + :option-price :underlying-price. Returns a
     quote map with :iv/:delta/:gamma/:theta/:vega, or :unavailable.")
  (session-vwap [this symbol]
    "Today's session VWAP for a stock symbol. Returns a double or nil
     when the backend can't supply it. Lets quotes.clj stay backend-
     agnostic — it composes this with its own DB-derived VWAP."))


(defn- option-contract [{:keys [symbol expiry strike right exchange currency multiplier]}]
  {:symbol     symbol
   :sec-type   "OPT"
   :exchange   (or exchange "SMART")
   :currency   (or currency "USD")
   :last-trade-date-or-contract-month (str expiry)
   :strike     (double strike)
   :right      (case (some-> right str clojure.string/lower-case)
                 ("c" "call" ":call") :call
                 ("p" "put"  ":put")  :put
                 right)
   :multiplier (or multiplier "100")})

(defn- ib-snapshot
  "Run a snapshot via the IB connection, returning a normalised quote map
   or :unavailable / :timeout. Cancels on timeout so we don't leak slots."
  [ib-client contract tick-types timeout-ms]
  (let [p   (promise)
        rid (ibkr/req-market-data-snapshot
              ib-client contract tick-types (fn [evs] (deliver p evs)))]
    (cond
      (= :unavailable rid) :unavailable
      :else (let [evs (deref p timeout-ms ::timeout)]
              (cond
                (= ::timeout evs)
                (do (try (ibkr/cancel-sub! ib-client rid) (catch Throwable _))
                    :timeout)
                (nil? evs) :timeout
                :else      (assoc (ibkr/normalize-snapshot evs) :contract contract))))))

(defn- ib-stream
  "Open a streaming market-data subscription, collect ticks for `collect-ms`,
   then cancel. Returns a quote map or :unavailable."
  [ib-client contract tick-types collect-ms]
  (let [acc (atom [])
        rid (ibkr/dispatch-stream!
              ib-client
              {:type :req-market-data :contract contract
               :tick-types tick-types :snapshot false}
              (fn [ev] (when (map? ev) (swap! acc conj ev))))]
    (cond
      (= :unavailable rid) :unavailable
      :else
      (try
        (Thread/sleep (long collect-ms))
        (let [snap (ibkr/normalize-snapshot @acc)]
          (assoc snap :contract contract :event-count (count @acc)))
        (finally
          (try (ibkr/cancel-sub! ib-client rid) (catch Throwable _)))))))

(defn- ib-calc-iv
  "Use IB's reqCalcImpliedVolatility to back IV + greeks out of provided
   prices. Works any time of day — no live market data needed."
  [ib-client contract option-price underlying-price collect-ms]
  (let [acc (atom [])
        rid (ibkr/req-calc-implied-vol ib-client contract
                                        (double option-price)
                                        (double underlying-price)
                                        (fn [ev] (when (map? ev) (swap! acc conj ev))))]
    (cond
      (= :unavailable rid) :unavailable
      :else
      (try
        (Thread/sleep (long collect-ms))
        (let [snap (ibkr/normalize-snapshot @acc)]
          (assoc snap
                 :contract         contract
                 :event-count      (count @acc)
                 :option-price     (double option-price)
                 :underlying-price (double underlying-price)))
        (finally
          (try (ibkr/cancel-calc-implied-vol ib-client rid) (catch Throwable _)))))))

(defn- ib-session-vwap
  "One-shot historical-bar fetch for today's '1 day' bar. Each IB Bar
   carries a `wap` (weighted-average-price) field — that's the broker's
   true session VWAP."
  [ib-client symbol]
  (try
    (let [p (promise)]
      (ibkr/req-historical-bars ib-client (ibkr/->contract symbol)
                                "1 day" "1 D"
                                (fn [evs] (deliver p evs)))
      (let [evs (deref p 4000 nil)]
        (when (sequential? evs)
          (some-> (last evs) (#(or (:wap %) (:w-a-p %))) double))))
    (catch Throwable _ nil)))

(deftype IbkrMarketDataSource [ib-client]
  IMarketDataSource
  (snapshot-stk [_ symbol]
    (if ib-client
      (ib-snapshot ib-client (ibkr/->contract symbol) ibkr/all-generic-ticks 5000)
      :unavailable))
  (snapshot-opt [_ opts]
    (if ib-client
      ;; Snapshot mode for OPT rejects generic ticks with error 321
      ;; ('Snapshot market data subscription is not applicable to generic
      ;; ticks'). The base tick set still delivers bid/ask/last/close/volume,
      ;; which is all snapshot mode realistically returns anyway.
      (ib-snapshot ib-client (option-contract opts) [] 15000)
      :unavailable))
  (stream-opt [_ opts collect-ms]
    (if ib-client
      (ib-stream ib-client (option-contract opts) ibkr/option-generic-ticks collect-ms)
      :unavailable))
  (calc-iv [_ opts]
    (if-not ib-client
      :unavailable
      (let [contract (option-contract opts)
            op       (:option-price opts)
            up       (:underlying-price opts)]
        (if (and (number? op) (number? up))
          (ib-calc-iv ib-client contract op up 3000)
          :unavailable))))
  (session-vwap [_ symbol]
    (when ib-client (ib-session-vwap ib-client symbol))))


(deftype UnavailableMarketDataSource []
  IMarketDataSource
  (snapshot-stk    [_ _symbol]      :unavailable)
  (snapshot-opt    [_ _opts]        :unavailable)
  (stream-opt      [_ _opts _ms]    :unavailable)
  (calc-iv         [_ _opts]        :unavailable)
  (session-vwap    [_ _symbol]      nil))


(deftype MockMarketDataSource [responses]
  IMarketDataSource
  (snapshot-stk [_ symbol]
    (or (get-in @responses [:snapshot-stk symbol]) :unavailable))
  (snapshot-opt [_ opts]
    (or (get-in @responses [:snapshot-opt (:symbol opts)]) :unavailable))
  (stream-opt [_ opts _collect-ms]
    (or (get-in @responses [:stream-opt (:symbol opts)]) :unavailable))
  (calc-iv [_ opts]
    (or (get-in @responses [:calc-iv (:symbol opts)]) :unavailable))
  (session-vwap [_ symbol]
    (get-in @responses [:session-vwap symbol])))

(defn make-mock-source
  "Build a MockMarketDataSource pre-loaded with canned responses.
   responses is a map like:
     {:snapshot-stk {\"AAPL\" {:bid 175.10 :ask 175.15 :last 175.12 ...}}
      :snapshot-opt {\"MPWR\" {:bid 192.50 :close 191.05 ...}}
      :stream-opt   {\"MPWR\" {:iv 0.41 :delta -0.91 ...}}
      :calc-iv      {\"MPWR\" {:iv 0.41 :delta -0.91 :gamma 0.0001 ...}}}"
  ([] (make-mock-source {}))
  ([responses] (->MockMarketDataSource (atom responses))))


(defmulti make-source
  "Construct a market data source from a config map. Dispatches on :type.
   Supported: :ibkr (needs :ib-client), :mock (needs :responses), :unavailable."
  :type)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (->IbkrMarketDataSource ib-client))

(defmethod make-source :mock [{:keys [responses]}]
  (make-mock-source (or responses {})))

(defmethod make-source :unavailable [_]
  (->UnavailableMarketDataSource))

(defmethod make-source :default [_]
  (->UnavailableMarketDataSource))
