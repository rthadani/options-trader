(ns options-trader.data.universes
  "Config-driven ticker-list fetcher with symbol normalisation.
   Sources are declared in resources/universes/sources.edn;
   normalisation rules live in resources/universes/symbol-rules.edn.

   Pure path (extract-tickers + normalise-symbol) is fixture-testable;
   fetch-and-normalise calls http-kit only when given a real URL."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [net.cgrand.enlive-html :as html]))

;;; ── Config loading ──────────────────────────────────────────────────────────

(defn- load-edn [resource-path]
  (when-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))))

(def ^:private sources
  (delay (load-edn "universes/sources.edn")))

(def ^:private symbol-rules
  (delay (load-edn "universes/symbol-rules.edn")))

;;; ── HTML parsing ────────────────────────────────────────────────────────────

(defn extract-tickers
  "Extract ticker symbols from an HTML source.
   src may be a String of HTML or a java.io.Reader.
   col-idx (0-based) selects which <td> column holds the symbol."
  ([src] (extract-tickers src 0))
  ([src col-idx]
   (let [reader   (if (instance? java.io.Reader src)
                    src
                    (java.io.StringReader. src))
         resource (html/html-resource reader)
         rows     (html/select resource [:table.wikitable :tbody :tr])
         tickers  (for [row rows
                        :let [cells (html/select row [:td])]
                        :when (>= (count cells) (inc col-idx))]
                    (-> cells (nth col-idx) html/text str/trim))]
     (vec (remove str/blank? tickers)))))

(def parse-tickers
  "Alias for extract-tickers (back-compat)."
  extract-tickers)

;;; ── Symbol normalisation ────────────────────────────────────────────────────

(defn normalise-symbol
  "Apply normalisation rules to a single raw ticker string.
   Order: strip footnote markers first, then apply dot replacements, then trim."
  [raw]
  (let [rules       @symbol-rules
        dot-map     (:dot-replacements rules {})
        pat-str     (:footnote-pattern rules)
        footnote-re (when pat-str (re-pattern pat-str))
        stripped    (if footnote-re (str/replace raw footnote-re "") raw)
        replaced    (get dot-map stripped stripped)]
    (str/trim replaced)))

(def normalize-symbol
  "Alias for normalise-symbol (American spelling, back-compat)."
  normalise-symbol)

(defn normalise-symbols
  "Apply normalisation rules to a seq of raw ticker strings.
   Returns a vector of normalised, non-blank symbols."
  [raw-tickers]
  (->> raw-tickers
       (map normalise-symbol)
       (remove str/blank?)
       vec))

(def normalize-symbols
  "Alias for normalise-symbols (American spelling, back-compat)."
  normalise-symbols)

;;; ── HTTP fetch ──────────────────────────────────────────────────────────────

(defn fetch-html
  "Fetch raw HTML from url. Returns HTML string or :unavailable on error.
   timeout-ms defaults to 15000."
  ([url] (fetch-html url 15000))
  ([url timeout-ms]
   (try
     (let [{:keys [body error]} @(http/get url {:timeout timeout-ms})]
       (if error
         (do (println "fetch-html error:" error) :unavailable)
         body))
     (catch Exception e
       (println "fetch-html exception:" (.getMessage e))
       :unavailable))))

(defn fetch-and-normalise
  "Fetch HTML from url, extract tickers at col-idx, and normalise.
   Returns a normalised vector or :unavailable on HTTP error.
   Network is only touched here — extract-tickers + normalise-symbols are pure."
  ([url] (fetch-and-normalise url 0))
  ([url col-idx] (fetch-and-normalise url col-idx 15000))
  ([url col-idx timeout-ms]
   (let [body (fetch-html url timeout-ms)]
     (if (= body :unavailable)
       :unavailable
       (-> body
           (extract-tickers col-idx)
           normalise-symbols)))))

;;; ── Source-config API ───────────────────────────────────────────────────────

(defn fetch-source
  "Fetch and normalise tickers for the given source key (e.g. :sp500).
   Returns a normalised vector of ticker strings, or :unavailable on error."
  [source-key & {:keys [timeout-ms] :or {timeout-ms 15000}}]
  (let [cfg (get @sources source-key)]
    (if-not cfg
      (do (println "Unknown source:" source-key) :unavailable)
      (fetch-and-normalise (:url cfg)
                           (or (:ticker-column cfg) 0)
                           timeout-ms))))

(defn ticker-fetch
  "Alias for fetch-source — delegates by source key."
  [source-key]
  (fetch-source source-key))

(defn fetch-sp500
  "Fetch S&P 500 tickers from Wikipedia. Returns normalised vector or :unavailable."
  []
  (fetch-source :sp500))

(defn fetch-nasdaq100
  "Fetch Nasdaq-100 tickers from Wikipedia. Returns normalised vector or :unavailable."
  []
  (fetch-source :nasdaq100))

(defn fetch-all
  "Fetch and merge tickers from all configured sources.
   Skips any source that returns :unavailable."
  []
  (->> (keys @sources)
       (map fetch-source)
       (remove #{:unavailable})
       (apply concat)
       distinct
       vec))

;;; ── Seeding scaffold (Phase 3 placeholder) ──────────────────────────────────

(defn seed-builtins!
  "Seed built-in universes into the DuckDB layer.
   Not implemented until Phase 3 — DuckDB schema bootstrap lands there."
  []
  (throw (UnsupportedOperationException.
          "seed-builtins!: not implemented until Phase 3")))
