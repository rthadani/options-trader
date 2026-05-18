#!/usr/bin/env bb
;; Prune DuckDB backup files older than 14 days from cache/backups/.
;; No-op (exit 0) when the backups directory does not exist yet.

(require '[babashka.fs :as fs])
(import '[java.time Instant]
        '[java.time.temporal ChronoUnit])

(def backups-dir "cache/backups")
(def max-age-days 14)

(when-not (fs/exists? backups-dir)
  (println "prune-db: no backups directory found — skipping")
  (System/exit 0))

(let [cutoff (.minus (Instant/now) max-age-days ChronoUnit/DAYS)
      files  (filter fs/regular-file? (fs/list-dir backups-dir))]
  (doseq [f files]
    (let [mtime (-> f fs/last-modified-time .toInstant)]
      (when (.isBefore mtime cutoff)
        (fs/delete f)
        (println "prune-db: deleted" (str f)))))
  (println "prune-db: done"))
