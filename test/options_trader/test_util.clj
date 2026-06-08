(ns options-trader.test-util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.duckdb :as db])
  (:import [java.io File]
           [java.sql Date]
           [java.time LocalDate]))

(def as-lower {:builder-fn rs/as-unqualified-lower-maps})

(defn tempfile-cfg
  []
  (let [f (File/createTempFile "test-" ".duckdb")]
    (.delete f)
    {:db {:path (.getAbsolutePath f)}}))

(defn make-ds
  []
  (let [conn (jdbc/get-connection {:jdbcUrl "jdbc:duckdb:"})]
    (jdbc/execute! conn
      ["CREATE TABLE bars_daily (
          symbol   VARCHAR NOT NULL,
          bar_date DATE    NOT NULL,
          open     DOUBLE,
          high     DOUBLE,
          low      DOUBLE,
          close    DOUBLE,
          volume   BIGINT,
          PRIMARY KEY (symbol, bar_date))"])
    (jdbc/execute! conn
      ["CREATE TABLE latest_indicators (
          symbol     VARCHAR PRIMARY KEY,
          updated_at TIMESTAMP DEFAULT current_timestamp)"])
    conn))

(defn seed-bars!
  [ds sym bars]
  (doseq [b bars]
    (jdbc/execute! ds
      ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
       sym
       (Date/valueOf ^String (:bar_date b))
       (:open b) (:high b) (:low b) (:close b) (:volume b)])))

(defn seed-ohlcv!
  [ds sym rows]
  (doseq [{:keys [bar_date open high low close volume]} rows]
    (jdbc/execute! ds
      ["INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
        VALUES (?, CAST(? AS DATE), ?, ?, ?, ?, ?)"
       sym bar_date open high low close volume])))

(defn query-row
  [ds sym]
  (first (jdbc/execute! ds
           [(str "SELECT * FROM latest_indicators WHERE symbol = '" sym "'")]
           as-lower)))

(defn round4 [x]
  (Double/parseDouble (format "%.4f" x)))

(defn approx=
  "Default tolerance 1e-3 — matches the eyeballed precision in
   indicator tests; pass a tol arg if you need tighter or looser."
  ([a b]     (approx= a b 1e-3))
  ([a b tol] (< (Math/abs (- (double a) (double b))) (double tol))))

(defn canned-sh [stdout]
  (fn [& _args] {:exit 0 :out stdout :err ""}))

(def raw-bars
  (edn/read-string (slurp (io/resource "fixtures/ohlcv-50.edn"))))
