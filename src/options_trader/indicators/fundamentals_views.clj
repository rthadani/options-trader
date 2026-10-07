(ns options-trader.indicators.fundamentals-views
  "EDGAR fundamentals → ratio columns on latest_indicators, with the full
   canonical map persisted to the fundamentals table. Symbols EDGAR can't
   resolve (foreign ADRs, most ETFs) are skipped with a warning."
  (:require [cheshire.core                    :as json]
            [taoensso.timbre                  :as log]
            [options-trader.data.fundamentals :as fund]
            [options-trader.db.queries.indicators :as q]))


;; Burry's IV15: tunable inputs kept here so a future maintainer can find them.
;; 15.0 / 0.15 are the literal "15" in the name.
(def ^:private exit-multiple        15.0)   ; terminal value = year-15 EBITDA × this
(def ^:private discount-rate        0.15)   ; discount all cash flows (and the terminal) at this rate
(def ^:private growth-cap           0.12)   ; per-year growth ceiling
(def ^:private terminal-growth      0.03)   ; fade target for stage 2
(def ^:private growth-fallback      0.05)   ; used when both revenue YoY and history-FCF growth are absent
(def ^:private stage1-years         10)     ; constant-growth stage (years 1..10)
(def ^:private stage2-years         5)      ; linear-fade stage (years 11..15)

(def ^:private columns
  [:pe_ratio :earnings_yield :price_to_book :fcf_yield :ocf_yield
   :debt_to_equity :current_ratio :gross_margin :operating_margin :net_margin
   :roe :revenue_growth_yoy :net_income_growth_yoy :eps_growth_yoy
   :iv15 :iv15_growth_used :iv15_terminal_multiple_used
   :iv15_discount_rate_used :iv15_mos])

(defn ensure-schema! [ds]
  (q/ensure-double-columns! ds columns))


(defn- div [a b]
  (when (and (number? a) (number? b) (not (zero? b)))
    (double (/ a b))))

(defn- growth [curr prev]
  (when (and (number? curr) (number? prev) (not (zero? prev)))
    (double (/ (- curr prev) prev))))

(defn- fcf-growth
  "FCF YoY with the absolute-value denominator Burry uses (avoids sign
   flips in the denominator when prior FCF is negative)."
  [curr prior]
  (when (and (number? curr) (number? prior) (not (zero? prior)))
    (double (/ (- curr prior) (Math/abs (double prior))))))

(defn- clamp-growth
  "Cap at growth-cap, floor at 0.0 — Burry's no-negative-growth convention:
   even a shrinking business is assumed flat for DCF."
  [g]
  (cond
    (> g growth-cap) growth-cap
    (< g 0.0)        0.0
    :else            (double g)))

(defn- derive-growth
  "Resolve the per-year growth rate g from a canonical map. Order:
   revenue YoY (from :revenue vs first :history period), then FCF YoY
   from :free-cash-flow, then a hard-coded 5% fallback."
  [{:keys [revenue free-cash-flow history]}]
  (let [prior (first history)]
    (or (growth revenue (:revenue prior))
        (fcf-growth free-cash-flow (:free-cash-flow prior))
        growth-fallback)))

(defn- fade-rate
  "Linear fade growth rate for year (stage1-years + k) in stage 2, k=0..(stage2-years-1).
   At k=0, rate=g. At k=(stage2-years-1), rate=terminal-growth."
  [g terminal-growth k]
  (- g (* (- g terminal-growth) (/ k (double (dec stage2-years))))))

(defn- grow-path
  "Sequence of (stage1-years + stage2-years) growth rates:
   the first stage1-years are constant g; the next stage2-years linearly fade g → terminal-growth."
  [g terminal-growth s1 s2]
  (concat (repeat s1 g)
          (for [k (range 0 s2)] (fade-rate g terminal-growth k))))

