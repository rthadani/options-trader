(ns options-trader.indicators.ta4j
  "ta4j wrapper providing indicator construction and value extraction.
   Uses direct Java interop against ta4j-core 0.16."
  (:require [clojure.string :as str])
  (:import [org.ta4j.core BaseBarSeries BarSeries Bar]
           [org.ta4j.core.num Num DecimalNum DoubleNum]
           [org.ta4j.core.indicators RSIIndicator SMAIndicator EMAIndicator
            MACDIndicator ATRIndicator
            ChopIndicator CCIIndicator CMOIndicator
            StochasticOscillatorKIndicator StochasticOscillatorDIndicator
            StochasticRSIIndicator WilliamsRIndicator
            ParabolicSarIndicator UlcerIndexIndicator MassIndexIndicator FisherIndicator]
           [org.ta4j.core.indicators.adx ADXIndicator DXIndicator
            PlusDIIndicator MinusDIIndicator]
           [org.ta4j.core.indicators.aroon AroonUpIndicator AroonDownIndicator
            AroonOscillatorIndicator]
           [org.ta4j.core.indicators.helpers ClosePriceIndicator
            HighPriceIndicator LowPriceIndicator OpenPriceIndicator
            VolumeIndicator CombineIndicator]
           [org.ta4j.core.indicators.statistics StandardDeviationIndicator]
           [org.ta4j.core.indicators.bollinger BollingerBandWidthIndicator
            BollingerBandsMiddleIndicator BollingerBandsUpperIndicator
            BollingerBandsLowerIndicator PercentBIndicator]
           [org.ta4j.core.indicators.volume OnBalanceVolumeIndicator
            ChaikinMoneyFlowIndicator]
           [org.ta4j.core.indicators.keltner KeltnerChannelUpperIndicator
            KeltnerChannelLowerIndicator KeltnerChannelMiddleIndicator]
           [org.ta4j.core.indicators.pivotpoints PivotPointIndicator
            StandardReversalIndicator FibonacciReversalIndicator
            PivotLevel TimeLevel]
           [org.ta4j.core.indicators.pivotpoints FibonacciReversalIndicator$FibReversalTyp]
           [java.time ZonedDateTime ZoneId Duration Instant]))

;;; ── Num factories ──────────────────────────────────────────────────────────

(defn num-double
  "Wrap a numeric value as ta4j DoubleNum."
  ^Num [v]
  (DoubleNum/valueOf (double v)))

(defn num-decimal
  "Wrap a numeric value as ta4j DecimalNum (arbitrary precision)."
  ^Num [v]
  (DecimalNum/valueOf (double v)))

;;; ── Bar series ──────────────────────────────────────────────────────────────

(defn bar-series
  "Create a named BaseBarSeries."
  (^BaseBarSeries [] (BaseBarSeries.))
  (^BaseBarSeries [name] (BaseBarSeries. (str name))))

(defn add-bar
  "Append one OHLCV bar to series. time-ms is epoch milliseconds.
   Returns series for threading."
  [^BaseBarSeries series time-ms open high low close volume]
  (let [zdt (ZonedDateTime/ofInstant
              (Instant/ofEpochMilli (long time-ms))
              (ZoneId/of "UTC"))]
    (.addBar series zdt
             (double open) (double high) (double low) (double close) (double volume)))
  series)

(defn bar-count
  "Number of bars in series."
  [^BarSeries series]
  (.getBarCount series))

(defn record->clj
  "Convert a ta4j Bar to a Clojure map."
  [^Bar bar]
  {:time    (-> bar .getEndTime .toInstant .toEpochMilli)
   :open    (-> bar .getOpenPrice .doubleValue)
   :high    (-> bar .getHighPrice .doubleValue)
   :low     (-> bar .getLowPrice .doubleValue)
   :close   (-> bar .getClosePrice .doubleValue)
   :volume  (-> bar .getVolume .doubleValue)})

;;; ── Dataset conversion ──────────────────────────────────────────────────────

