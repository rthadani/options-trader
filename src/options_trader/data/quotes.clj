(ns options-trader.data.quotes
  "Detailed single-symbol quote: a live IB market-data snapshot fused with
   DB-derived metrics (14-day average volume, put/call OI + volume ratios).

   IB delivers bid/ask/last/day-volume/day-high/day-low/close natively. VWAP
   arrives via the RT_VOLUME tick-string (generic tick 233). The 14-day avg
   volume and put/call ratios aren't shipped by IB at all — we compute from
   bars_daily and read from latest_indicators (which the option-chain-agg
   refresh has already populated)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.ibkr :as ibkr]))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

;;; ── Component fetches ─────────────────────────────────────────────────────

(defn- ->right
  "ib-re-actor's :right translation table expects keywords (:put / :call)
   that map to com.ib.client.Types$Right. Strings or 1-char letters get
   silently dropped by the translation layer → IB Contract.right ends up
   null → TWS rejects the request → snapshot times out. Normalize here."
  [r]
  (case (some-> r str clojure.string/lower-case)
    ("c" "call" ":call") :call
    ("p" "put"  ":put")  :put
    r))

(defn- option-contract
  "Build an IB OPT contract for snapshot/details requests."
  [{:keys [symbol expiry strike right exchange currency multiplier]}]
  {:symbol     symbol
   :sec-type   "OPT"
   :exchange   (or exchange "SMART")
   :currency   (or currency "USD")
   :last-trade-date-or-contract-month (str expiry)   ;; YYYYMMDD
   :strike     (double strike)
   :right      (->right right)
   :multiplier (or multiplier "100")})

(defn option-quote
  "Live snapshot for a single options contract: bid/ask/last + the greeks
   bundle (model + bid + ask + last IV/delta/gamma/theta/vega). Returns the
   normalised quote map, :unavailable if TWS rejected the request, or
   :timeout if no events arrived in `timeout-ms` (default 8 s — options
   snapshots are slower than stocks).

   Pass either a fully-typed contract via :contract, or the component args:
     :symbol \"MPWR\"
     :expiry \"20260619\"    (YYYYMMDD)
     :strike 800
     :right  \"C\"            (or \"P\" / \"Call\" / \"Put\")"
  [conn opts & {:keys [timeout-ms] :or {timeout-ms 8000}}]
  (let [contract (or (:contract opts) (option-contract opts))
        p       (promise)
        rid     (ibkr/req-market-data-snapshot
                  conn contract ibkr/all-generic-ticks
                  (fn [evs] (deliver p evs)))]
    (cond
      (= :unavailable rid) :unavailable
      :else (let [evs (deref p timeout-ms ::timeout)]
              (cond
                (= ::timeout evs) :timeout
                (nil? evs)        :timeout
                :else             (assoc (ibkr/normalize-snapshot evs)
                                         :contract contract))))))

(defn snapshot-from-ib
  "One-shot market-data snapshot. Returns a flat quote map (bid/ask/last/
   high/low/volume/...) or :unavailable when TWS rejects the request. On
   :timeout we explicitly cancel the request — otherwise the subscription
   leaks one of TWS's 100 simultaneous-market-data slots, and subsequent
   snapshots eventually hang waiting for a slot that never frees.
   timeout-ms defaults to 5 s."
  [conn symbol & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [p (promise)
        rid (ibkr/req-market-data-snapshot
              conn
              (ibkr/->contract symbol)
              ibkr/all-generic-ticks
              (fn [evs] (deliver p evs)))]
    (cond
      (= :unavailable rid) :unavailable
      :else (let [evs (deref p timeout-ms ::timeout)]
              (cond
                (= ::timeout evs)
                (do (try (ibkr/cancel-sub! conn rid) (catch Throwable _))
                    :timeout)

                (nil? evs)
                :timeout

                :else (ibkr/normalize-snapshot evs))))))

