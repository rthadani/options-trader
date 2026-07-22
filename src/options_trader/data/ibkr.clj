(ns options-trader.data.ibkr
  "Thin wrapper around ib-re-actor-976-plus. Two dispatch modes:
   - dispatch-batch!  accumulates events keyed by req-id; fires cb once with
                      the full vector when the terminal event arrives.
   - dispatch-stream! fires cb per event. Use for live subscriptions
                      (market-data stream, positions, scanner).
   Loads without ib-re-actor on the classpath; connect! throws ex-info via
   the lib-fn resolver when the gateway is needed but missing."
  (:require [clojure.string                          :as str]
            [options-trader.data.ibkr.request-id    :as req-id]
            [options-trader.data.ibkr.pacer          :as pacer]
            [options-trader.data.ibkr.subscriptions :as subs]
            [taoensso.timbre                         :as log]))

(try (require 'ib-re-actor-976-plus.gateway)       (catch Throwable _))
(try (require 'ib-re-actor-976-plus.client-socket) (catch Throwable _))
(try (require 'ib-re-actor-976-plus.mapping)       (catch Throwable _))

(defn- lib-fn [ns-sym sym]
  (if-let [v (some-> (find-ns ns-sym) (ns-resolve sym))]
    @v
    (throw (ex-info (str ns-sym "/" sym " unavailable — ib-re-actor not on classpath")
                    {:ns ns-sym :sym sym}))))

(defn- gw-fn [sym] (lib-fn 'ib-re-actor-976-plus.gateway       sym))
(defn- cs-fn [sym] (lib-fn 'ib-re-actor-976-plus.client-socket sym))

(defn- ->cmap
  "Best-effort coerce a java IB object to a Clojure map via the Mappable
   protocol. Returns the value unchanged if mapping isn't loaded or the
   conversion throws."
  [obj]
  (if-let [v (some-> (find-ns 'ib-re-actor-976-plus.mapping) (ns-resolve '->map))]
    (try (@v obj) (catch Throwable _ obj))
    obj))


(defonce ^:private conn-atom    (atom nil))
(defonce ^:private pending      (atom {}))      ;; rid → {:cb fn :mode kw :events []}
(defonce ^:private default-subs (subs/create-manager))

;; reqPositions is account-wide and carries no request-id, so its :position /
;; :position-end events can't be routed by id like every other stream. Remember
;; the id we registered the cb under and map those events back to it.
(defonce ^:private positions-rid (atom nil))

;; reqAccountUpdates is single-subscription per session and also rid-less. Its
;; :update-portfolio, :update-account-value, :update-account-time, and
;; :account-download-end events route via the same id-as-fallback trick.
(defonce ^:private account-updates-rid (atom nil))

;; IB orders use their own monotonic id sequence (NOT the req-id sequence).
;; TWS hands us the seed via :next-valid-id at handshake; every place-order
;; consumes one. We keep the counter here and bump it as orders go out.
(defonce ^:private next-order-id-atom (atom nil))

(defn- capture-next-valid-id! [event]
  (when-let [seed (or (:order-id event) (:id event))]
    (let [n (long seed)]
      (reset! next-order-id-atom n)
      (log/infof "ibkr next-valid-order-id seeded at %d" n))))

(defn next-order-id!
  "Atomically consume and return the next IB order-id. Throws if TWS has
   not yet sent :next-valid-id — connect! waits for managedAccounts but
   not for the order-id seed; callers can race the handshake on a fresh
   connection. Retry after a short delay if this fires."
  []
  (let [v (swap! next-order-id-atom (fn [n] (when n (inc n))))]
    (when (nil? v)
      (throw (ex-info "next-valid-order-id not yet received from TWS"
                      {:hint "wait ~250ms after connect! before sending orders"})))
    (dec v)))


(defn ->contract
  "Build (or normalise) a contract map for ib-re-actor.
   String arg: (->contract \"AAPL\") → STK / SMART / USD defaults.
   Map arg:    keyword :sec-type gets name-ified. :right is left as-is —
   ib-re-actor's translation table expects the KEYWORD form (:put / :call),
   so calling (name :put) here would silently turn it into \"put\", the
   translation would fail to match, and the IB Contract would ship with a
   null right field → TWS error 321.
   Idempotent."
  [x]
  (cond
    (string? x)
    {:symbol x :sec-type "STK" :exchange "SMART" :currency "USD"}

    (map? x)
    (cond-> x
      (keyword? (:sec-type x)) (update :sec-type name))

    :else x))

(defn- parse-bar-size
  "IBKR bar-size string → [n unit-kw]. Examples:
     \"1 day\"  → [1 :day]
     \"5 mins\" → [5 :minute]
     \"1 hour\" → [1 :hour]"
  [s]
  (let [[n unit] (str/split s #"\s+")]
    [(Integer/parseInt n)
     (case (str/lower-case (str/replace unit #"s$" ""))
       "sec"   :second
       "min"   :minute
       "hour"  :hour
       "day"   :day
       "week"  :week
       "month" :month)]))

(defn- parse-duration
  "IBKR duration string → [n unit-kw]. Examples:
     \"1 Y\"  → [1 :years]
     \"30 D\" → [30 :days]
     \"60 S\" → [60 :seconds]"
  [s]
  (let [[n unit] (str/split s #"\s+")]
    [(Integer/parseInt n)
     (case (str/upper-case unit)
       "S" :seconds
       "D" :days
       "W" :weeks
       "M" :months
       "Y" :years)]))


(def ^:private batch-terminal-event-types
  "Events that close out a BATCH request — TWS's `*-end` markers."
  #{:historical-data-end
    :historical-news-end
    :contract-details-end
    :tick-snapshot-end
    :position-end
    :account-summary-end
    :scanner-data-end
    :security-definition-optional-parameter-end})

(def ^:private stream-terminal-event-types
  "Same set MINUS :tick-snapshot-end. IB sends tick-snapshot-end on both
   snapshot and streaming requests — for the latter it means 'initial state
   delivered, live updates follow', NOT 'request finished'. Treating it as
   terminal for streaming subs unregisters the cb and silently drops every
   subsequent live tick."
  (disj batch-terminal-event-types :tick-snapshot-end))

(def ^:private single-shot-event-types
  "Events that are both the data and the terminal — TWS sends one and is done.
   The listener accumulates them, then closes the batch immediately."
  #{:news-article
    :news-providers
    :fundamental-data
    :current-time
    :historical-schedule})

(defn- register-pending! [rid cb mode]
  (swap! pending assoc rid {:cb cb :mode mode :events []})
  rid)

(defn- accumulate! [rid event]
  (swap! pending update-in [rid :events] (fnil conj []) event))

(defn- complete-pending! [rid]
  (when-let [{:keys [cb mode events]} (get @pending rid)]
    (swap! pending dissoc rid)
    (when (and cb (= mode :batch))
      (try (cb events)
           (catch Throwable e
             (log/warnf e "batch cb threw for req-id %s" rid))))))

(def ^:private epoch-expiry (java.time.LocalDate/parse "1900-01-01"))

(defn- ib-expiry->local-date
  "IB option expiry arrives as \"yyyyMMdd\"; stock positions carry none."
  [s]
  (when (and (string? s) (re-matches #"\d{8}" s))
    (java.time.LocalDate/parse s java.time.format.DateTimeFormatter/BASIC_ISO_DATE)))

(defn- project-position
  "Normalise a reqPositions :position event (java Contract + Decimal) into the
   row shape portfolio.core/upsert-positions! expects. reqPositions carries no
   market value or unrealized P&L, so those stay nil."
  [event]
  (let [^com.ib.client.Contract c (:contract event)
        opt? (= "OPT" (str (.secType c)))]
    {:type           :position
     :account        (:account event)
     :conid          (.conid c)
     :symbol         (.symbol c)
     :opt-right      (case (str (.right c)) "Call" "C" "Put" "P" "")
     :expiry         (if opt?
                       (or (ib-expiry->local-date (.lastTradeDateOrContractMonth c))
                           epoch-expiry)
                       epoch-expiry)
     :strike         (.strike c)
     :qty            (.longValue ^com.ib.client.Decimal (:pos event))
     :avg-cost       (:avg-cost event)
     :market-value   nil
     :unrealized-pnl nil}))

(defn- pick
  "Try several possible key spellings (ib-re-actor's auto-conversion is
   usually kebab-case but isn't always — defensive against minor binding drift)."
  [m & ks]
  (some (fn [k] (when-let [v (get m k)] v)) ks))

(defn- project-update-portfolio
  "Normalise a reqAccountUpdates :update-portfolio event. Unlike :position
   events, these carry live :market-price/:market-value/:unrealized-pnl that
   tick continuously as the underlying moves — that's the streaming part."
  [event]
  (let [^com.ib.client.Contract c (:contract event)
        opt? (= "OPT" (str (.secType c)))
        qty  (let [p (or (:position event) (:pos event))]
               (cond
                 (instance? com.ib.client.Decimal p) (.longValue ^com.ib.client.Decimal p)
                 (number? p)                         (long p)
                 :else                               0))]
    {:type           :update-portfolio
     :account        (pick event :account-name :account)
     :conid          (.conid c)
     :symbol         (.symbol c)
     :opt-right      (case (str (.right c)) "Call" "C" "Put" "P" "")
     :expiry         (if opt?
                       (or (ib-expiry->local-date (.lastTradeDateOrContractMonth c))
                           epoch-expiry)
                       epoch-expiry)
     :strike         (.strike c)
     :qty            qty
     :avg-cost       (pick event :average-cost :avg-cost)
     :market-price   (pick event :market-price :marketPrice)
     :market-value   (pick event :market-value :marketValue)
     :unrealized-pnl (pick event :unrealized-pnl :unrealizedPNL :unrealised-pnl)
     :realized-pnl   (pick event :realized-pnl   :realizedPNL   :realised-pnl)}))

(defn- project
  "Convert per-event java payloads (Bar, ContractDetails, …) to Clojure maps.
   For ContractDetails the nested Contract is also unwrapped so callers see
   plain values like :conid directly."
  [event]
  (case (:type event)
    :historical-data
    (assoc (->cmap (:bar event))
           :type :bar :req-id (:req-id event))

    :contract-details
    (let [m (->cmap (:contract-details event))
          m (cond-> m
              (:contract m) (update :contract ->cmap))]
      (assoc m :type :contract-details :req-id (:req-id event)))

    :position
    (project-position event)

    :update-portfolio
    (project-update-portfolio event)

    event))

(defn- warning-code?
  "TWS sends informational status updates with the :error event type but
   non-fatal codes:
     2100–2200       — connection/data-feed warnings
     10000–10999     — market-data farm status, delayed-data notices,
                        entitlement-missing notices (10167 = delayed-data
                        display; 10358 = fundamentals subscription absent).
                        These are info, NOT request-killing — TWS keeps
                        sending the rest of the data.
   Treat these as info, not failure — the request keeps streaming data."
  [code]
  (when (number? code)
    (or (<= 2100 code 2200)
        (<= 10000 code 10999))))

(defn- protobuf-event?
  "TWS emits a non-protobuf event + a protobuf duplicate for most callbacks.
   Drop the protobuf side; consumers want Clojure data."
  [event]
  (let [n (some-> (:type event) name)]
    (and n (re-find #"-proto-buf$" n))))

(defonce ^:private managed-accounts-atom (atom nil))

(defn- capture-managed-accounts! [event]
  (let [raw (or (:accounts-list event) (:accounts event) "")
        xs  (->> (str/split raw #",")
                 (map str/trim)
                 (remove str/blank?)
                 vec)]
    (log/infof "ibkr managed-accounts event raw=%s parsed=%s" raw xs)
    (when (seq xs)
      (reset! managed-accounts-atom xs))))

(defonce ^:private event-tap (atom nil))

(defn enable-event-tap!
  "Start logging every non-protobuf event the listener sees, until N events
   are observed. Useful for diagnosing 'why isn't event X arriving'."
  ([] (enable-event-tap! 100))
  ([limit]
   (reset! event-tap (atom {:limit limit :events []}))
   :ok))

(defn event-tap-results []
  (some-> @event-tap deref :events))

(defn- handle-event!
  [event]
  (when-not (protobuf-event? event)
   (try
    (let [t         (:type event)
          rid       (or (:req-id event) (:request-id event)
                        (:id event) (:ticker-id event) (:order-id event)
                        (when (or (= t :position) (= t :position-end))
                          @positions-rid)
                        (when (contains?
                                #{:update-portfolio :update-account-value
                                  :update-account-time :account-download-end}
                                t)
                          @account-updates-rid))
          entry     (when rid (get @pending rid))
          ;; tick-snapshot-end is terminal only for batch mode; see
          ;; stream-terminal-event-types for the rationale.
          terminal? (contains? (if (= :stream (:mode entry))
                                 stream-terminal-event-types
                                 batch-terminal-event-types)
                               t)]
      (when (= t :managed-accounts) (capture-managed-accounts! event))
      (when (= t :next-valid-id)    (capture-next-valid-id!    event))
      (when-let [tap @event-tap]
        (swap! tap update :events
          (fn [evs]
            (if (< (count evs) (:limit @tap))
              (conj evs (select-keys event [:type :req-id :request-id :code :message :accounts-list]))
              evs))))
      (cond
        ;; informational warning — accumulate but don't terminate the batch
        (and entry (= t :error) (warning-code? (:code event)))
        (let [{:keys [cb mode]} entry
              warn {:type :warning :code (:code event) :message (:message event)}]
          (case mode
            :batch  (accumulate! rid warn)
            :stream (try (cb warn) (catch Throwable _)))
          (log/debugf "ibkr warning rid=%s code=%s msg=%s"
                      rid (:code event) (:message event)))

        ;; Stream subscriptions should survive most errors — a stray TWS
        ;; error tagged with our rid used to dissoc the pending entry, which
        ;; killed the stream silently. Now: deliver the error to the cb as a
        ;; :warning event and KEEP the entry. Batch requests still terminate.
        (and entry (= t :error) (= :stream (:mode entry)))
        (let [warn {:type :warning :code (:code event) :message (:message event)}]
          (try ((:cb entry) warn) (catch Throwable _))
          (log/debugf "ibkr stream warning rid=%s code=%s msg=%s preserved subscription"
                      rid (:code event) (:message event)))

        (and entry (= t :error))
        (do
          (swap! pending assoc-in [rid :events]
                 [{:type :error :code (:code event) :message (:message event)}])
          (complete-pending! rid))

        entry
        (let [{:keys [cb mode]} entry
              ev          (project event)
              single?     (contains? single-shot-event-types t)
              done?       (or terminal? single?)]
          (case mode
            :batch  (do (when-not terminal? (accumulate! rid ev))
                        (when done?         (complete-pending! rid)))
            :stream (do (when-not terminal? (try (cb ev) (catch Throwable _)))
                        (when done?
                          (try (cb nil) (catch Throwable _))
                          (swap! pending dissoc rid)))))

        :else nil))
    (catch Throwable t
      (log/warnf t "handle-event! crashed on event %s" (:type event))))))


(defn- request-managed-accounts!
  "Explicit EClient.reqManagedAccts. IB also sends managedAccounts unsolicited
   after handshake, but firing it explicitly removes any race with the post-
   connect window."
  [conn]
  (try ((cs-fn 'request-managed-accounts) (:ecs conn)) (catch Throwable _)))

(defn- await-managed-accounts!
  "Poll `managed-accounts-atom` until populated, up to `deadline-ms`.
   Returns the vector of accounts (or nil if it never arrived)."
  [deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (or @managed-accounts-atom
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn connect!
  "Connect to TWS/Gateway. Blocks until IB's managedAccounts handshake
   arrives (or 3s deadline). Returns the connection map or :unavailable."
  [host port client-id]
  (try
    (let [c ((gw-fn 'connect) client-id host port #'handle-event!)]
      (reset! conn-atom c)
      (when c
        (request-managed-accounts! c)
        (await-managed-accounts! 3000))
      (or c :unavailable))
    (catch Exception e
      (reset! conn-atom nil)
      (log/warnf e "ibkr/connect! failed (host=%s port=%s client-id=%s)"
                 host port client-id)
      :unavailable)))

(defn disconnect! []
  (when-let [c @conn-atom]
    (try ((gw-fn 'disconnect) c) (catch Exception _)))
  (reset! conn-atom nil)
  (reset! pending {})
  (reset! managed-accounts-atom nil)
  nil)

(defn managed-accounts
  "Return the list of account-ids IB sent on the post-connect handshake.
   Nil if not connected or the event hasn't arrived yet."
  []
  @managed-accounts-atom)

(defn default-account
  "Return the first managed account-id, or nil. Most paper users have one."
  []
  (first (managed-accounts)))

(defn refresh-managed-accounts!
  "Manually re-fire `EClient.reqManagedAccts`. Returns the captured list after
   waiting up to `wait-ms` (default 2000)."
  ([] (refresh-managed-accounts! 2000))
  ([wait-ms]
   (when-let [c @conn-atom]
     (try ((cs-fn 'request-managed-accounts) (:ecs c))
          (catch Throwable t (log/warnf t "reqManagedAccts call threw")))
     (let [deadline (+ (System/currentTimeMillis) wait-ms)]
       (loop []
         (or @managed-accounts-atom
             (when (< (System/currentTimeMillis) deadline)
               (Thread/sleep 50)
               (recur))))))))

(defn- live-conn []
  (when-let [c @conn-atom]
    (try (when ((gw-fn 'is-connected?) c) c) (catch Exception _ nil))))

(defn is-connected? [] (boolean (live-conn)))

(defn connect [host port client-id] (connect! host port client-id))
(defn disconnect [] (disconnect!))

;;;
;;; req-market-data-snapshot returns a vector of opaque {:type :tick-price
;;; :field N :price V}-style events. The IBKR field codes are positional —
;;; below we fold them into a flat quote map with named keys, plus the four
;;; option-computation greek bundles (bid / ask / last / model).

(def ^:private tick-field->key
  "IBKR tick-type codes → canonical keys. Each price/size has both a live
   form (1/2/4/0/3/5/...) and a delayed form (66/67/68/69/70/71/...)."
  {;; prices
   1   :bid     66 :bid
   2   :ask     67 :ask
   4   :last    68 :last
   9   :close   75 :close
   14  :open
   6   :high    72 :high
   7   :low     73 :low
   ;; sizes
   8   :volume    74 :volume
   0   :bid-size  69 :bid-size
   3   :ask-size  70 :ask-size
   5   :last-size 71 :last-size})

(def ^:private opt-comp-prefix
  "tick-option-computation field codes → greek-bundle prefix."
  {10 :bid 11 :ask 12 :last 13 :model
   80 :bid 81 :ask 82 :last 83 :model})

(defn- ->double [v]
  (cond
    (nil? v)     nil
    (number? v)  (double v)
    :else (try (Double/parseDouble (str v)) (catch Throwable _ nil))))

(def ^:private market-data-type-codes
  {1 :live 2 :frozen 3 :delayed 4 :delayed-frozen})

(defn- ->greek-kw [prefix suffix-kw]
  (keyword (str (name prefix) "-" (name suffix-kw))))

(defn- handle-tick-price [acc {:keys [field price]}]
  (if-let [k (get tick-field->key field)]
    (if (and (number? price) (not= -1.0 price))
      (assoc acc k price)
      acc)
    acc))

(defn- handle-tick-size [acc {:keys [field size]}]
  (if-let [k (get tick-field->key field)]
    (assoc acc k (->double size))
    acc))

(defn- handle-tick-opt-comp [acc {:keys [field implied-vol delta gamma theta vega
                                          opt-price und-price pv-dividend]}]
  (if-let [pfx (get opt-comp-prefix field)]
    (let [acc' (-> acc
                   (assoc (->greek-kw pfx :iv)    implied-vol
                          (->greek-kw pfx :delta) delta
                          (->greek-kw pfx :gamma) gamma
                          (->greek-kw pfx :theta) theta
                          (->greek-kw pfx :vega)  vega
                          (->greek-kw pfx :opt-price) opt-price))]
      (cond-> acc'
        und-price   (assoc :underlying-price und-price)
        pv-dividend (assoc :pv-dividend      pv-dividend)))
    acc))

(defn- handle-market-data-type [acc {:keys [market-data-type]}]
  (assoc acc :data-mode
         (get market-data-type-codes market-data-type :unknown)))

(defn- handle-tick-string
  "tickString delivers a few comma-separated payloads. The one we care about
   is field 48 = RT_VOLUME → 'Last;LastSize;Time;TotalVolume;VWAP;SingleFlag'.
   Generic tick 233 must be requested for these to arrive."
  [acc {:keys [field value]}]
  (case (long (or field 0))
    48 (let [parts (str/split (str value) #";")
             [last-px _last-sz _t total-vol vwap _flag] parts
             num   #(try (Double/parseDouble %) (catch Throwable _ nil))]
         (cond-> acc
           (some-> last-px num)   (assoc :rt-last   (num last-px))
           (some-> total-vol num) (assoc :rt-volume (num total-vol))
           (some-> vwap num)      (assoc :vwap      (num vwap))))
    acc))

(defn- handle-warning [acc {:keys [code message]}]
  (update acc :warnings conj {:code code :message message}))

(defn- handle-error
  "Hard errors land as one-element event vectors when TWS rejects the
   request (bad contract, no security definition, etc). Capture them on
   :errors so the caller sees WHY the snapshot is empty instead of just
   getting back a blank map of nils."
  [acc {:keys [code message]}]
  (update acc :errors (fnil conj []) {:code code :message message}))

(def ^:private snapshot-event-handlers
  {:tick-price              handle-tick-price
   :tick-size               handle-tick-size
   :tick-string             handle-tick-string
   :tick-option-computation handle-tick-opt-comp
   :market-data-type        handle-market-data-type
   :warning                 handle-warning
   :error                   handle-error})

(defn normalize-snapshot
  "Fold a vector of tick events from req-market-data-snapshot into a flat
   quote map. Live and delayed tick codes both collapse into the same keys
   (:bid, :ask, :last, etc.). Sentinel value -1.0 (TWS for 'no data') is
   stripped. Option greeks land under :model-{iv,delta,gamma,theta,vega}
   and are also promoted to bare :iv/:delta/... for convenience."
  [events]
  (let [m (reduce (fn [acc e]
                    (if-let [handler (get snapshot-event-handlers (:type e))]
                      (handler acc e)
                      acc))
                  {:warnings []}
                  events)]
    (cond-> m
      (:model-iv m)    (assoc :iv    (:model-iv m))
      (:model-delta m) (assoc :delta (:model-delta m))
      (:model-gamma m) (assoc :gamma (:model-gamma m))
      (:model-theta m) (assoc :theta (:model-theta m))
      (:model-vega m)  (assoc :vega  (:model-vega m)))))

;; MUST be sequences (vectors of ints), NOT comma-separated strings —
;; ib-re-actor's `:to-ib :tick-list` translation does `(map ... val)` which
;; iterates a string character-by-character → TWS receives nonsense like
;; `1,0,0,,,1,0,1,…` and rejects with error 321. Sequence form joins
;; correctly to `100,101,…`.

(def all-generic-ticks
  "Generic-tick IDs for STK contracts. Asking for ticks that don't apply
   to STK is harmless — TWS silently omits them. But ticks invalid for
   the contract type get the WHOLE request rejected, so OPT/FUT use the
   typed constants below."
  [100 101 104 105 106 162 165 221 225 233 236 258 292 293 294 295 318 411 456 588 595])

(def option-generic-ticks
  "Generic-tick IDs valid for OPT contracts. TWS rejects the whole
   request with error 321 if you include 104 (Historical Vol — stocks
   only) or 162 (Index Future Premium — futures only). Tick 258
   (Fundamental Ratios) is also dropped — most accounts don't have
   the Fundamentals subscription, so it triggers warning 10358 which
   kills the snapshot batch before any prices arrive."
  [100 101 105 106 165 221 225 233 236 292 293 294 295 318 411 456 588 595])

(def ^:private market-data-type-aliases
  "Map any of the common spellings (keyword or int) onto the keyword the
   ib-re-actor translation layer expects.
   Live is :real-time-streaming in IBKR-speak; :live is a convenience alias."
  {:live                :real-time-streaming
   :real-time-streaming :real-time-streaming
   :frozen              :frozen
   :delayed             :delayed
   :delayed-frozen      :delayed-frozen
   1                    :real-time-streaming
   2                    :frozen
   3                    :delayed
   4                    :delayed-frozen})

(defn set-market-data-type!
  "Configure the market-data feed mode for the entire connection. Accepts
   :live (= :real-time-streaming), :frozen, :delayed, :delayed-frozen,
   or the raw int code (1–4). Affects every subsequent req-market-data /
   req-market-data-snapshot call on this connection."
  ([type-kw-or-int] (set-market-data-type! @conn-atom type-kw-or-int))
  ([conn type-kw-or-int]
   (let [kw (get market-data-type-aliases type-kw-or-int)]
     (when-not kw
       (throw (ex-info (str "unknown market-data type: " type-kw-or-int)
                       {:valid [:live :frozen :delayed :delayed-frozen]})))
     ((cs-fn 'request-market-data-type) (:ecs conn) kw)
     kw)))


(defn- send-historical-bars [ecs {:keys [req-id contract bar-size duration what-to-show]}]
  (let [[bs bsu] (parse-bar-size bar-size)
        [dur du] (parse-duration duration)]
    ((cs-fn 'request-historical-data) ecs req-id (->contract contract) ""
     dur du bs bsu (or what-to-show :trades) true 1 false)))

(defn- send-historical-iv [ecs {:keys [req-id contract bar-size duration]}]
  (let [[bs bsu] (parse-bar-size (or bar-size "1 day"))
        [dur du] (parse-duration  (or duration  "30 D"))]
    ((cs-fn 'request-historical-data) ecs req-id (->contract contract) ""
     dur du bs bsu :option-implied-volatility true 1 false)))

(defn- send-market-data [ecs {:keys [req-id contract tick-types snapshot]}]
  ((cs-fn 'request-market-data) ecs req-id (->contract contract)
   ;; ib-re-actor wants a SEQUENCE for tick-list (sees each item, str-joins
   ;; with commas). Empty sequence = no generic ticks.
   (or tick-types []) (boolean snapshot) false))

(defn- send-calc-implied-vol
  "Ask TWS to compute IV + greeks from a given option price and underlying
   price. Works after-hours / when there's no live market data, because
   we provide the prices."
  [ecs {:keys [req-id contract option-price underlying-price]}]
  ((cs-fn 'calculate-implied-volatility) ecs req-id (->contract contract)
   (double option-price) (double underlying-price)))

(defn- send-contract-details [ecs {:keys [req-id contract]}]
  ((cs-fn 'request-contract-details) ecs req-id (->contract contract)))

(defn- send-fundamentals [ecs {:keys [req-id contract report-type]}]
  ((cs-fn 'request-fundamental-data) ecs req-id (->contract contract)
   (or report-type "ReportSnapshot")))

(defn- send-account-summary [ecs {:keys [req-id tags]}]
  ((cs-fn 'request-account-summary) ecs req-id "All" (or tags "")))

(defn- send-account-updates
  "reqAccountUpdates is one-at-a-time per session: you (un)subscribe to a
   single account. The stream pumps :update-portfolio + :update-account-value
   + :update-account-time until cancelled. No req-id is involved."
  [ecs {:keys [subscribe? account-code]}]
  ((cs-fn 'request-account-updates) ecs (boolean subscribe?)
                                    (or account-code "")))

(defn- send-pnl
  "reqPnL streams daily/unrealized/realized PnL for an account. Carries a
   req-id (unlike reqAccountUpdates) so events route normally."
  [ecs {:keys [req-id account-code model-code]}]
  ((cs-fn 'request-pnl) ecs req-id (or account-code "") (or model-code "")))

(defn- send-pnl-single
  "reqPnLSingle streams per-position dailyPnL/unrealizedPnL/realizedPnL AND
   a live :value (market value) using IB's server-side prices. Works around
   the stinginess of reqAccountUpdates' updatePortfolio, which is reluctant
   to fire on pure price changes."
  [ecs {:keys [req-id account-code model-code conid]}]
  ((cs-fn 'request-pnl-single) ecs req-id (or account-code "")
                               (or model-code "") (int conid)))

(defn- send-news-article [ecs {:keys [req-id provider-code article-id]}]
  ((cs-fn 'request-news-article) ecs req-id provider-code article-id))

(defn- send-historical-news [ecs {:keys [req-id conid provider-codes
                                         start-date-time end-date-time
                                         total-results]}]
  ((cs-fn 'request-historical-news) ecs req-id conid
   (or provider-codes "BRFG+DJNL") (or start-date-time "")
   (or end-date-time "") (or total-results 10)))

(defn- send-scanner-subscription [ecs {:keys [req-id params]}]
  ((cs-fn 'request-scanner-subscription) ecs req-id params nil nil))

(defn- send-option-chain [ecs {:keys [req-id underlying]}]
  (let [sym  (:symbol underlying)
        sec  (or (some-> (:sec-type underlying) name) "STK")
        cnid (or (:conid underlying) 0)]
    ((cs-fn 'request-sec-def-option-parameters) ecs req-id sym "" sec cnid)))

(defn- send-place-order [ecs {:keys [order-id contract order]}]
  ((cs-fn 'place-order) ecs order-id contract order))

(defn- send-cancel-order [ecs {:keys [order-id]}]
  ((cs-fn 'cancel-order) ecs order-id))

(def ^:private request-senders
  {:req-historical-bars     send-historical-bars
   :req-historical-iv       send-historical-iv
   :req-market-data         send-market-data
   :req-contract-details    send-contract-details
   :req-fundamentals        send-fundamentals
   :req-positions           (fn [ecs _] ((cs-fn 'request-positions) ecs))
   :req-account-summary     send-account-summary
   :req-account-updates     send-account-updates
   :req-pnl                 send-pnl
   :req-pnl-single          send-pnl-single
   :req-calc-implied-vol    send-calc-implied-vol
   :req-news-providers      (fn [ecs _] ((cs-fn 'request-news-providers) ecs))
   :req-news-article        send-news-article
   :req-historical-news     send-historical-news
   :req-scanner-subscription send-scanner-subscription
   :req-option-chain        send-option-chain
   :place-order             send-place-order
   :cancel-order            send-cancel-order})

(defn send-request!
  "Translate req to a TWS API call. conn must contain :ecs; req must carry
   :type and :req-id. Tests stub via with-redefs on this var."
  [conn req]
  (let [ecs (:ecs conn)
        sender (get request-senders (:type req))]
    (if sender
      (sender ecs req)
      (throw (ex-info (str "unsupported req :type " (:type req)) {:req req})))
    nil))

(defn cancel-sub!
  "Best-effort cancel by req-id. Walks likely cancel fns and clears the pending entry."
  [conn req-id]
  (let [ecs (:ecs conn)]
    (doseq [fname '[cancel-market-data cancel-historical-data
                    cancel-fundamental-data cancel-scanner-subscription
                    cancel-account-summary]]
      (try ((cs-fn fname) ecs req-id) (catch Throwable _))))
  (swap! pending dissoc req-id)
  nil)


(defn dispatch-batch!
  "Send a request in batch mode: accumulate events, fire cb once on terminal.
   Returns the allocated req-id."
  [conn req cb]
  (let [id (req-id/next-id!)]
    (register-pending! id cb :batch)
    (pacer/acquire!)
    (send-request! conn (assoc req :req-id id))
    id))

(defn dispatch-stream!
  "Send a request in stream mode: invoke cb per event. Returns the allocated req-id."
  [conn req cb]
  (let [id (req-id/next-id!)]
    (register-pending! id cb :stream)
    (pacer/acquire!)
    (send-request! conn (assoc req :req-id id))
    id))


(defn req-historical-bars
  "Request a historical bar series. 4-arg form defaults what-to-show to
   :trades; 5-arg form takes an explicit keyword (:option-implied-volatility,
   :historical-volatility, :midpoint, …)."
  ([conn contract bar-size duration cb]
   (req-historical-bars conn contract bar-size duration :trades cb))
  ([conn contract bar-size duration what-to-show cb]
   (pacer/record-hist-request! (:symbol contract) bar-size)
   (dispatch-batch! conn {:type :req-historical-bars
                           :contract contract :bar-size bar-size
                           :duration duration :what-to-show what-to-show}
                    cb)))

(defn req-historical-iv [conn contract expiry cb]
  (dispatch-batch! conn {:type :req-historical-iv
                          :contract contract :expiry expiry} cb))

(defn req-market-data
  ([conn contract tick-types cb] (req-market-data conn contract tick-types cb default-subs))
  ([conn contract tick-types cb sub-mgr]
   (let [cid (or (:conid contract) (:symbol contract))
         sub (subs/subscribe! sub-mgr cid :market-data)]
     (if (:error sub)
       sub
       (dispatch-stream! conn {:type :req-market-data :contract contract
                                :tick-types tick-types :snapshot false} cb)))))

(defn req-market-data-snapshot [conn contract tick-types cb]
  (dispatch-batch! conn {:type :req-market-data :contract contract
                          :tick-types tick-types :snapshot true} cb))

(defn start-snapshot!
  "Fire a one-shot market-data snapshot and return an atom that accumulates the
   raw tick events. Read it via `normalize-snapshot` after a collection window
   — `:tick-snapshot-end` is unreliable, so callers time-box instead of waiting
   for it. The IB snapshot auto-cancels, so this is not a persistent sub."
  [conn contract]
  (let [acc (atom [])]
    (dispatch-stream! conn {:type :req-market-data :contract contract :snapshot true}
                      (fn [ev] (when ev (swap! acc conj ev))))
    acc))

(defn req-option-chain [conn underlying expiry cb]
  (dispatch-batch! conn {:type :req-option-chain :underlying underlying :expiry expiry} cb))

(defn req-place-order!
  "Submit an order to IB. Allocates the next order-id from TWS's monotonic
   sequence (NOT the req-id sequence), registers cb under that id so future
   :order-status / :open-order / :error events for it can be routed via the
   normal handle-event! plumbing, and fires placeOrder. Returns the assigned
   order-id, or :unavailable if TWS hasn't seeded :next-valid-id yet.

   `contract` is a regular contract map (or a BAG map with :combo-legs for
   multi-leg). `order` carries the IB order fields — :action (\"BUY\"/\"SELL\"),
   :total-quantity (number), :order-type (\"MKT\"/\"LMT\"), :lmt-price (when
   LMT), :tif (\"DAY\"/\"GTC\"), :outside-rth? (bool), :transmit? (bool, default
   true). cb may be nil if the caller doesn't need status updates."
  [conn contract order cb]
  (try
    (let [id (next-order-id!)]
      (when cb (register-pending! id cb :stream))
      (send-request! conn {:type :place-order :order-id id
                           :contract contract :order order})
      id)
    (catch clojure.lang.ExceptionInfo e
      (log/warnf "req-place-order!: %s" (.getMessage e))
      :unavailable)))

(defn req-cancel-order!
  "Cancel a previously-submitted order by id. Fire-and-forget; cancellation
   status arrives via the same callback the order was placed with (if any).
   Returns the order-id passed in."
  [conn order-id]
  (send-request! conn {:type :cancel-order :order-id order-id})
  order-id)

(defn req-contract-details [conn contract cb]
  (dispatch-batch! conn {:type :req-contract-details :contract contract} cb))

(defn req-calc-implied-vol
  "Ask TWS to compute IV + greeks from a given option price and underlying
   price. Works any time of day (no live market data needed) — exactly
   the after-hours greeks path.

   Delivers :tick-option-computation events on the cb (field 13 = model).
   Caller is responsible for cancelling via `cancel-calc-implied-vol`."
  [conn contract option-price underlying-price cb]
  (dispatch-stream! conn {:type             :req-calc-implied-vol
                          :contract         contract
                          :option-price     option-price
                          :underlying-price underlying-price}
                    cb))

(defn cancel-calc-implied-vol [conn req-id]
  (try ((cs-fn 'cancel-calculate-implied-volatility) (:ecs conn) req-id)
       (catch Throwable _))
  (swap! pending dissoc req-id)
  nil)

(defn req-fundamentals [conn contract report-type cb]
  (dispatch-batch! conn {:type :req-fundamentals :contract contract
                          :report-type report-type} cb))

(defn req-positions [conn cb]
  (let [id (dispatch-stream! conn {:type :req-positions} cb)]
    (reset! positions-rid id)
    id))

(defn req-account-updates
  "Subscribe to live portfolio + account-value updates for `account-code`.
   Pi mash `cb` with each :update-portfolio / :update-account-value /
   :update-account-time event. Returns the (id-less) subscription id; pass
   to `cancel-account-updates` to stop."
  [conn account-code cb]
  (let [id (dispatch-stream! conn {:type         :req-account-updates
                                    :subscribe?   true
                                    :account-code account-code} cb)]
    (reset! account-updates-rid id)
    id))

(defn cancel-account-updates
  "Stop the active reqAccountUpdates stream. Sends reqAccountUpdates(false)
   and clears the routing atom. Safe to call when nothing's subscribed."
  [conn account-code]
  (try
    (send-request! conn {:type :req-account-updates
                         :subscribe? false
                         :account-code account-code})
    (catch Throwable _))
  (when-let [rid @account-updates-rid]
    (swap! pending dissoc rid)
    (reset! account-updates-rid nil))
  nil)

(defn req-pnl
  "Subscribe to live account-level PnL (daily / unrealized / realized).
   The :pnl events carry a :req-id so routing is normal — no rid fallback
   needed. Returns the subscription id; pass to cancel-pnl."
  [conn account-code cb]
  (dispatch-stream! conn {:type :req-pnl :account-code account-code} cb))

(defn cancel-pnl
  "Stop a previously-started reqPnL subscription."
  [conn req-id]
  (try ((cs-fn 'cancel-pnl) (:ecs conn) req-id) (catch Throwable _))
  (swap! pending dissoc req-id)
  nil)

(defn req-pnl-single
  "Subscribe to live per-position PnL + market value for `conid` in
   `account-code`. Returns the subscription id; pass to cancel-pnl-single."
  [conn account-code conid cb]
  (dispatch-stream! conn {:type :req-pnl-single
                          :account-code account-code
                          :conid conid} cb))

(defn cancel-pnl-single
  "Stop a previously-started reqPnLSingle subscription."
  [conn req-id]
  (try ((cs-fn 'cancel-pnl-single) (:ecs conn) req-id) (catch Throwable _))
  (swap! pending dissoc req-id)
  nil)

(defn req-account-summary [conn tags cb]
  (dispatch-stream! conn {:type :req-account-summary :tags tags} cb))

(defn req-scanner-subscription
  ([conn params cb] (req-scanner-subscription conn params cb default-subs))
  ([conn params cb sub-mgr]
   (let [skey (or (:scan-code params) (str (gensym "scan-")))
         sub  (subs/subscribe! sub-mgr skey :scanner)]
     (if (:error sub)
       sub
       (dispatch-stream! conn {:type :req-scanner-subscription :params params} cb)))))

(defn req-news-providers [conn cb]
  (dispatch-batch! conn {:type :req-news-providers} cb))

(defn req-news-article [conn provider-code article-id cb]
  (dispatch-batch! conn {:type :req-news-article
                          :provider-code provider-code :article-id article-id} cb))


(defn ->order
  "Build an IBKR Order map. Required keys: :action (:buy/:sell), :quantity.
   Common optional keys:
     :type        :market (default), :limit, :stop, :stop-limit
     :limit-price (required for :limit / :stop-limit)
     :stop-price  (required for :stop / :stop-limit)
     :time-in-force :day (default), :good-to-close, :immediate-or-cancel,
                    :fill-or-kill
     :outside-regular-trading-hours? false (default)
     :transmit?   true (default) — false stages the order in TWS without sending"
  [{:keys [action quantity type limit-price stop-price time-in-force
           outside-regular-trading-hours? transmit?]
    :or   {type :market time-in-force :day transmit? true
           outside-regular-trading-hours? false}}]
  (cond-> {:action                          action
           :quantity                        quantity
           :type                            type
           :time-in-force                   time-in-force
           :transmit?                       transmit?
           :outside-regular-trading-hours?  outside-regular-trading-hours?}
    limit-price (assoc :limit-price limit-price)
    stop-price  (assoc :stop-price  stop-price)))

(defn place-order
  "Place an order against TWS. Returns the allocated order-id.
   `contract` and `order` are Clojure maps; pass through (->contract …) and
   (->order …) first for keyword coercion.

   Order events (status, fills, errors) arrive on the global event stream
   keyed by :order-id. Pass an optional `cb` to register a stream listener
   that will fire on each event for this order until you cancel the
   registration with (cancel-sub! conn order-id)."
  ([conn contract order] (place-order conn contract order nil))
  ([conn contract order cb]
   (let [oid     ((lib-fn 'ib-re-actor-976-plus.gateway 'next-id) conn)
         contract (->contract contract)
         order    (->order (if (:action order) order
                              (assoc order :action (:action order))))]
     (when cb
       (register-pending! oid cb :stream))
     ((cs-fn 'place-order) (:ecs conn) oid contract (assoc order :order-id oid))
     oid)))

(defn cancel-order
  "Cancel an open order by order-id. No-op if the order doesn't exist."
  [conn order-id]
  ((cs-fn 'cancel-order) (:ecs conn) order-id)
  (swap! pending dissoc order-id)
  nil)


(defn pending-ids
  "Set of req-ids with an active callback registered (batch or stream)."
  []
  (set (keys @pending)))

(defn debug-pending
  "Snapshot of the pending registry — keys are req-ids, values are
   {:mode kw :event-count n :event-types [...] :first-event ev}. Use
   in the REPL to diagnose timeouts: an entry that's still here means the
   listener never received a terminal event for that request."
  []
  (into {}
    (map (fn [[rid {:keys [mode events]}]]
           [rid {:mode         mode
                 :event-count  (count events)
                 :event-types  (->> events (map :type) frequencies)
                 :first-event  (first events)}]))
    @pending))

(defn install-debug-tap!
  "Install a temporary listener that prints every TWS event to stdout for
   `duration-ms` then removes itself. Use for diagnosing missing events."
  ([] (install-debug-tap! 10000))
  ([duration-ms]
   (when-let [conn @conn-atom]
     (let [subs (:subscribers conn)
           key  (keyword (str "debug-tap-" (System/nanoTime)))]
       (swap! subs assoc key (fn [evt] (println key (:type evt) evt)))
       (future
         (Thread/sleep duration-ms)
         (swap! subs dissoc key)
         (println key "removed"))
       key))))

(defn next-request-id! [] (req-id/next-id!))
(defn register-request! [id cb] (req-id/register! id cb))
(defn complete-request! [id] (req-id/complete! id))
(defn pacer-stats [] (pacer/stats))
(defn active-subscriptions [] (subs/active-subs default-subs))
(defn subscribe-market-data! [conid kind] (subs/subscribe! default-subs conid kind))
(defn unsubscribe-market-data! [conid]    (subs/unsubscribe! default-subs conid))

(defn request-positions       []     (if (live-conn) :pending :unavailable))
(defn request-account-summary []     (if (live-conn) :pending :unavailable))
(defn request-contract-details [_]   (if (live-conn) :pending :unavailable))
(defn request-market-data [_]        (if (live-conn) :pending :unavailable))
(defn account-summary [_] :unavailable)
(defn positions       [_] :unavailable)
