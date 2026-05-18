#!/usr/bin/env bb
;; Back up the DuckDB file to cache/backups/<timestamp>.duckdb.
;; No-op (exit 0) when the DB file does not exist yet.

(require '[babashka.fs :as fs])
(import '[java.time LocalDateTime]
        '[java.time.format DateTimeFormatter])

(def db-path "cache/options_trader.duckdb")
(def backups-dir "cache/backups")

(when-not (fs/exists? db-path)
  (println "backup-db: no DB file found at" db-path "— skipping")
  (System/exit 0))

(fs/create-dirs backups-dir)
(let [ts   (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
      dest (str backups-dir "/" ts ".duckdb")]
  (fs/copy db-path dest)
  (println "backup-db: backed up to" dest))
