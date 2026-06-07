(ns options-trader.indicators.black-scholes
  "Black-Scholes-Merton pricing + Greeks for European options.

   Pure math, no I/O. All trig, exp, log, and the standard-normal CDF/PDF
   go through fastmath, so there is no Apache-Commons-Math interop in
   callers."
  (:require [fastmath.core :as m]
            [fastmath.random :as r]))

(def ^:private std-normal (r/distribution :normal))

(defn- cdf ^double [^double x] (r/cdf std-normal x))
(defn- pdf ^double [^double x] (r/pdf std-normal x))

(defn- validate!
  [{:keys [S K T sigma]}]
  (when-not (and (number? S) (number? K) (number? T) (number? sigma)
                 (pos? S) (pos? K) (pos? T) (pos? sigma))
    (throw (ex-info "S, K, T, sigma must all be positive numbers"
                    {:S S :K K :T T :sigma sigma}))))

(defn greeks
  "Price + Greeks for one European option.

   opts:
     :S      spot price
     :K      strike
     :T      years to expiry (e.g. 30/365 for 30 calendar days)
     :r      risk-free rate (decimal, e.g. 0.053)
     :sigma  annualised implied vol (decimal, e.g. 0.25 for 25%)
     :q      continuous dividend yield (decimal, default 0.0)
     :type   :call or :put (default :call)

   Returns:
     {:price :delta :gamma :theta :vega :rho :intrinsic :time-value :moneyness}
   theta is per calendar day; vega is per 1 percentage-point change in IV;
   rho is per 1 percentage-point change in the risk-free rate."
  [{:keys [S K T r sigma q type] :or {q 0.0 type :call} :as opts}]
  (validate! opts)
  (let [sqrtT  (m/sqrt T)
        d1     (/ (+ (m/log (/ S K))
                     (* (+ (- r q) (* 0.5 sigma sigma)) T))
                  (* sigma sqrtT))
        d2     (- d1 (* sigma sqrtT))
        e-qT   (m/exp (- (* q T)))
        e-rT   (m/exp (- (* r T)))
        Nd1    (cdf d1)        Nd2    (cdf d2)
        N-d1   (cdf (- d1))    N-d2   (cdf (- d2))
        pdf-d1 (pdf d1)
        call?  (= type :call)
        price  (max 0.0
                    (if call?
                      (- (* S e-qT Nd1) (* K e-rT Nd2))
                      (- (* K e-rT N-d2) (* S e-qT N-d1))))
        delta  (if call? (* e-qT Nd1) (* e-qT (- Nd1 1.0)))
        gamma  (/ (* e-qT pdf-d1) (* S sigma sqrtT))
        vega   (/ (* S e-qT pdf-d1 sqrtT) 100.0)
        theta  (/ (+ (- (/ (* S pdf-d1 sigma e-qT) (* 2.0 sqrtT)))
                     (if call?
                       (+ (- (* r K e-rT Nd2)) (* q S Nd1 e-qT))
                       (- (* r K e-rT N-d2)    (* q S N-d1 e-qT))))
                  365.0)
        rho    (if call?
                 (/ (* K T e-rT Nd2) 100.0)
                 (/ (* (- K) T e-rT N-d2) 100.0))
        intr   (if call? (max 0.0 (- S K)) (max 0.0 (- K S)))
        ratio  (/ S K)]
    {:price      price
     :delta      delta
     :gamma      gamma
     :theta      theta
     :vega       vega
     :rho        rho
     :intrinsic  intr
     :time-value (- price intr)
     :moneyness  (cond (< (m/abs (- ratio 1.0)) 0.02) :atm
                       (> ratio 1.0)                  (if call? :itm :otm)
                       :else                          (if call? :otm :itm))}))

(defn call-price [opts] (:price (greeks (assoc opts :type :call))))
(defn put-price  [opts] (:price (greeks (assoc opts :type :put))))

;; Historical volatility lives in the warehouse: db.refresh/refresh-iv-daily!
;; computes HV30 from bars_daily on every run and stores it in iv_daily.hv30
;; (with hv_percentile_252d and iv_minus_hv derived in latest_indicators).
;; Callers should `SELECT hv30 FROM iv_daily ...` rather than recomputing
;; here — keeping one source of truth avoids ddof drift between callers.
