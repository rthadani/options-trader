(ns options-trader.indicators.fundamentals-views-test
  "Tests for Burry's IV15 indicator. The `iv15` fn is pure
   (canonical-map + close in, columns-map out), so these tests are
   self-contained — no DB, no network. Growth rates and transparency
   constants are hand-computed where asserted."
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.indicators.fundamentals-views :as fv]
            [options-trader.test-util :as tu]))


(deftest happy-path-test
  (testing "all fields present: every IV15 column is populated and reasonable"
    ;; canonical: rev=1000, fcf=100, opinc=200, da=50, cash=500, debt=200, shares=50
    ;; history rev=900, history fcf=80
    ;; rev_g = (1000-900)/900 ≈ 0.1111
    ;; fcf_g not used (rev_g is present)
    ;; g = 0.1111 (uncapped, below 0.12)
    ;; base-ebitda = 200+50 = 250
    (let [canonical {:revenue 1000.0
                     :free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :cash 500.0
                     :long-term-debt 200.0
                     :shares-diluted 50.0
                     :history [{:revenue 900.0
                                :free-cash-flow 80.0}]}
          result (fv/iv15 canonical 60.0)]
      (is (some? result) "iv15 returns a map when all prerequisites are present")
      (testing "iv15 is a positive number"
        (is (number? (:iv15 result)))
        (is (pos? (:iv15 result))))
      (testing "iv15_growth_used = revenue YoY ≈ 0.1111"
        (is (tu/approx= (:iv15_growth_used result) 0.1111 1e-3)))
      (testing "iv15_terminal_multiple_used = 15.0"
        (is (tu/approx= (:iv15_terminal_multiple_used result) 15.0)))
      (testing "iv15_discount_rate_used = 0.15"
        (is (tu/approx= (:iv15_discount_rate_used result) 0.15)))
      (testing "iv15_mos is a number"
        (is (number? (:iv15_mos result)))))))


(deftest nil-cashflow-skip-test
  (testing "free-cash-flow is nil → iv15 returns nil (row is not written)"
    (let [canonical {:revenue 1000.0
                     :free-cash-flow nil
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :cash 500.0
                     :long-term-debt 200.0
                     :shares-diluted 50.0}]
      (is (nil? (fv/iv15 canonical 60.0))
          "iv15 must return nil when FCF is missing"))))


(deftest negative-fcf-returns-nil-test
  (testing "free-cash-flow < 0 → iv15 returns nil (DCF on negative FCF is meaningless)"
    ;; Without the guard, base-fcf=-50 compounds through the growth path producing
    ;; ever-larger-magnitude negatives in PV space, ending in a negative
    ;; per-share IV15 — the opposite signal of what Burry's framework intends.
    (let [canonical {:revenue 1000.0
                     :free-cash-flow -50.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :cash 500.0
                     :long-term-debt 200.0
                     :shares-diluted 15.0
                     :history [{:revenue 1000.0}]}
          result (fv/iv15 canonical 90.0)]
      (is (nil? result)
          "iv15 must return nil when free-cash-flow is negative")
      (testing "no IV15 columns are populated when iv15 returns nil"
        (is (nil? (:iv15 result)))
        (is (nil? (:iv15_growth_used result)))
        (is (nil? (:iv15_terminal_multiple_used result)))
        (is (nil? (:iv15_discount_rate_used result)))
        (is (nil? (:iv15_mos result)))))))


(deftest nil-shares-skip-test
  (testing "shares-diluted is nil → iv15 returns nil"
    (let [canonical {:free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted nil}]
      (is (nil? (fv/iv15 canonical 60.0))
          "iv15 must return nil when shares-diluted is missing"))))

(deftest zero-or-negative-shares-skip-test
  (testing "shares-diluted ≤ 0 → iv15 returns nil"
    (let [canonical {:free-cash-flow 100.0
                     :shares-diluted 0}]
      (is (nil? (fv/iv15 canonical 60.0))
          "shares-diluted = 0 → iv15 returns nil"))
    (let [canonical {:free-cash-flow 100.0
                     :shares-diluted -1.0}]
      (is (nil? (fv/iv15 canonical 60.0))
          "shares-diluted < 0 → iv15 returns nil"))))


(deftest terminal-value-contributes-test
  (testing "FCF=0 but EBITDA derivable: terminal value alone produces a positive IV15"
    ;; canonical: fcf=0, opinc=200, da=50, cash=0, debt=0, shares=100
    ;; ebitda=250, so terminal = ebitda[15]*15 > 0 even with no explicit cash flows.
    ;; equity = PV(terminal) > 0  →  IV15 per share > 0
    (let [canonical {:revenue 1000.0
                     :free-cash-flow 0.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :cash 0.0
                     :long-term-debt 0.0
                     :shares-diluted 100.0
                     :history [{:revenue 900.0}]}
          result (fv/iv15 canonical 60.0)]
      (is (some? result) "iv15 returns a map even when FCF=0 (only terminal contributes)")
      (is (pos? (:iv15 result))
          "terminal value alone produces a positive IV15")
      (testing "terminal multiple and discount rate are still the transparency values"
        (is (tu/approx= (:iv15_terminal_multiple_used result) 15.0))
        (is (tu/approx= (:iv15_discount_rate_used result) 0.15))))))