(defn- project-series
  "Compound `base` through the given growth rates, returning end-of-year
   values for years 1..15 (15 entries). `rest` drops the seed so the
   resulting seq has exactly 15 elements; year 1 is index 0, year 15 is
   index 14."
  [base rates]
  (rest (reductions * (double base) (map #(+ 1.0 %) rates))))

(defn- pv [cf y r]
  (/ cf (Math/pow (+ 1.0 r) y)))

(defn iv15
  "Burry's IV15: 15-year DCF with a 15% discount rate, an exit multiple of
   15× year-15 EBITDA, and a 2-stage growth path.

   Stage 1 (years 1-10) compounds FCF and EBITDA at a per-year rate g.
   Stage 2 (years 11-15) linearly fades g to 0.03.

   g is derived from canonical :revenue (revenue YoY) when available,
   then a hand-rolled FCF YoY from the canonical :history, then a
   hard-coded 5% fallback. g is capped at 0.12 and floored at 0.0.

   Terminal value = year-15 EBITDA × 15.
   Equity = Σ PV(explicit year 1..15) + PV(terminal) + cash − LT debt.
   IV15 per share = equity / shares-diluted.

   Returns nil when shares-diluted is missing or ≤0, or when
   free-cash-flow is negative (DCF on negative FCF is meaningless;
   FCF=0 still produces a positive IV15 from the terminal-value
   contribution). The returned map carries the 5 transparency
   columns (:iv15, :iv15_growth_used, :iv15_terminal_multiple_used,
   :iv15_discount_rate_used, :iv15_mos)."
  [{:keys [free-cash-flow operating-income depreciation-amortization
           cash long-term-debt shares-diluted] :as canonical}
   close]
  (when (and (number? free-cash-flow)
             (>= free-cash-flow 0)
             (number? shares-diluted)
             (pos? shares-diluted))
    (let [g           (clamp-growth (derive-growth canonical))
          base-fcf    (double free-cash-flow)
          base-ebitda (+ (double (or operating-income 0.0))
                         (double (or depreciation-amortization 0.0)))
          rates       (grow-path g terminal-growth stage1-years stage2-years)
          fcfs        (project-series base-fcf rates)
          ebitdas     (project-series base-ebitda rates)
          year-15-ebitda (nth ebitdas 14)        ; 0-indexed: year 15 is index 14
          terminal    (* year-15-ebitda exit-multiple)
          sum-pv      (reduce + (map-indexed
                                  (fn [i cf] (pv cf (inc i) discount-rate))
                                  fcfs))
          pv-term     (pv terminal 15 discount-rate)
          equity      (+ sum-pv pv-term
                           (double (or cash 0.0))
                           (- (double (or long-term-debt 0.0))))
          per-share   (/ equity (double shares-diluted))]
      {:iv15                        per-share
       :iv15_growth_used            g
       :iv15_terminal_multiple_used exit-multiple
       :iv15_discount_rate_used     discount-rate
       :iv15_mos                    (when (and (number? close) (not (zero? close)))
                                       ;; positive ⇒ trading below IV15 (fat pitch)
                                       (/ (- per-share close) close))})))

(defn- ratios
  "Compute the screenable ratios from a canonical fundamentals map +
   the latest close + shares-outstanding-derived market-cap.

   ocf_yield exists as a resilient sibling to fcf_yield: edgarjure's
   :capex extraction is unreliable for banks/REITs/many foreign filers,
   which nils out :free-cash-flow and therefore fcf_yield. ocf_yield
   needs only operating-cash-flow + shares — much more frequently
   present — so sages have a usable cash-yield proxy when fcf is gone."
  [{:keys [revenue gross-profit operating-income net-income eps-diluted
           total-liabilities current-liabilities current-assets
           long-term-debt stockholders-equity free-cash-flow
           operating-cash-flow shares-diluted history] :as _f}
   close]
  (let [market-cap (when (and (number? close) (number? shares-diluted))
                     (* close shares-diluted))
        book-value (div stockholders-equity shares-diluted)
        prior      (first history)]
    {:pe_ratio              (div close eps-diluted)
     :earnings_yield        (div eps-diluted close)
     :price_to_book         (div close book-value)
     :fcf_yield             (div free-cash-flow      market-cap)
     :ocf_yield             (div operating-cash-flow market-cap)
     :debt_to_equity        (div long-term-debt stockholders-equity)
     :current_ratio         (div current-assets current-liabilities)
     :gross_margin          (div gross-profit revenue)
     :operating_margin      (div operating-income revenue)
     :net_margin            (div net-income revenue)
     :roe                   (div net-income stockholders-equity)
     :revenue_growth_yoy    (growth revenue       (:revenue       prior))
     :net_income_growth_yoy (growth net-income    (:net-income    prior))
     :eps_growth_yoy        (growth eps-diluted   (:eps-diluted   prior))}))


(defn- latest-close   [ds sym] (q/latest-close ds sym))

(defn- persist-fundamentals! [ds sym period payload]
  (q/persist-fundamentals! ds {:symbol sym :period period
                                :data   (json/generate-string payload)}))

(defn- upsert-row! [ds sym values]
  (q/upsert-latest-row! ds sym (into {} (remove (comp nil? val) values))))


(defn refresh-fundamentals-views!
  "Fetch fundamentals for symbols via the EDGAR source, persist the canonical
   payload to the fundamentals table, and upsert computed ratio columns onto
   latest_indicators.

   With no :symbols opt, walks every distinct symbol in bars_daily. Pass
   :symbols [\"XEL\" \"XYL\" ...] to scope the run — useful for retrying a
   partial failure or smoke-testing a single ticker.

   Slow path: ~1 request per symbol (edgarjure rate-limits to ~10 req/sec
   internally). Intended for daily-cadence refresh. Symbols not in EDGAR
   are logged and skipped."
  ([ds]
   (refresh-fundamentals-views! ds (fund/make-source {:type :edgar}) {}))
  ([ds source]
   (refresh-fundamentals-views! ds source {}))
  ([ds source {:keys [symbols]}]
   (ensure-schema! ds)
   (let [syms        (or (seq symbols) (q/all-bars-daily-symbols ds))
         total       (count syms)
         oom-cap     5
         step!
         (fn [{:keys [oom-streak] :as tally} [i sym]]
           (when (>= oom-streak oom-cap)
             (throw (ex-info (str "aborting fundamentals: " oom-streak
                                  " consecutive OOMs — heap is wedged, bump -Xmx")
                             (assoc tally :aborted-at i :total total))))
           (try
             (let [p   (promise)
                   _   (fund/fetch-fundamentals sym {} source
                         (fn [evs] (deliver p evs)))
                   evs (deref p 30000 nil)
                   raw (first evs)]
               (cond
                 (or (nil? raw) (= :error (:type raw)))
                 (do (log/infof "fundamentals [%d/%d] %s skipped (%s)"
                                (inc i) total sym (:message raw))
                     (-> tally (update :skipped inc) (assoc :oom-streak 0)))

                 :else
                 (let [norm  (fund/normalise-fundamentals raw source)
                       close (latest-close ds sym)
                       rs    (ratios norm close)
                       iv    (iv15 norm close)
                       row   (merge rs iv)]
                   (persist-fundamentals! ds sym (str (:as-of norm)) norm)
                   (upsert-row! ds sym row)
                   (log/infof "fundamentals [%d/%d] %s ok (as-of %s)"
                              (inc i) total sym (:as-of norm))
                   (-> tally (update :ok inc) (assoc :oom-streak 0)))))
             (catch OutOfMemoryError t
               (log/warnf "fundamentals [%d/%d] %s OOM — heap wedged" (inc i) total sym)
               (System/gc)
               (-> tally (update :failed inc) (update :oom-streak inc)))
             (catch Throwable t
               (log/warnf "fundamentals [%d/%d] %s failed: %s"
                          (inc i) total sym (.getMessage t))
               (-> tally (update :failed inc) (assoc :oom-streak 0)))))]
     (log/infof "fundamentals: processing %d symbols" total)
     (-> (reduce step! {:ok 0 :skipped 0 :failed 0 :oom-streak 0}
                 (map-indexed vector syms))
         (dissoc :oom-streak)
         (assoc :total total)))))