(defn ds->ta4j-ohlcv
  "Convert a seq of maps with :time :open :high :low :close :volume keys
   into a BaseBarSeries. :time must be epoch milliseconds."
  [rows & {:keys [name] :or {name "series"}}]
  (let [series (bar-series name)]
    (doseq [r rows]
      (add-bar series
               (or (:time r) (:t r) 0)
               (or (:open r) (:h r) 0)
               (or (:high r) (:h r) 0)
               (or (:low r) (:l r) 0)
               (or (:close r) (:c r) 0)
               (or (:volume r) (:v r) 0)))
    series))

;;; ── Indicator constructors ──────────────────────────────────────────────────

(defn close-price
  "ClosePriceIndicator wrapping a series."
  [^BarSeries series]
  (ClosePriceIndicator. series))

(defn sma
  "Simple Moving Average indicator. bar-count is the look-back window."
  [^BarSeries series bar-count]
  (SMAIndicator. (close-price series) (int bar-count)))

(defn ema
  "Exponential Moving Average indicator."
  [^BarSeries series bar-count]
  (EMAIndicator. (close-price series) (int bar-count)))

(defn rsi
  "Relative Strength Index indicator."
  [^BarSeries series bar-count]
  (RSIIndicator. (close-price series) (int bar-count)))

(defn macd
  "MACD indicator (shortPeriod, longPeriod)."
  ([series] (macd series 12 26))
  ([^BarSeries series short-p long-p]
   (MACDIndicator. (close-price series) (int short-p) (int long-p))))

(defn stddev
  "Standard Deviation indicator."
  [^BarSeries series bar-count]
  (StandardDeviationIndicator. (close-price series) (int bar-count)))

(defn atr
  "Average True Range indicator (Wilder smoothing)."
  [^BarSeries series bar-count]
  (ATRIndicator. series (int bar-count)))

(defn bollinger-band-width
  "BollingerBandWidthIndicator: (upper - lower) / middle, params [barCount k]."
  [^BarSeries series bar-count k]
  (let [cp    (close-price series)
        sma-i (SMAIndicator. cp (int bar-count))
        std-i (StandardDeviationIndicator. cp (int bar-count))
        kk    (.numOf series (double k))
        mid   (BollingerBandsMiddleIndicator. sma-i)
        upp   (BollingerBandsUpperIndicator. mid std-i kk)
        low   (BollingerBandsLowerIndicator. mid std-i kk)]
    (BollingerBandWidthIndicator. upp mid low)))

;;; ── ADX / DI family ─────────────────────────────────────────────────────────

(defn adx
  "ADXIndicator (Wilder-smoothed Average Directional Index)."
  [^BarSeries series bar-count]
  (ADXIndicator. series (int bar-count)))

(defn dx
  "DXIndicator (Directional Movement Index, single period)."
  [^BarSeries series bar-count]
  (DXIndicator. series (int bar-count)))

(defn plus-di
  "PlusDIIndicator (+DI, positive directional indicator)."
  [^BarSeries series bar-count]
  (PlusDIIndicator. series (int bar-count)))

(defn minus-di
  "MinusDIIndicator (-DI, negative directional indicator)."
  [^BarSeries series bar-count]
  (MinusDIIndicator. series (int bar-count)))

;;; ── Aroon family ────────────────────────────────────────────────────────────

(defn aroon-up
  "AroonUpIndicator."
  [^BarSeries series bar-count]
  (AroonUpIndicator. series (int bar-count)))

(defn aroon-down
  "AroonDownIndicator."
  [^BarSeries series bar-count]
  (AroonDownIndicator. series (int bar-count)))

(defn aroon-osc
  "AroonOscillatorIndicator (AroonUp - AroonDown)."
  [^BarSeries series bar-count]
  (AroonOscillatorIndicator. series (int bar-count)))

;;; ── Range / oscillator indicators ───────────────────────────────────────────

(defn chop
  "ChopIndicator (Choppiness Index). scaleUpTo=100."
  [^BarSeries series bar-count]
  (ChopIndicator. series (int bar-count) 100))

