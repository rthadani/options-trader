(ns options-trader.tui.watchlist
  "Live-streaming watchlist beside the portfolio panel. The user adds symbols
   with /add-to-watchlist; each one gets its own IB market-data stream and
   tick events fold into st/state under :watchlist-quotes.

   VOL and AVG come from bars_daily on add, not from the tick stream —
   IB's per-contract lot multiplier makes live volume unreliable for an
   at-a-glance column."
  (:require [charm.components.table :as ct]
            [clojure.java.io :as io]
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

(def ^:private avg-volume-window 14)

(defn- avg-daily-volume [ds sym]
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

(defn persist! []
  (util/safe-spit (paths/watchlist-file)
                  (pr-str (vec (:watchlist @st/state)))))

(defn load-symbols []
  (let [path (paths/watchlist-file)]
    (or (when-let [data (and (.exists (io/file path))
                             (util/safe-edn-read (slurp path)))]
          (when (vector? data) data))
        [])))

(defn restore!
  "Resubscribe to every persisted symbol. Silently skips ones that can't
   subscribe yet (e.g. IB still connecting) — the user can /remove or retry."
  [{:keys [ds conn]}]
  (doseq [sym (load-symbols)]
    (add! {:ds ds :conn conn :sym sym})))


;; Strip ANSI SGR escapes before measuring visible width; otherwise the
;; coloured CHG% cells push the column divider off-screen.
(def ^:private ansi-sgr-re
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

;; Widths sum to 63; charm/table adds a 1-char gap between adjacent
;; columns → 71 visible chars total. panel-width depends on this.
(def ^:private columns
  [{:title "SYM"  :width 6}
   {:title "LAST" :width 8}
   {:title "CHG%" :width 7}
   {:title "BID"  :width 8}
   {:title "ASK"  :width 8}
   {:title "BSZ"  :width 6}
   {:title "ASZ"  :width 6}
   {:title "VOL"  :width 7}
   {:title "AVG"  :width 7}])

(def ^:private total-width
  (+ (apply + (map :width columns))
     (dec (count columns))))

(defn- quote->row
  [sym {:keys [last close bid ask bid-size ask-size volume avg-volume]}]
  [sym
   (fmt-num last 8)
   (fmt-chg last close 7)
   (fmt-num bid 8)
   (fmt-num ask 8)
   (fmt-int bid-size 6)
   (fmt-int ask-size 6)
   (fmt-int volume 7)
   (fmt-int avg-volume 7)])

(defn panel-width [] total-width)

(defn panel-lines
  "Right-side watchlist column as `height` rows padded to panel-width.
   Walks :watchlist-offset (PageUp/PageDown when focused) for scrolling."
  [state height]
  (let [w       (panel-width)
        wl      (vec (:watchlist state))
        quotes  (:watchlist-quotes state)
        offset  (max 0 (min (:watchlist-offset state 0) (max 0 (dec (count wl)))))
        ;; Reserve one blank row at the top so the watchlist's header
        ;; lines up with the portfolio pane's column-headers row, not
        ;; its account-status row above it.
        top-gap [(pad-row "" w)]
        body-h  (max 1 (- height 2))   ;; -1 top-gap, -1 in-table header
        visible (->> wl (drop offset) (take body-h) vec)
        rendered (ct/table-view
                   (ct/table columns
                             (mapv (fn [sym] (quote->row sym (get quotes sym))) visible)
                             :height 0
                             :header? true)
                   {:separator " "})
        lines   (mapv #(pad-row % w) (str/split-lines rendered))
        lines   (cond-> lines
                  (empty? wl)
                  (conj (pad-row "(empty — /add-to-watchlist SYM)" w)))
        more?   (> (count wl) (+ offset body-h))
        with-hint (cond-> lines
                    more? (conj (pad-row (format "  +%d more — PgDn"
                                                 (- (count wl) offset body-h))
                                         w)))
        all      (into (vec top-gap) with-hint)
        padded   (into (vec (take height all))
                       (repeat (max 0 (- height (count all))) (pad-row "" w)))]
    padded))
