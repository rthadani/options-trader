(ns options-trader.tui.watchlist
  "Live-streaming watchlist beside the portfolio panel. The user adds symbols
   with /add-to-watchlist; each one gets its own IB market-data stream tagged
   :watchlist in the sub manager. Tick events fold into st/state under
   :watchlist-quotes so the render is read-only against the atom.

   avg-volume isn't part of the tick stream — it comes from
   latest_indicators.avg_vol_20d, refreshed daily by the indicator runner.
   We snapshot it once on add and keep it static for the session."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.db.queries.watchlist :as q]
            [options-trader.paths :as paths]
            [options-trader.tui.state :as st]
            [options-trader.util :as util]
            [taoensso.timbre :as log]))

(def ^:const max-symbols
  "Soft cap on concurrent watchlist subs. Held positions + the active
   investigation symbol + watchlist must stay under the sub manager's
   own cap (default 50)."
  10)

(defn- normalise-symbol [s]
  (some-> s str str/trim str/upper-case not-empty))

(defn- coerce-num
  "IB delivers tick-size events with `:size` as either a boxed Number, a
   Java BigDecimal, or — in newer wire versions — a com.ib.client.Decimal
   that is NOT a Number subclass. (double Decimal) throws ClassCastException,
   which until now silently dropped every size update. Mirror the safe
   converter used inside the snapshot handler."
  [v]
  (cond
    (nil? v)     nil
    (number? v)  (double v)
    :else (try (Double/parseDouble (str v))
               (catch Throwable _ nil))))

(defn update-quote
  "Pure: given the symbol's current quote map and one IB tick event, return
   the new map. Always bumps :updated-at; touches the relevant price/size
   field when the event is one we care about, otherwise leaves them alone.

   Field-code key: :tick-price and :tick-size events carry it as :field,
   but :tick-string events carry it as :tick-type. We accept either."
  [m {:keys [type field tick-type price size value]}]
  (let [m            (assoc (or m {}) :updated-at (System/currentTimeMillis))
        field        (or field tick-type)
        valid-price? (and (number? price) (not= -1.0 price))]
    (case type
      :tick-price
      (if-not valid-price?
        m
        (case field
          (1 66) (assoc m :bid   price)
          (2 67) (assoc m :ask   price)
          (4 68) (assoc m :last  price)
          (9 75) (assoc m :close price)
          m))

      ;; Bid/ask sizes only. Volume is sourced from bars_daily on add!,
      ;; not from the tick stream — see the comment in the SQL file for
      ;; why IB's live ticks aren't reliable enough for an at-a-glance
      ;; VOL column.
      :tick-size
      (let [n (coerce-num size)]
        (case field
          (0 69) (assoc m :bid-size n)
          (3 70) (assoc m :ask-size n)
          m))

      m)))

(defn- on-tick
  "IB stream callback. Wrapped in try/log because dispatch-stream! swallows
   exceptions silently — a throw here would manifest as 'quotes never update'
   instead of a visible error."
  [sym ev]
  (try
    (log/debugf "watchlist tick sym=%s ev=%s" sym (pr-str ev))
    (swap! st/state update-in [:watchlist-quotes sym] update-quote ev)
    (catch Throwable t
      (log/warnf t "watchlist on-tick crashed sym=%s ev=%s" sym (pr-str ev)))))

(def ^:private avg-volume-window
  "Trading days used for the AVG column."
  14)

(defn- avg-daily-volume
  "Mean daily volume over the most recent `avg-volume-window` bars from the
   warehouse, or nil if bars_daily has no rows for sym. Delegates to the
   hugsql wrapper so the SQL itself lives in resources/sql/watchlist.sql."
  [ds sym]
  (when ds
    (try (q/avg-daily-volume ds sym avg-volume-window)
         (catch Throwable _ nil))))