(defn stoch-k
  "StochasticOscillatorKIndicator (%K)."
  [^BarSeries series bar-count]
  (StochasticOscillatorKIndicator. series (int bar-count)))

(defn stoch-d
  "StochasticOscillatorDIndicator (%D = SMA[3] of %K[bar-count])."
  [^BarSeries series bar-count]
  (StochasticOscillatorDIndicator. (stoch-k series bar-count)))

(defn stoch-rsi
  "StochasticRSIIndicator (Stochastic of RSI)."
  [^BarSeries series bar-count]
  (StochasticRSIIndicator. series (int bar-count)))

(defn williams-r
  "WilliamsRIndicator (%R, range -100 to 0)."
  [^BarSeries series bar-count]
  (WilliamsRIndicator. series (int bar-count)))

(defn cci
  "CCIIndicator (Commodity Channel Index)."
  [^BarSeries series bar-count]
  (CCIIndicator. series (int bar-count)))

(defn cmo
  "CMOIndicator (Chande Momentum Oscillator) on close price."
  [^BarSeries series bar-count]
  (CMOIndicator. (close-price series) (int bar-count)))

;;; ── MACD signal and histogram ───────────────────────────────────────────────

(defn macd-signal
  "EMA[signal-period] over MACD(short,long) — the signal line."
  ([series] (macd-signal series 12 26 9))
  ([^BarSeries series short-p long-p signal-p]
   (EMAIndicator. (macd series short-p long-p) (int signal-p))))

(defn macd-hist
  "MACD histogram = MACD(short,long) minus EMA[signal] of MACD."
  ([series] (macd-hist series 12 26 9))
  ([^BarSeries series short-p long-p signal-p]
   (let [m (macd series short-p long-p)
         s (EMAIndicator. m (int signal-p))]
     (CombineIndicator/minus m s))))

;;; ── Parabolic SAR ───────────────────────────────────────────────────────────

(defn parabolic-sar
  "ParabolicSarIndicator[start, increment, max].
   Uses series.numOf to stay consistent with the series' Num type (DecimalNum by default)."
  [^BarSeries series start increment max-val]
  (ParabolicSarIndicator. series
                          (.numOf series (double start))
                          (.numOf series (double increment))
                          (.numOf series (double max-val))))

;;; ── Volume indicators ───────────────────────────────────────────────────────

(defn obv
  "OnBalanceVolumeIndicator (no parameters)."
  [^BarSeries series]
  (OnBalanceVolumeIndicator. series))

(defn cmf
  "ChaikinMoneyFlowIndicator[barCount]."
  [^BarSeries series bar-count]
  (ChaikinMoneyFlowIndicator. series (int bar-count)))

;;; ── Statistical / risk indicators ──────────────────────────────────────────

(defn ulcer-index
  "UlcerIndexIndicator[barCount] on close price."
  [^BarSeries series bar-count]
  (UlcerIndexIndicator. (close-price series) (int bar-count)))

(defn mass-index
  "MassIndexIndicator[emaPeriod, barCount]."
  [^BarSeries series ema-period bar-count]
  (MassIndexIndicator. series (int ema-period) (int bar-count)))

;;; ── Bollinger upper / lower / %B ────────────────────────────────────────────

(defn bollinger-upper
  "BollingerBandsUpperIndicator[barCount, k]."
  [^BarSeries series bar-count k]
  (let [cp    (close-price series)
        sma-i (SMAIndicator. cp (int bar-count))
        std-i (StandardDeviationIndicator. cp (int bar-count))
        kk    (.numOf series (double k))
        mid   (BollingerBandsMiddleIndicator. sma-i)]
    (BollingerBandsUpperIndicator. mid std-i kk)))

(defn bollinger-lower
  "BollingerBandsLowerIndicator[barCount, k]."
  [^BarSeries series bar-count k]
  (let [cp    (close-price series)
        sma-i (SMAIndicator. cp (int bar-count))
        std-i (StandardDeviationIndicator. cp (int bar-count))
        kk    (.numOf series (double k))
        mid   (BollingerBandsMiddleIndicator. sma-i)]
    (BollingerBandsLowerIndicator. mid std-i kk)))

