(ns options-trader.data.ibkr.pacer
  "Token-bucket rate limiter for IBKR API calls.
   Use create-pacer to obtain a fresh instance; pass it as the first argument
   to acquire!, with-budget, stats, drain!, and the hist-* functions.
   A module-level default-pacer is kept for backward-compatible callers
   that do not manage their own instance.
   Default configuration: 50 tokens/sec, burst capacity 50.")

(def ^:const default-rate     50)             ; tokens replenished per second
(def ^:const default-capacity 50)             ; max burst tokens
(def ^:const hist-window-ms   (* 10 60 1000)) ; 10 minutes in milliseconds
(def ^:const hist-window-max  60)             ; max historical requests per window


(defn create-pacer
  "Return a new opaque pacer instance.
   Accepts optional :rate (tokens/sec, default 50) and
   :burst (max token capacity, default 50)."
  ([] (create-pacer {}))
  ([{:keys [rate burst] :or {rate default-rate burst default-capacity}}]
   {:bucket      (atom {:tokens   (double burst)
                        :capacity (double burst)
                        :rate     (double rate)
                        :last-ms  (System/currentTimeMillis)})
    :queue       (atom [])
    :hist-state  (atom {})       ; per-(symbol, bar-size) — 6-per-2s rule
    :hist-global (atom [])}))    ; connection-wide — 60-per-10min rule

(defonce default-pacer (create-pacer))


(defn- refill [state]
  (let [now       (System/currentTimeMillis)
        elapsed-s (/ (double (- now (:last-ms state))) 1000.0)
        new-tok   (min (:capacity state)
                       (+ (:tokens state) (* (:rate state) elapsed-s)))]
    (assoc state :tokens new-tok :last-ms now)))


(defn acquire!
  "Consume one token from pacer.
   Returns true when a token was acquired, false when the bucket is empty."
  ([pacer]
   (let [acquired (volatile! false)]
     (swap! (:bucket pacer)
       (fn [s]
         (let [s' (refill s)]
           (if (>= (:tokens s') 1.0)
             (do (vreset! acquired true)
                 (update s' :tokens #(- % 1.0)))
             s'))))
     @acquired))
  ([] (acquire! default-pacer)))

(defn enqueue!
  "Add thunk work to the pending queue of pacer.
   Returns {:status :queued :queue-depth n}."
  ([pacer work]
   (let [q (swap! (:queue pacer) conj work)]
     {:status :queued :queue-depth (count q)}))
  ([work] (enqueue! default-pacer work)))

(defmacro with-budget
  "Execute body if a token is available from pacer; otherwise queue the thunk.
   Returns {:status :ok :result v} or {:status :queued :queue-depth n}."
  [pacer & body]
  `(if (acquire! ~pacer)
     {:status :ok :result (do ~@body)}
     (enqueue! ~pacer (fn [] ~@body))))

(defn drain!
  "Process queued thunks as tokens become available. Returns count processed."
  ([pacer]
   (loop [n 0]
     (let [q @(:queue pacer)]
       (if (and (seq q) (acquire! pacer))
         (do (swap! (:queue pacer) (comp vec rest))
             ((first q))
             (recur (inc n)))
         n))))
  ([] (drain! default-pacer)))

(defn stats
  "Return {:queue-depth n :tokens m} snapshot of pacer state."
  ([pacer]
   (swap! (:bucket pacer) refill)
   {:queue-depth (count @(:queue pacer))
    :tokens      (long (:tokens @(:bucket pacer)))})
  ([] (stats default-pacer)))


(defn prune-window
  "Remove timestamps older than hist-window-ms from now."
  [timestamps now]
  (filterv #(> % (- now hist-window-ms)) timestamps))

(defn record-hist-request!
  "Record a historical-data request — bumps the per-(symbol, bar-size)
   window (6-per-2s rule) and the connection-wide window (60-per-10min)."
  ([pacer symbol bar-size]
   (let [k   [symbol bar-size]
         now (System/currentTimeMillis)]
     (swap! (:hist-state  pacer) update k
            (fn [ts] (conj (prune-window (or ts []) now) now)))
     (swap! (:hist-global pacer)
            (fn [ts] (conj (prune-window (or ts []) now) now)))))
  ([symbol bar-size] (record-hist-request! default-pacer symbol bar-size)))

(defn hist-request-count
  ([pacer symbol bar-size]
   (count (prune-window (get @(:hist-state pacer) [symbol bar-size] [])
                        (System/currentTimeMillis))))
  ([symbol bar-size] (hist-request-count default-pacer symbol bar-size)))

(defn hist-global-count
  ([pacer]
   (count (prune-window (or @(:hist-global pacer) []) (System/currentTimeMillis))))
  ([] (hist-global-count default-pacer)))

(defn reset-hist-global!
  "Clear the connection-wide historical-data window. IB resets its own
   60/10-min counter when the API client disconnects, so the in-process
   tally is also stale after a reconnect — call this on reconnect to
   keep them in sync."
  ([pacer] (reset! (:hist-global pacer) []))
  ([]      (reset-hist-global! default-pacer)))

(defn can-request-historical?
  ([pacer symbol bar-size]
   (and (< (hist-request-count pacer symbol bar-size) hist-window-max)
        (< (hist-global-count  pacer)                  hist-window-max)))
  ([symbol bar-size] (can-request-historical? default-pacer symbol bar-size)))

(defn await-hist-slot!
  "Block until a historical-data slot frees up. Polls every poll-ms;
   returns false after max-wait-ms (defaults to the 10-min window length
   plus 30s slack — enough for the oldest entry to age out)."
  ([pacer symbol bar-size]
   (await-hist-slot! pacer symbol bar-size
                     {:poll-ms 500 :max-wait-ms (+ hist-window-ms 30000)}))
  ([pacer symbol bar-size {:keys [poll-ms max-wait-ms]
                            :or {poll-ms 500 max-wait-ms (+ hist-window-ms 30000)}}]
   (let [deadline (+ (System/currentTimeMillis) (long max-wait-ms))]
     (loop []
       (cond
         (can-request-historical? pacer symbol bar-size) true
         (> (System/currentTimeMillis) deadline)         false
         :else (do (Thread/sleep (long poll-ms)) (recur)))))))


(defn set-tokens!
  "Override token count in pacer. Updates last-ms to prevent immediate refill.
   Test helper only."
  ([pacer n]
   (swap! (:bucket pacer) assoc :tokens (double n) :last-ms (System/currentTimeMillis)))
  ([n] (set-tokens! default-pacer n)))

(defn reset-state!
  "Reset pacer to initial full-bucket state (preserving rate/capacity). Test helper."
  ([pacer]
   (let [{:keys [capacity rate]} @(:bucket pacer)]
     (reset! (:bucket pacer) {:tokens   capacity
                               :capacity capacity
                               :rate     rate
                               :last-ms  (System/currentTimeMillis)})
     (reset! (:queue pacer) [])
     (reset! (:hist-state pacer) {})))
  ([] (reset-state! default-pacer)))
