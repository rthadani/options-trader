(ns options-trader.data.quotes
  "Detailed single-symbol quote: a live market-data snapshot from a pluggable
   IMarketDataSource fused with DB-derived metrics (14-day average volume,
   put/call OI + volume ratios, intraday VWAP).

   All backend-specific plumbing lives behind data.market-data — this
   namespace operates on the protocol so an alternate backend (Polygon,
   Alpaca, etc.) can be swapped in by passing a different source:
     (md/make-source {:type :ibkr :ib-client conn})
     (md/make-source {:type :mock :responses {...}})"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.market-data :as md]))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

;;; ── DB-side metrics ───────────────────────────────────────────────────────

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
   from latest_indicators (populated by indicators.option-chain-agg).
   Returns {:pc-oi-ratio nil-or-double :pc-vol-ratio nil-or-double}."
  [ds symbol]
  (let [row (jdbc/execute-one! ds
              ["SELECT put_call_oi_ratio AS oi, put_call_volume_ratio AS vol
                  FROM latest_indicators WHERE symbol = ?" symbol]
              as-lower)]
    {:pc-oi-ratio  (:oi row)
     :pc-vol-ratio (:vol row)}))

(defn- vwap-from-bars-intraday
  "Today's VWAP from bars_intraday: Σ((H+L+C)/3 × V) / Σ V. Returns nil
   when there are no rows for today."
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

(defn- resolve-vwap
  "Three-fallback VWAP: snapshot RT_VOLUME → bars_intraday computation →
   the source's own session-VWAP (e.g. IB's historical-bar WAP field).
   Any layer returning nil falls through to the next."
  [snap ds symbol src]
  (or (:vwap snap)
      (vwap-from-bars-intraday ds symbol)
      (md/session-vwap src symbol)))

;;; ── Helpers ───────────────────────────────────────────────────────────────

(defn- price-of
  "Pick the most representative price from a snapshot: last → close → mid."
  [snap]
  (when (map? snap)
    (or (:last snap)
        (:close snap)
        (when (and (:bid snap) (:ask snap))
          (/ (+ (double (:bid snap)) (double (:ask snap))) 2.0)))))

;;; ── Public composition fns (operate on IMarketDataSource) ─────────────────

(defn detailed-quote
  "Full quote for `symbol`. Combines a market-data snapshot from `src` with
   DB-computed averages and option-chain ratios from `ds`.

   :avg-window defaults to 14 days.

   Returns :unavailable when the source rejects the symbol. Otherwise a
   flat quote map — see data.market-data for field details."
  [src ds symbol & {:keys [avg-window] :or {avg-window 14}}]
  (let [snap (md/snapshot-stk src symbol)]
    (cond
      (= :unavailable snap) :unavailable
      :else
      (let [snap        (if (map? snap) snap {})
            day-volume  (or (:rt-volume snap) (:volume snap))
            avg-vol     (avg-volume ds symbol avg-window)
            vwap        (resolve-vwap snap ds symbol src)
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

(defn option-quote-snapshot
  "Snapshot-mode option quote. Returns a quote map, :unavailable, or :timeout."
  [src opts]
  (md/snapshot-opt src opts))

(defn option-quote
  "Streaming option quote: opens a market-data subscription, collects ticks
   for `collect-ms` (default 3 s), cancels. Better than snapshot for after-
   hours because TWS gets a chance to compute model greeks during the
   window. Returns a quote map or :unavailable."
  [src opts & {:keys [collect-ms] :or {collect-ms 3000}}]
  (md/stream-opt src opts collect-ms))

(defn calc-option-greeks
  "Compute IV + greeks via the source's calc-iv path. Snapshots both legs
   (option close + underlying close) when prices aren't provided, then
   asks the source to back out greeks. Works any time of day for any
   source that implements calc-iv.

   Required: :symbol :expiry :strike :right
   Optional: :option-price :underlying-price (auto-snapshotted if absent)"
  [src opts]
  (let [opt-px (or (:option-price opts)
                   (price-of (md/snapshot-opt src opts)))
        und-px (or (:underlying-price opts)
                   (price-of (md/snapshot-stk src (:symbol opts))))]
    (cond
      (not (number? opt-px)) :unavailable
      (not (number? und-px)) :unavailable
      :else (md/calc-iv src (assoc opts
                                   :option-price opt-px
                                   :underlying-price und-px)))))