(defn percent-b
  "PercentBIndicator[barCount, k] — %B position within Bollinger Bands."
  [^BarSeries series bar-count k]
  (PercentBIndicator. (close-price series) (int bar-count) (double k)))

;;; ── Keltner channels ────────────────────────────────────────────────────────

(defn keltner-upper
  "KeltnerChannelUpperIndicator[emaPeriod, ratio, atrPeriod]."
  [^BarSeries series period ratio atr-period]
  (let [mid (KeltnerChannelMiddleIndicator. series (int period))]
    (KeltnerChannelUpperIndicator. mid (double ratio) (int atr-period))))

(defn keltner-lower
  "KeltnerChannelLowerIndicator[emaPeriod, ratio, atrPeriod]."
  [^BarSeries series period ratio atr-period]
  (let [mid (KeltnerChannelMiddleIndicator. series (int period))]
    (KeltnerChannelLowerIndicator. mid (double ratio) (int atr-period))))

;;; ── Fisher Transform ────────────────────────────────────────────────────────

(defn fisher
  "FisherIndicator[barCount] on close price."
  [^BarSeries series bar-count]
  (FisherIndicator. (close-price series) (int bar-count)))

;;; ── Pivot points (classical + Fibonacci) ───────────────────────────────────

(defn pivot-point
  "PivotPointIndicator on the prior bar's H/L/C (TimeLevel.BARBASED).
   With daily bars this gives classical daily pivots: P = (H+L+C)/3 of yesterday."
  [^BarSeries series]
  (PivotPointIndicator. series TimeLevel/BARBASED))

(defn pivot-reversal
  "Classical reversal level off a daily pivot.
   level – :R1 :R2 :R3 :S1 :S2 :S3"
  [^BarSeries series level]
  (let [plv (case level
              :R1 PivotLevel/RESISTANCE_1
              :R2 PivotLevel/RESISTANCE_2
              :R3 PivotLevel/RESISTANCE_3
              :S1 PivotLevel/SUPPORT_1
              :S2 PivotLevel/SUPPORT_2
              :S3 PivotLevel/SUPPORT_3)]
    (StandardReversalIndicator. (pivot-point series) plv)))

(defn pivot-fib-reversal
  "Fibonacci reversal level off a daily pivot.
   factor – 0.382 / 0.618 / etc.  dir – :support | :resistance"
  [^BarSeries series factor dir]
  (let [ty (case dir
             :support    FibonacciReversalIndicator$FibReversalTyp/SUPPORT
             :resistance FibonacciReversalIndicator$FibReversalTyp/RESISTANCE)]
    (FibonacciReversalIndicator. (pivot-point series) (double factor) ty)))

;;; ── Value extraction ────────────────────────────────────────────────────────

(defn indicator-value
  "Get the double value of an indicator at bar index."
  [indicator ^long index]
  (-> indicator (.getValue (int index)) .doubleValue))