(deftest iv15-mos-sign-flip-test
  (testing "iv15_mos is negative when close > iv15, positive when close < iv15 (fat pitch)"
    ;; canonical: fcf=100, opinc=200, da=50, shares=100, history=[]
    ;; g = 0.05 (fallback) → IV15 per share ≈ 10-12 (depends on stage-2 fade)
    ;; Convention: iv15_mos = (iv15 - close) / close, so positive ⇒ fat pitch.
    (let [canonical {:free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :cash 0.0
                     :long-term-debt 0.0
                     :shares-diluted 100.0
                     :history []}
          mid-result (fv/iv15 canonical 60.0)
          iv-val     (:iv15 mid-result)
          ;; Trading above intrinsic → mos < 0
          high-close (* iv-val 5.0)
          high-result (fv/iv15 canonical high-close)
          ;; Trading below intrinsic (Burry's "fat pitch") → mos > 0
          low-close  (* iv-val 0.1)
          low-result (fv/iv15 canonical low-close)]
      (is (pos? iv-val) "sanity: IV15 is positive for this canonical")
      (testing "iv15 itself is independent of close"
        (is (tu/approx= (:iv15 high-result) iv-val))
        (is (tu/approx= (:iv15 low-result)  iv-val)))
      (testing "close > iv15 → iv15_mos < 0 (trading above intrinsic)"
        (is (neg? (:iv15_mos high-result))))
      (testing "close < iv15 → iv15_mos > 0 (trading below intrinsic — fat pitch)"
        (is (pos? (:iv15_mos low-result)))))))


(deftest growth-rate-fallbacks-test
  (testing "revenue YoY is preferred when available"
    ;; rev=1000, history rev=800 → rev_g = 200/800 = 0.25 → capped to 0.12
    (let [canonical {:revenue 1000.0
                     :free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 50.0
                     :history [{:revenue 800.0
                                :free-cash-flow 80.0}]}
          result (fv/iv15 canonical 60.0)]
      (is (tu/approx= (:iv15_growth_used result) 0.12)
          "revenue YoY = 0.25 is above the 0.12 cap, clamped to 0.12")))

  (testing "no revenue → FCF YoY from history is used"
    ;; revenue nil, history fcf=80, fcf=100
    ;; fcf_g = (100-80)/|80| = 0.25 → capped to 0.12
    (let [canonical {:free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 50.0
                     :history [{:free-cash-flow 80.0}]}
          result (fv/iv15 canonical 60.0)]
      (is (tu/approx= (:iv15_growth_used result) 0.12)
          "FCF YoY = 0.25 is above the 0.12 cap, clamped to 0.12")))

  (testing "no revenue, no history FCF → hard-coded 5% fallback"
    (let [canonical {:revenue nil
                     :free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 50.0
                     :history []}
          result (fv/iv15 canonical 60.0)]
      (is (tu/approx= (:iv15_growth_used result) 0.05)
          "g falls back to 0.05 when neither source is present")))

  (testing "negative revenue growth is floored to 0.0 (no negative DCF growth)"
    ;; rev=900, history rev=1000 → rev_g = (900-1000)/1000 = -0.1 → floored to 0.0
    (let [canonical {:revenue 900.0
                     :free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 100.0
                     :history [{:revenue 1000.0}]}
          result (fv/iv15 canonical 100.0)]
      (is (tu/approx= (:iv15_growth_used result) 0.0)
          "revenue shrinkage (-10%) is floored to 0.0")
      (testing "iv15 still computes with g=0 (no nil-out)"
        (is (some? result))
        (is (pos? (:iv15 result)))))))


(deftest nil-close-skips-mos-test
  (testing "close=nil → per-share iv15 still computed, iv15_mos is nil"
    (let [canonical {:free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 50.0
                     :history []}
          result (fv/iv15 canonical nil)]
      (is (some? result) "iv15 returns a map (per-share value doesn't depend on close)")
      (is (number? (:iv15 result)))
      (is (nil? (:iv15_mos result))
          "iv15_mos requires a valid close"))))


(deftest result-shape-test
  (testing "result map has exactly the 5 IV15 columns (no extras, no missing)"
    (let [canonical {:free-cash-flow 100.0
                     :operating-income 200.0
                     :depreciation-amortization 50.0
                     :shares-diluted 50.0
                     :history []}
          result (fv/iv15 canonical 60.0)
          expected #{:iv15 :iv15_growth_used :iv15_terminal_multiple_used
                     :iv15_discount_rate_used :iv15_mos}]
      (is (= expected (set (keys result)))
          "result keys must be exactly the 5 IV15 columns"))))