(defn- vwap-from-bars-intraday
  "Try to compute today's VWAP from bars_intraday.
   VWAP = Σ(typical-price × volume) / Σ(volume) where typical-price =
   (high + low + close) / 3. Returns nil when there are no rows today."
  [ds symbol]
  (try
    (let [row (jdbc/execute-one! ds
                ["SELECT
                    SUM(((high + low + close) / 3.0) * volume) AS num,
                    SUM(volume)                                AS den
                  FROM bars_intraday
                  WHERE symbol = ?
                    AND bar_ts >= CURRENT_DATE
                    AND volume IS NOT NULL AND volume > 0" symbol]
                as-lower)
          num (:num row)
          den (:den row)]
      (when (and (number? num) (number? den) (pos? den))
        (double (/ num den))))
    (catch Throwable _ nil)))

(defn- vwap-from-ib-historical
  "Synchronous one-shot historical-data fetch for today's '1 day' bar. Each
   IB Bar carries a `wap` (weighted-average-price) field — that's the
   broker's true session VWAP. Used as the fallback for `vwap` when
   bars_intraday is empty and the snapshot RT_VOLUME path is blocked by
   IB's streaming-only design."
  [conn symbol]
  (try
    (let [p (promise)]
      (ibkr/req-historical-bars conn (ibkr/->contract symbol) "1 day" "1 D"
                                (fn [evs] (deliver p evs)))
      (let [evs (deref p 4000 nil)]
        (when (sequential? evs)
          (some-> (last evs) (#(or (:wap %) (:w-a-p %)))
                  double))))
    (catch Throwable _ nil)))

(defn- vwap
  "Resolve today's VWAP through three fallbacks in order:
   1. Snapshot's RT_VOLUME (streaming-only — never available for snapshots
      in practice, but the path stays in case IB ever changes it).
   2. bars_intraday for today (if intraday refresh ran).
   3. Live IB historical-bars request for today's 1-day bar (always works
      when you have market-data permissions; ~300-500ms IB round trip)."
  [conn ds symbol snap]
  (or (:vwap snap)
      (vwap-from-bars-intraday ds symbol)
      (vwap-from-ib-historical conn symbol)))

(defn avg-volume
  "Trailing N-day average daily volume from bars_daily. Uses the most recent
   N completed bars (today's bar may or may not be present)."
  [ds symbol n-days]
  (-> (jdbc/execute-one! ds
        [(str "SELECT AVG(volume) AS avg FROM ("
              "  SELECT volume FROM bars_daily WHERE symbol = ?"
              "    ORDER BY bar_date DESC LIMIT ?)")
         symbol n-days]
        as-lower)
      :avg))

(defn pc-ratios
  "Read the latest put_call_oi_ratio and put_call_volume_ratio for symbol
   from latest_indicators (populated by indicators.option-chain-agg). Returns
   {:pc-oi-ratio nil-or-double :pc-vol-ratio nil-or-double}, both nil when
   the option-chain refresh hasn't run for this symbol yet."
  [ds symbol]
  (let [row (jdbc/execute-one! ds
              ["SELECT put_call_oi_ratio AS oi, put_call_volume_ratio AS vol
                  FROM latest_indicators WHERE symbol = ?" symbol]
              as-lower)]
    {:pc-oi-ratio  (:oi row)
     :pc-vol-ratio (:vol row)}))

;;; ── Compose ───────────────────────────────────────────────────────────────

(defn detailed-quote
  "Full quote for `symbol`. Combines a live IB snapshot with DB-computed
   averages and option-chain ratios. Pass `conn` (an IB client) and `ds` (a
   DuckDB datasource). Optional :avg-window selects the average-volume window
   (defaults to 14 days).

   Returned map (any key may be missing if its source had no data):
     :symbol
     :bid :ask :last :open :high :low :close
     :bid-size :ask-size :last-size
     :day-volume         (live volume so far today, from IB)
     :day-vwap           (intraday VWAP from RT_VOLUME tick)
     :avg-volume-Nd      (e.g. :avg-volume-14d)
     :volume-ratio       (day-volume ÷ avg-volume-Nd)
     :pc-oi-ratio        (put OI ÷ call OI for the front month)
     :pc-vol-ratio       (put volume ÷ call volume)
     :data-mode          :live | :delayed | :frozen | :delayed-frozen
     :warnings           (vector of IB warning maps; informational only)

   Returns :unavailable if IB rejected the symbol entirely. Partial data is
   OK — the caller decides whether missing fields are fatal."
  [conn ds symbol & {:keys [timeout-ms avg-window]
                     :or   {timeout-ms 5000 avg-window 14}}]
  (let [snap (snapshot-from-ib conn symbol :timeout-ms timeout-ms)]
    (cond
      (= :unavailable snap) :unavailable
      :else
      (let [snap        (if (map? snap) snap {})
            ;; rt-volume from RT_VOLUME ticks overrides the periodic tick-size
            ;; volume when both are present (it's the most recent total).
            day-volume  (or (:rt-volume snap) (:volume snap))
            avg-vol     (avg-volume ds symbol avg-window)
            ;; VWAP resolution: snapshot first (always nil for snapshots),
            ;; then today's intraday bars, then a live IB historical-bar
            ;; request (which carries IB's own `wap` field).
            vwap        (vwap conn ds symbol snap)
            pc          (pc-ratios ds symbol)
            avg-key     (keyword (str "avg-volume-" avg-window "d"))]
        (-> {:symbol symbol}
            (merge (select-keys snap
                                [:bid :ask :last :open :high :low :close
                                 :bid-size :ask-size :last-size
                                 :data-mode :warnings]))
            (cond-> day-volume        (assoc :day-volume day-volume)
                    vwap              (assoc :day-vwap (double vwap))
                    avg-vol           (assoc avg-key (double avg-vol))
                    (and day-volume avg-vol (pos? avg-vol))
                    (assoc :volume-ratio (/ (double day-volume) (double avg-vol))))
            (merge pc))))))
