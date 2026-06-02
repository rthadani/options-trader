(ns options-trader.data.news
  (:require [options-trader.data.ibkr :as ibkr]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;;; ── Lexicon-based sentiment ─────────────────────────────────────────────────

(def ^:private lexicon
  (delay (edn/read-string (slurp (io/resource "sentiment-lexicon.edn")))))

(defn- strip-ib-meta
  "IBKR headlines from BRFG and similar providers prepend a metadata block
   like '{A:800015:L:en:K:0.90:C:0.904...}' before the actual text. Strip it."
  [s]
  (str/replace (or s "") #"^\{[^}]+\}" ""))

(defn extract-k-score
  "Parse the K: field out of an IB headline metadata block — that's the
   provider's own sentiment score in the range [-1.0, +1.0]. Returns a double
   when present, else nil. The pattern allows an optional sign and decimal."
  [^String headline]
  (when-let [m (re-find #"^\{[^}]*K:(-?\d+(?:\.\d+)?)" (or headline ""))]
    (Double/parseDouble (second m))))

(defn- score-text
  "Sum signed lexicon hits over tokens in `text`. Returns 0.0 on no hits."
  [text]
  (let [words (-> text str/lower-case (str/split #"\W+"))
        hits  (keep @lexicon words)]
    (if (empty? hits) 0.0 (double (reduce + hits)))))

(defn score-headline
  "Score a raw IB headline. Prefers the provider's own K: score from the
   metadata block; falls back to the lexicon scoring of the stripped text."
  [^String headline]
  (or (extract-k-score headline)
      (score-text (strip-ib-meta headline))))

(defn classify
  "Map a score in roughly [-1,+1] to a sentiment keyword."
  [score]
  (cond (nil? score)   :neutral
        (> score 0.2)  :bullish
        (< score -0.2) :bearish
        :else          :neutral))

(def ^:private news-date-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.0"))

(defn- now-str    [] (.format (LocalDateTime/now) news-date-fmt))
(defn- days-ago-str [n]
  (.format (.minusDays (LocalDateTime/now) n) news-date-fmt))

(defprotocol INewsSource
  "Protocol for news data sources."
  (fetch-headlines [this symbol params callback]
    "Fetch historical news headlines for symbol. Invokes callback with each article map
     and a terminal :historical-news-end message. Returns the allocated req-id or :unavailable.")
  (fetch-sentiment [this symbol params]
    "Fetch sentiment score for symbol. Returns a sentiment map or :unavailable.")
  (normalise [this raw-article]
    "Normalise a raw news article to the canonical map. Returns the normalised map.")
  (supported-symbols [this]
    "Return :all or the set of supported ticker symbols."))

(deftype UnavailableNewsSource []
  INewsSource
  (fetch-headlines  [_ _symbol _params _callback] :unavailable)
  (fetch-sentiment  [_ _symbol _params]           :unavailable)
  (normalise        [_ raw-article]               raw-article)
  (supported-symbols [_]                          :all))

(def ^:private default-source (UnavailableNewsSource.))

(defn- ibkr-normalise [raw]
  {:title        (:headline raw)
   :published-at (:time raw)
   :source       (:provider-code raw)
   :article-id   (:article-id raw)
   :url          nil
   :sentiment    nil})

(defn- resolve-conid!
  "Block on contract-details for symbol and return its conid as an int.
   Returns nil if the lookup fails or times out."
  [ib-client symbol]
  (let [p (promise)]
    (ibkr/req-contract-details ib-client
      (ibkr/->contract symbol)
      (fn [events] (deliver p events)))
    (let [events (deref p 5000 nil)
          cd     (first events)]
      (some-> (or (:conid cd) (get-in cd [:contract :conid])) int))))

(deftype IbkrNewsSource [ib-client]
  INewsSource
  (fetch-headlines [_ symbol params callback]
    (let [conid          (or (:conid params) (resolve-conid! ib-client symbol))
          provider-codes (get params :provider-codes "BRFG")
          start          (get params :start-date (days-ago-str 30))
          end            (get params :end-date   (now-str))
          limit          (get params :limit 10)]
      (if-not conid
        (do (callback [{:type :error :message (str "could not resolve conid for " symbol)}])
            :unavailable)
        (ibkr/dispatch-batch! ib-client
          {:type            :req-historical-news
           :conid           conid
           :provider-codes  provider-codes
           :start-date-time start
           :end-date-time   end
           :total-results   limit}
          callback))))

  (fetch-sentiment [this symbol params]
    (let [p (promise)
          _ (fetch-headlines this symbol
              (assoc params :limit (or (:limit params) 25))
              (fn [evs] (deliver p evs)))
          evs (deref p 15000 nil)]
      (cond
        (nil? evs)              :unavailable
        (= :unavailable evs)    :unavailable
        (empty? evs)            {:symbol symbol :count 0 :score 0.0 :sentiment :neutral :samples []}
        :else
        (let [scored  (mapv (fn [e]
                              (let [raw  (:headline e)
                                    text (strip-ib-meta raw)
                                    s    (score-headline raw)]
                                {:headline text :time (:time e)
                                 :provider (:provider-code e) :score s}))
                            evs)
              total   (reduce + (map :score scored))
              mean    (/ total (double (count scored)))
              dist    (frequencies (map (comp classify :score) scored))]
          {:symbol    symbol
           :count     (count scored)
           :score     mean
           :sentiment (classify mean)
           :distribution dist
           :samples   (take 3 (sort-by (comp - :score) scored))}))))

  (normalise        [_ raw]             (ibkr-normalise raw))
  (supported-symbols [_]                :all))

(defmulti make-source
  "Construct a news source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (IbkrNewsSource. ib-client))

(defn fetch-news-headlines
  "Fetch news headlines for symbol using source and callback.
   Returns the allocated req-id or :unavailable."
  ([symbol]
   (fetch-news-headlines symbol {} default-source (fn [_])))
  ([symbol params]
   (fetch-news-headlines symbol params default-source (fn [_])))
  ([symbol params source]
   (fetch-news-headlines symbol params source (fn [_])))
  ([symbol params source callback]
   (fetch-headlines source symbol params callback)))

(defn fetch-news-sentiment
  "Fetch sentiment for symbol using source. Returns a sentiment map or :unavailable."
  ([symbol]
   (fetch-news-sentiment symbol {} default-source))
  ([symbol params]
   (fetch-news-sentiment symbol params default-source))
  ([symbol params source]
   (fetch-sentiment source symbol params)))

(defn req-news-article
  "Request a news article by provider-code and article-id via ib-client.
   callback receives the accumulated events vector. Returns the allocated req-id."
  [ib-client provider-code article-id callback]
  (ibkr/req-news-article ib-client provider-code article-id callback))

(defn fetch-article
  "Synchronously fetch the full article body for a (provider-code, article-id)
   pair. Pass either a headline event map (uses its :provider-code + :article-id)
   or the two ids explicitly. Returns {:article-type kw :article-text str} or
   :unavailable on timeout.

   article-type: 0 = plain text, 1 = HTML, 2 = binary/PDF (base64)."
  ([ib-client headline]
   (fetch-article ib-client (:provider-code headline) (:article-id headline)))
  ([ib-client provider-code article-id]
   (let [p (promise)]
     (ibkr/req-news-article ib-client provider-code article-id
       (fn [evs] (deliver p evs)))
     (let [evs (deref p 15000 nil)
           a   (first evs)]
       (if-not a
         :unavailable
         {:article-type (:article-type a)
          :article-text (:article-text a)
          :provider-code provider-code
          :article-id    article-id})))))
