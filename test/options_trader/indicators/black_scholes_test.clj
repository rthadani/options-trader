(ns options-trader.indicators.black-scholes-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.indicators.black-scholes :as bs]))

(defn- close? [a b tol]
  (< (Math/abs (- (double a) (double b))) tol))

;; Canonical BSM reference values for
;; S=180, K=185, T=30/365, r=5.3%, sigma=25%, q=1%, type=call.
;; These match any standard Black-Scholes implementation to four decimals.
(deftest matches-bsm-reference-call
  (let [g (bs/greeks {:S 180.0 :K 185.0 :T (/ 30.0 365.0)
                      :r 0.053 :sigma 0.25 :q 0.01 :type :call})]
    (is (close? (:price g) 3.3174  1e-3))
    (is (close? (:delta g) 0.3829  1e-3))
    (is (close? (:gamma g) 0.0296  1e-3))
    (is (close? (:theta g) -0.0896 1e-3))
    (is (close? (:vega  g) 0.1968  1e-3))
    (is (close? (:rho   g) 0.0539  1e-3))))

(deftest put-signs
  ;; Put with same inputs — sign checks against BSM expectations.
  (let [g (bs/greeks {:S 180.0 :K 185.0 :T (/ 30.0 365.0)
                      :r 0.053 :sigma 0.25 :q 0.01 :type :put})]
    (is (pos? (:price g)))
    (is (neg? (:delta g)))
    (is (pos? (:gamma g)))                 ; gamma symmetric across types
    (is (neg? (:theta g)))                 ; OTM put still decays
    (is (pos? (:vega  g)))
    (is (neg? (:rho   g)))))

(deftest put-call-parity
  ;; C - P = S*e^-qT - K*e^-rT  for European options.
  (let [opts {:S 180.0 :K 185.0 :T (/ 30.0 365.0)
              :r 0.053 :sigma 0.25 :q 0.01}
        c   (bs/call-price opts)
        p   (bs/put-price  opts)
        rhs (- (* (:S opts) (Math/exp (- (* (:q opts) (:T opts)))))
               (* (:K opts) (Math/exp (- (* (:r opts) (:T opts))))))]
    (is (close? (- c p) rhs 1e-6))))

(deftest validates-inputs
  (is (thrown? clojure.lang.ExceptionInfo
        (bs/greeks {:S 0    :K 100 :T 0.1 :r 0.05 :sigma 0.2})))
  (is (thrown? clojure.lang.ExceptionInfo
        (bs/greeks {:S 100  :K 100 :T 0.0 :r 0.05 :sigma 0.2})))
  (is (thrown? clojure.lang.ExceptionInfo
        (bs/greeks {:S 100  :K 100 :T 0.1 :r 0.05 :sigma 0.0}))))
