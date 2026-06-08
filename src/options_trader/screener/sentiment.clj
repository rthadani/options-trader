(ns options-trader.screener.sentiment
  (:require [clojure.string :as str]
            [options-trader.util :as util]))

(def ^:private lexicon
  (delay (util/read-edn-resource "sentiment-lexicon.edn")))

(defn score-text
  "VADER-style sentiment score. Returns double in [-1.0, 1.0] or nil for blank input."
  [text]
  (when (seq (str/trim (or text "")))
    (let [words    (-> text str/lower-case (str/replace #"[^a-z\s]" " ") (str/split #"\s+"))
          all-caps (boolean (re-find #"[A-Z]{3,}" (or text "")))
          excl-ct  (count (re-seq #"!" (or text "")))
          scores   (keep @lexicon words)
          n        (count scores)]
      (if (zero? n)
        0.0
        (let [raw     (double (/ (reduce + scores) n))
              boosted (cond-> raw
                        all-caps      (* 1.25)
                        (pos? excl-ct) (+ (* 0.05 (min excl-ct 3))))]
          (max -1.0 (min 1.0 boosted)))))))

(defn score-news
  "Score a seq of news-item maps with :title and optional :body.
   Returns {:score float :count int}."
  [news-items]
  (let [scores (->> news-items
                    (map #(score-text (str (:title %) " " (or (:body %) ""))))
                    (remove nil?))
        n      (count scores)]
    {:score (if (zero? n) 0.0 (double (/ (reduce + 0.0 scores) n)))
     :count n}))

(defn sentiment-label
  "Convert a numeric score to a label keyword."
  [score]
  (cond
    (>= score  0.35) :bullish
    (<= score -0.35) :bearish
    :else            :neutral))

(defn score-and-label
  "Return {:score float :label keyword} for text."
  [text]
  (let [s (or (score-text text) 0.0)]
    {:score s :label (sentiment-label s)}))