;; --- direct unit tests for the math helpers ---------------------------
;; grow-path / fade-rate / project-series are private — reach them via #'.

(deftest grow-path-test
  (testing "grow-path: 15-length seq = 10 constant head + 5 linear fade tail"
    ;; 10 stage-1 years at g=0.08, then 5 stage-2 years fading 0.08 → 0.03
    ;; at k=0..4 with formula g - (g - terminal-growth) * (k/4).
    (let [path (vec (#'fv/grow-path 0.08 0.03 10 5))]
      (is (= 15 (count path))
          "grow-path returns 15 elements (10 stage-1 + 5 stage-2)")
      (testing "first 10 elements are constant 0.08"
        (is (every? #(tu/approx= % 0.08) (take 10 path))))
      (testing "last 5 elements are the linear fade 0.08 → 0.03"
        (is (tu/approx= (nth path 10) 0.08))
        (is (tu/approx= (nth path 11) 0.0675))
        (is (tu/approx= (nth path 12) 0.055))
        (is (tu/approx= (nth path 13) 0.0425))
        (is (tu/approx= (nth path 14) 0.03))))))


(deftest fade-rate-test
  (testing "fade-rate: linear interpolation from g to terminal-growth at step k"
    ;; k=0  → g unchanged
    ;; k=2  → midpoint of g and terminal-growth
    ;; k=4  → terminal-growth (last step in a 5-element stage-2 fade)
    (is (tu/approx= (#'fv/fade-rate 0.08 0.03 0) 0.08)
        "k=0 → g")
    (is (tu/approx= (#'fv/fade-rate 0.08 0.03 2) 0.055)
        "k=2 (midpoint) → (g + terminal-growth) / 2")
    (is (tu/approx= (#'fv/fade-rate 0.08 0.03 4) 0.03)
        "k=4 (last step) → terminal-growth")))


(deftest project-series-test
  (testing "project-series: compound base through growth rates, 1 entry per rate"
    ;; base=100, three years of 10% growth → 100×1.1, 100×1.1², 100×1.1³
    ;; = 110.0, 121.0, 133.1. year-N lives at index (N-1).
    (let [series (#'fv/project-series 100.0 [0.1 0.1 0.1])]
      (is (= 3 (count series))
          "project-series returns 1 entry per growth rate (no seed year)")
      (is (tu/approx= (nth series 0) 110.0)
          "year 1 = base × 1.1¹")
      (is (tu/approx= (nth series 1) 121.0)
          "year 2 = base × 1.1²")
      (is (tu/approx= (nth series 2) 133.1)
          "year 3 = base × 1.1³"))))


(deftest iv15-matches-agree-128-test
  (testing "Burry's canonical scenario: $100B FCF, 8% growth → IV15 ≈ $105.22/share"
    ;; Agree-128 (Burry letter): FCF=$100B, rev_g=0.08, opinc=$120B, D&A=$12B,
    ;; cash=$60B, LT debt=$100B, shares=15B. revenue=1080, history rev=1000
    ;; so derive-growth returns 0.08 (uncapped, below 0.12 cap).
    ;; g drives years 1..10 = 0.08; years 11..15 fade g → terminal-growth.
    ;; Hand-rolled: Σ PV(F1..F15) ≈ $932B, year-15 EBITDA ≈ $372B,
    ;; terminal ≈ $5,583B → PV ≈ $686B, equity ≈ $1,578B → IV15 ≈ $105.22/share.
    ;; ±$1.0 absolute tolerance (≈ 1%) catches any meaningful regression in
    ;; project-series, fade-rate, or the pv discount math while leaving headroom
    ;; for floating-point drift in the 15-year compounding.
    (let [canonical {:revenue 1080.0
                     :free-cash-flow 100.0
                     :operating-income 120.0
                     :depreciation-amortization 12.0
                     :cash 60.0
                     :long-term-debt 100.0
                     :shares-diluted 15.0
                     :history [{:revenue 1000.0}]}
          result (fv/iv15 canonical 90.0)]
      (is (some? result))
      (testing "iv15 ≈ $105.22/share (±$1.0 absolute, ≈ 1%)"
        (is (tu/approx= (:iv15 result) 105.22 1.0)))
      (testing "growth used is the input rev_yoy 0.08"
        (is (tu/approx= (:iv15_growth_used result) 0.08)))
      (testing "transparency columns are the public defaults"
        (is (tu/approx= (:iv15_terminal_multiple_used result) 15.0))
        (is (tu/approx= (:iv15_discount_rate_used result) 0.15)))
      (testing "close=$90 (below IV15) → iv15_mos ≈ 0.1691 (fat pitch)"
        ;; (iv15 - close) / close = (105.22 - 90) / 90 ≈ 0.1691
        ;; ±0.005 absolute (≈ 0.5%) catches any meaningful MOS regression.
        (is (pos? (:iv15_mos result))
            "trading below intrinsic must yield a positive margin of safety")
        (is (tu/approx= (:iv15_mos result) 0.1691 0.005))))))
