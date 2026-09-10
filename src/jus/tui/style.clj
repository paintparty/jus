(ns jus.tui.style
  (:require [charm.style.core :as charm-style]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(def no-color? true)

;; Icons
(def error-prefix #_"▲ " "! ")

;; os utils 
;; TODO - add these to util ns or a distinct lib 
(defn target-os? [s]
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) s))
(def windows? (target-os? "windows"))
(def windows-10?
  (delay
    (try
      (and windows?
           (let [build (-> (shell {:out :string} "cmd" "/c" "ver")
                           :out
                           (->> (re-find #"\[Version \d+\.\d+\.(\d+)"))
                           second
                           parse-long)]
             (< build 22000)))
      (catch Exception _
        false))))
(def linux? (target-os? "linux"))
(def mac? (target-os? "mac"))
(def not-mac? (not mac?))

;; Logo
#_"☯"
(def logo (if @windows-10? "*" "◒"))

;; Formatting
(def margin-inline-start 2)
(def margin-inline-start-str (str/join (repeat margin-inline-start " ")))
(def main-menu-logo-position {:row 1 :column margin-inline-start})

;; Colors
(def secondary-hex (charm-style/hex "#9e9e9e"))
(def accent-hex (charm-style/hex "#4eb5ec"))
(def success-hex (charm-style/hex "#00d700"))
(def error-hex (charm-style/hex "#ff0000"))

;; Styles
(def secondary-style (charm-style/style :fg secondary-hex))
(def neutral-accent-style (charm-style/style :fg secondary-hex :bold true))
(def neutral-accent-italic-style (charm-style/style :fg secondary-hex :bold true :italic true))
(def accent-style (charm-style/style :fg accent-hex))
(def error-style (charm-style/style :fg error-hex :bold true))
(def success-style (charm-style/style :fg success-hex :bold true))
(def accent-italic-style (charm-style/style :fg accent-hex :italic true))
(def italic-style (charm-style/style :italic true))

(defn style
  "Construct a Charm style."
  [& args]
  (apply charm-style/style args))

(defn render
  "Render text with a Charm style."
  [style text]
  (charm-style/render style text))

(defn sgr
  "Wrap text in an ANSI SGR code."
  [code s]
  (str "\033[" code "m" s "\033[0m"))

(defn dim
  "Low-emphasis text. Uses SGR dim so it adapts to the user's terminal theme."
  [s]
  (sgr "2" s))

(defn secondary+italic
  "Low-emphasis text. Uses SGR dim so it adapts to the user's terminal theme."
  [s]
  (if no-color?
    (sgr "2;3" s)
    (sgr "2;3" s)))

(defn italic
  [s]
  (charm-style/render italic-style s))

(defn secondary
  "Secondary text. Uses a neutral medium gray."
  [s]
  (if no-color?
    (dim s)
    (charm-style/render secondary-style s)))

(defn default
  "Default text. Uses the user's terminal foreground."
  [s]
  (str s))

(defn primary
  "Primary/emphasized text. Uses SGR bold with the user's terminal foreground."
  [s]
  (sgr "1" s))

(defn primary-italic
  [s]
  (sgr "1;3" s))

(defn error
  "Primary/emphasized text. Uses SGR bold with the user's terminal foreground."
  [s]
  (if no-color?
    (primary s)
    (charm-style/render error-style s)))

(defn accent
  "Emphasized secondary text.
   In no-color mode, uses SGR bold with the current secondary.
   With colorized theme, uses the accent color."
  [s]
  (charm-style/render
   (if no-color?
     neutral-accent-style
     accent-style)
   s))

(defn success
  "Emphasized success text.
   With colorized theme, uses the success color, otherwise nothing."
  [s]
  (if no-color? s (charm-style/render accent-style s)))

(defn accent-italic
  "Emphasized secondary text in italic.
   In no-color mode, uses SGR bold with the current secondary.
   With colorized theme, uses the accent color."
  [s]
  (charm-style/render
   (if no-color?
     neutral-accent-italic-style
     accent-italic-style)
   s))

(defn bold-secondary [s]
  (if no-color?
    (primary s)
    (charm-style/render (charm-style/style :fg secondary-hex :bold true) s)))

(defn bold-accent [s]
  (if no-color?
    (primary s)
    (charm-style/render (charm-style/style :fg accent-hex :bold true) s)))

(defn hyperlinks-enabled?
  []
  (and (nil? (System/getenv "NO_COLOR"))
       (not= "dumb" (System/getenv "TERM"))))

(defn hyperlink
  [label url]
  (if (hyperlinks-enabled?)
    (str "\033]8;;" url "\033\\" label "\033]8;;\033\\")
    label))

(defn helper-lines
  "Wrap helper text by visible width, rendering only Markdown links and code spans."
  [text width]
  (let [width (max 1 width)
        tokens (re-seq #"\[([^\]]+)\]\((https?://[^\s)]+)\)([.,;:!?]?)|([^\s]+)" text)
        words (mapcat (fn [[_ label url punctuation plain]]
                        (for [word (clojure.string/split (if label (str label punctuation) plain) #"\s+")
                              piece (partition-all width (clojure.string/replace word "`" ""))]
                          {:text (apply str piece) :url url})) tokens)]
    (:lines
     (reduce (fn [{:keys [lines column]} {:keys [text url]}]
               (let [new-line? (> (+ column (if (pos? column) 1 0) (count text)) width)
                     gap (if (or new-line? (zero? column)) "" " ")
                     rendered (str gap (if url (hyperlink text url) text))]
                 {:lines (if new-line? (conj lines rendered)
                             (update lines (dec (count lines)) str rendered))
                  :column (+ (if new-line? 0 column) (count gap) (count text))}))
             {:lines [""] :column 0} words))))