(defn add!
  "Subscribe to live ticks for sym and record it in state. Returns:
     {:ok :sym S}                       on success
     {:error :already-watching :sym S}  if already in watchlist
     {:error :cap-reached :max N}       at the soft cap
     {:error :not-connected}            if no IB conn
     {:error :sub-error  :reason …}     if the sub manager rejected it"
  [{:keys [ds conn sym]}]
  (let [sym (normalise-symbol sym)]
    (cond
      (nil? sym)
      {:error :bad-symbol}

      (contains? (set (:watchlist @st/state)) sym)
      {:error :already-watching :sym sym}

      (>= (count (:watchlist @st/state)) max-symbols)
      {:error :cap-reached :max max-symbols}

      (nil? conn)
      {:error :not-connected}

      :else
      ;; Default tick set: bid/ask/last + their sizes. We do NOT request
      ;; RT_VOLUME (233) — VOL is sourced from the warehouse instead, since
      ;; IB's live volume ticks have a per-contract lot multiplier that
      ;; reads wrong for high-priced stocks.
      (let [rid (ibkr/req-market-data conn (ibkr/->contract sym)
                                       []
                                       #(on-tick sym %))]
        (cond
          (= :unavailable rid)
          {:error :not-connected}

          (and (map? rid) (:error rid))
          {:error :sub-error :reason (:error rid)}

          :else
          (let [avg-vol  (avg-daily-volume ds sym)
                last-vol (when ds (q/latest-daily-volume ds sym))]
            ;; The IB stream may have already fired a tick by the time the
            ;; DB lookup returns. Merge into whatever's there so :last/:bid
            ;; from an early tick survive, instead of being clobbered.
            (swap! st/state
                   (fn [s]
                     (-> s
                         (update :watchlist (fn [xs] (vec (concat xs [sym]))))
                         (update-in [:watchlist-quotes sym]
                                    (fn [prev]
                                      (merge (or prev {})
                                             {:avg-volume avg-vol
                                              :volume     last-vol
                                              :updated-at (System/currentTimeMillis)})))
                         (assoc-in [:watchlist-subs sym] rid))))
            {:ok true :sym sym}))))))

(defn remove!
  "Cancel sym's stream and drop it from state. Returns
   {:ok :sym S} or {:error :not-watching :sym S}."
  [{:keys [conn sym]}]
  (let [sym (normalise-symbol sym)
        rid (get-in @st/state [:watchlist-subs sym])]
    (if-not rid
      {:error :not-watching :sym sym}
      (do
        (when conn (try (ibkr/cancel-sub! conn rid) (catch Throwable _)))
        (swap! st/state
               (fn [s]
                 (-> s
                     (update :watchlist (fn [xs] (vec (remove #(= % sym) xs))))
                     (update :watchlist-quotes dissoc sym)
                     (update :watchlist-subs   dissoc sym))))
        {:ok true :sym sym}))))

(defn persist!
  "Write the current watchlist symbol list to disk."
  []
  (let [path (paths/watchlist-file)]
    (util/safe-spit path (pr-str (vec (:watchlist @st/state))))))

(defn load-symbols
  "Read persisted symbols from disk, or [] if absent."
  []
  (let [path (paths/watchlist-file)]
    (or (when-let [data (and (.exists (io/file path))
                             (util/safe-edn-read (slurp path)))]
          (when (vector? data) data))
        [])))

(defn restore!
  "Resubscribe to every persisted symbol on startup. Silently skips symbols
   that can't subscribe (e.g. IB not connected yet) — they stay in the
   in-memory list with no quote data, and the user can /remove or retry."
  [{:keys [ds conn]}]
  (doseq [sym (load-symbols)]
    (add! {:ds ds :conn conn :sym sym})))

;; Render: build the right-hand watchlist column as a vec of strings of
;; exactly `width` characters and `height` rows. Empty rows pad to height
;; so the row count matches the portfolio pane for clean side-by-side
;; composition.

(def ^:private ansi-sgr-re
  ;; ANSI SGR escape sequences: ESC [ ... m. Used to count visible width
  ;; without including invisible colour bytes.
  #"\[[0-9;]*m")

(defn- visible-length [^String s]
  (count (str/replace s ansi-sgr-re "")))

(defn- pad-row [^String s ^long width]
  (let [n (visible-length s)]
    (cond
      (= n width) s
      (< n width) (str s (apply str (repeat (- width n) \space)))
      ;; Only truncate raw text; for coloured strings we trust the caller
      ;; sized the columns and leave the row untouched.
      :else       (if (re-find ansi-sgr-re s) s (subs s 0 width)))))

(defn- ansi [code ^String s]
  (str "[" code "m" s "[0m"))

(defn- fmt-chg
  "Right-aligned coloured '+1.23%' / '-1.23%' / '-' for the change-vs-close
   column. Green when up, red when down, uncoloured at flat or no data."
  [last close width]
  (if (and (number? last) (number? close) (pos? (double close)))
    (let [pct    (* 100.0 (/ (- (double last) (double close)) (double close)))
          padded (pad-row (format "%+.2f%%" pct) width)]
      (cond
        (pos? pct) (ansi "32" padded)
        (neg? pct) (ansi "31" padded)
        :else      padded))
    (pad-row "-" width)))

(defn- fmt-num
  "Format a number into a fixed-width string. nil → '-'."
  ([v]       (fmt-num v 8 2))
  ([v width] (fmt-num v width 2))
  ([v width dp]
   (let [s (cond
             (nil? v)          "-"
             (not (number? v)) "-"
             (zero? (double v)) "-"
             :else (format (str "%." dp "f") (double v)))]
     (pad-row s width))))

(defn- fmt-int [v width]
  (let [s (cond
            (nil? v)          "-"
            (not (number? v)) "-"
            (zero? (double v)) "-"
            (>= (Math/abs (double v)) 1e9) (format "%.1fB" (/ (double v) 1e9))
            (>= (Math/abs (double v)) 1e6) (format "%.1fM" (/ (double v) 1e6))
            (>= (Math/abs (double v)) 1e3) (format "%.1fK" (/ (double v) 1e3))
            :else (format "%.0f" (double v)))]
    (pad-row s width)))

(def ^:private col-widths
  "Symbol, last, %chg-vs-prev-close, bid, ask, bidSz, askSz, vol, avgVol.
   The chg-pct column slots between LAST and BID so the price + its
   movement read as a pair."
  {:sym 6 :last 8 :chg 7 :bid 8 :ask 8 :bid-sz 6 :ask-sz 6 :vol 7 :avg 7})

(def ^:private total-width
  (+ (apply + (vals col-widths))
     (dec (count col-widths))))      ;; 1-space gap between each adjacent col

(defn- header-row []
  (let [{:keys [sym last chg bid ask bid-sz ask-sz vol avg]} col-widths]
    (str (pad-row "SYM"  sym)    " "
         (pad-row "LAST" last)   " "
         (pad-row "CHG%" chg)    " "
         (pad-row "BID"  bid)    " "
         (pad-row "ASK"  ask)    " "
         (pad-row "BSZ"  bid-sz) " "
         (pad-row "ASZ"  ask-sz) " "
         (pad-row "VOL"  vol)    " "
         (pad-row "AVG"  avg))))

(defn- quote-row [sym q]
  (let [{:keys [last close bid ask bid-size ask-size volume avg-volume]} q
        cw col-widths]
    (str (pad-row sym (:sym cw))            " "
         (fmt-num last       (:last   cw))  " "
         (fmt-chg last close (:chg    cw))  " "
         (fmt-num bid        (:bid    cw))  " "
         (fmt-num ask        (:ask    cw))  " "
         (fmt-int bid-size   (:bid-sz cw))  " "
         (fmt-int ask-size   (:ask-sz cw))  " "
         (fmt-int volume     (:vol    cw))  " "
         (fmt-int avg-volume (:avg    cw)))))

(defn panel-width
  "Width in chars the watchlist column wants. Render composes the portfolio
   pane with whatever's left after subtracting this + 1 for the divider."
  []
  total-width)

(defn panel-lines
  "Build the right-side watchlist column as a vector of exactly `height` lines,
   each padded to `panel-width` chars."
  [state height]
  (let [w       (panel-width)
        wl      (:watchlist state)
        quotes  (:watchlist-quotes state)
        header  (header-row)
        rows    (if (empty? wl)
                  [(pad-row "(empty — /add-to-watchlist SYM)" w)]
                  (mapv (fn [sym] (quote-row sym (get quotes sym))) wl))
        all     (vec (cons header rows))
        capped  (vec (take height all))
        padded  (into capped (repeat (max 0 (- height (count capped)))
                                     (pad-row "" w)))]
    (mapv #(pad-row % w) padded)))