(defn ind-values
  "Get all computable values of indicator as a double vector.
   Starts from first-index (default 0)."
  ([indicator series]
   (ind-values indicator series 0))
  ([indicator series first-index]
   (let [n (bar-count series)]
     (mapv #(indicator-value indicator %) (range first-index n)))))

;;; ── Known indicator registry ────────────────────────────────────────────────

(def class-name-overrides
  "ta4j classes whose names don't end in 'Indicator'."
  {:CashFlow "org.ta4j.core.analysis.CashFlow"
   :Returns  "org.ta4j.core.analysis.Returns"})

(def all-known-indicators
  "Map of keyword → indicator constructor fn (series bar-count → indicator)."
  {:RSI               rsi
   :SMA               sma
   :EMA               ema
   :MACD              (fn [s _] (macd s))
   :STDDEV            stddev
   :ATR               atr
   :BollingerBandWidth (fn [s params] (bollinger-band-width s (first params) (second params)))})

;;; ── Generic indicator dispatcher ────────────────────────────────────────────

(def ^:private indicator-dispatch
  "Complete keyword → (fn [series params]) constructor map for all declared indicators."
  {:RSI                   (fn [s p] (rsi              s (first p)))
   :SMA                   (fn [s p] (sma              s (first p)))
   :EMA                   (fn [s p] (ema              s (first p)))
   :ATR                   (fn [s p] (atr              s (first p)))
   :STDDEV                (fn [s p] (stddev           s (first p)))
   :BollingerBandWidth    (fn [s p] (bollinger-band-width s (first p) (second p)))
   :BBUpper               (fn [s p] (bollinger-upper  s (first p) (second p)))
   :BBLower               (fn [s p] (bollinger-lower  s (first p) (second p)))
   :PercentB              (fn [s p] (percent-b        s (first p) (second p)))
   :ADX                   (fn [s p] (adx              s (first p)))
   :DX                    (fn [s p] (dx               s (first p)))
   :PlusDI                (fn [s p] (plus-di          s (first p)))
   :MinusDI               (fn [s p] (minus-di         s (first p)))
   :AroonUp               (fn [s p] (aroon-up         s (first p)))
   :AroonDown             (fn [s p] (aroon-down       s (first p)))
   :AroonOsc              (fn [s p] (aroon-osc        s (first p)))
   :Chop                  (fn [s p] (chop             s (first p)))
   :StochasticOscillatorK (fn [s p] (stoch-k          s (first p)))
   :StochasticOscillatorD (fn [s p] (stoch-d          s (first p)))
   :StochasticRSI         (fn [s p] (stoch-rsi        s (first p)))
   :WilliamsR             (fn [s p] (williams-r       s (first p)))
   :CCI                   (fn [s p] (cci              s (first p)))
   :CMO                   (fn [s p] (cmo              s (first p)))
   :MACD                  (fn [s p] (macd             s (first p) (second p)))
   :MACDSignal            (fn [s p] (macd-signal      s (first p) (second p) (nth p 2)))
   :MACDHist              (fn [s p] (macd-hist        s (first p) (second p) (nth p 2)))
   :ParabolicSAR          (fn [s p] (parabolic-sar    s (first p) (second p) (nth p 2)))
   :OBV                   (fn [s _] (obv              s))
   :UlcerIndex            (fn [s p] (ulcer-index      s (first p)))
   :MassIndex             (fn [s p] (mass-index       s (first p) (second p)))
   :CMF                   (fn [s p] (cmf              s (first p)))
   :KCUpper               (fn [s p] (keltner-upper    s (first p) (second p) (nth p 2)))
   :KCLower               (fn [s p] (keltner-lower    s (first p) (second p) (nth p 2)))
   :Fisher                (fn [s p] (fisher           s (first p)))
   :PivotPoint            (fn [s _] (pivot-point      s))
   :PivotR1               (fn [s _] (pivot-reversal   s :R1))
   :PivotR2               (fn [s _] (pivot-reversal   s :R2))
   :PivotS1               (fn [s _] (pivot-reversal   s :S1))
   :PivotS2               (fn [s _] (pivot-reversal   s :S2))
   :FibR38                (fn [s _] (pivot-fib-reversal s 0.382 :resistance))
   :FibR62                (fn [s _] (pivot-fib-reversal s 0.618 :resistance))
   :FibS38                (fn [s _] (pivot-fib-reversal s 0.382 :support))
   :FibS62                (fn [s _] (pivot-fib-reversal s 0.618 :support))})

(defn known-kinds
  "Sorted vector of indicator kinds (keywords) callable via the dispatch."
  []
  (vec (sort (keys indicator-dispatch))))

(defn indicator
  "Construct a ta4j indicator by keyword using the indicator-dispatch registry.
   Returns the indicator object, or nil when kind is not registered.
   kind   – keyword such as :RSI, :ATR, :BollingerBandWidth
   series – ta4j BarSeries built via ds->ta4j-ohlcv
   params – seq of numeric parameters matching the indicator's constructor"
  [kind series params]
  (when-let [f (get indicator-dispatch kind)]
    (f series (vec params))))
