(ns options-trader.tui.markdown
  "Markdown → ANSI for the chat area. Covers headers, bold, italic, inline
   code, fenced blocks, tables, and bullet lists — the patterns LLM
   responses actually use. Returns a vec of pre-wrapped strings sized to
   `width` visible chars (ANSI escapes don't count)."
  (:require [clojure.string :as str]))


(def ^:private ESC "")
(defn- ansi [code] (str ESC "[" code "m"))
(def RESET   (ansi 0))
(def ^:private BOLD    (ansi 1))
(def ^:private DIM     (ansi 2))
(def ^:private ITALIC  (ansi 3))
(def ^:private UNDER   (ansi 4))
(def ^:private YELLOW  (ansi 33))

(def ^:private ansi-re #"\[[0-9;]*m")

(defn visible-length
  "Character count of s ignoring ANSI escape sequences."
  [s]
  (count (str/replace (str s) ansi-re "")))

(defn- pad-visible [s n]
  (let [v (visible-length s)]
    (if (>= v n) s (str s (apply str (repeat (- n v) \space))))))


(defn- apply-inline
  "Apply `code`, **bold**, *italic*/_italic_ to a single line.
   Order matters: code first (its contents are protected), then bold (** wins
   over single *), then italic. Patterns require non-whitespace adjacent to
   the marker so simple math like 2 * 3 isn't mis-flagged."
  [^String line]
  (-> line
      (str/replace #"`([^`]+)`"
                   (str YELLOW "$1" RESET))
      (str/replace #"\*\*(\S(?:[^*]|\*(?!\*))*?\S|\S)\*\*"
                   (str BOLD "$1" RESET))
      (str/replace #"(?:^|(?<=\s))\*(\S(?:[^*])*?\S|\S)\*(?=\s|$|[.,;:!?])"
                   (str ITALIC "$1" RESET))
      (str/replace #"(?:^|(?<=\s))_(\S(?:[^_])*?\S|\S)_(?=\s|$|[.,;:!?])"
                   (str ITALIC "$1" RESET))))


(defn- wrap-line [^String s width]
  (if (<= (visible-length s) width)
    [s]
    (loop [words   (str/split s #"\s+")
           current ""
           acc     []]
      (cond
        (empty? words)
        (if (str/blank? current) acc (conj acc current))

        (str/blank? current)
        (recur (rest words) (first words) acc)

        (<= (+ (visible-length current) 1 (visible-length (first words))) width)
        (recur (rest words) (str current " " (first words)) acc)

        :else
        (recur (rest words) (first words) (conj acc current))))))


(defn- table-row? [line]
  (and (string? line) (re-matches #"\s*\|.+\|\s*" line)))

(defn- table-separator? [line]
  (and (string? line)
       (re-matches #"\s*\|?(\s*:?-+:?\s*\|)+\s*:?-+:?\s*\|?\s*" line)))

(defn- parse-row [line]
  (->> (str/split (str/trim line) #"\|")
       (drop-while str/blank?)
       (reverse) (drop-while str/blank?) (reverse)
       (mapv str/trim)))

(defn- render-table
  [lines width]
  (let [rows         (->> lines (remove table-separator?) (map parse-row))
        n            (apply max 0 (map count rows))
        rows         (mapv (fn [r] (vec (concat r (repeat (- n (count r)) "")))) rows)
        cols         (apply mapv vector rows)
        col-widths   (mapv #(apply max 0 (map visible-length %)) cols)
        budget       (max 8 (- width (+ 2 (* 3 (count col-widths)))))
        col-widths   (let [total (apply + col-widths)]
                       (if (<= total budget)
                         col-widths
                         (mapv #(max 4 (int (* % (/ budget (double total)))))
                               col-widths)))
        seg          (fn [w] (apply str (repeat (+ 2 w) \─)))
        top-rule     (str "┌" (str/join "┬" (map seg col-widths)) "┐")
        mid-rule     (str "├" (str/join "┼" (map seg col-widths)) "┤")
        bot-rule     (str "└" (str/join "┴" (map seg col-widths)) "┘")
        fmt-row      (fn [r bold?]
                       (str "│ "
                            (str/join " │ "
                                      (map (fn [cell w]
                                             (let [cell (if (> (visible-length cell) w)
                                                          (subs cell 0 (max 1 (- w 1)))
                                                          cell)
                                                   cell (if bold? (str BOLD cell RESET) cell)]
                                               (pad-visible cell w)))
                                           r col-widths))
                            " │"))]
    (concat [(str DIM top-rule RESET)
             (fmt-row (first rows) true)
             (str DIM mid-rule RESET)]
            (map #(fmt-row % false) (rest rows))
            [(str DIM bot-rule RESET)])))


(defn- header-line? [line] (re-find #"^#{1,6}\s+" line))
(defn- fence-line?  [line] (re-find #"^```" line))
(defn- list-item?   [line] (re-find #"^\s*(?:[-*+]|\d+\.)\s+" line))

(defn- render-header [line width]
  (let [text  (str/replace line #"^#+\s+" "")
        styled (str BOLD UNDER text RESET)]
    [styled (str DIM (apply str (repeat (min (count text) width) \-)) RESET)]))

(defn render
  "Render markdown text to a vector of ANSI-styled lines fitting `width`."
  [text width]
  (let [raw-lines (str/split-lines (or text ""))]
    (loop [[ln & more] raw-lines
           acc         []
           in-fence?   false]
      (cond
        (nil? ln)
        acc

        (fence-line? ln)
        (recur more acc (not in-fence?))

        in-fence?
        (recur more (conj acc (str DIM "  " ln RESET)) true)

        (header-line? ln)
        (recur more (into acc (render-header ln width)) false)

        (table-row? ln)
        (let [tbl-lines (cons ln (take-while #(or (table-row? %)
                                                  (table-separator? %)) more))
              after     (drop (dec (count tbl-lines)) more)]
          (recur after (into acc (render-table tbl-lines width)) false))

        (list-item? ln)
        (let [m       (re-find #"^(\s*)(?:[-*+]|\d+\.)\s+(.*)$" ln)
              indent  (or (nth m 1 "") "")
              body    (nth m 2 "")
              styled  (str indent "• " (apply-inline body))
              wrapped (wrap-line styled width)]
          (recur more (into acc wrapped) false))

        (str/blank? ln)
        (recur more (conj acc "") false)

        :else
        (let [styled  (apply-inline ln)
              wrapped (wrap-line styled width)]
          (recur more (into acc wrapped) false))))))
