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
(def intel-mac?
  (and mac?
       (contains? #{"x86_64" "amd64"}
                  (str/lower-case (System/getProperty "os.arch" "")))))
(def not-mac? (not mac?))

;; Logo

(def logo (cond @windows-10? "*" mac? "☯" :else "◒"))

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

(def flash-foreground-color-medium
  "xterm 256 color id (midtone purple) for the Enter confirmation flash.
  Charm downgrades it at render time to the detected color profile, so on a
  16 color terminal it becomes the nearest basic color and on an ascii
  terminal it is dropped, leaving only the bold frames visible."
  141)

(def flash-background-color
  "xterm 256 color id (light lavender) for the background accent flash."
  189)

(def flash-foreground-color-dark
  "xterm 256 color id (dark purple) for text in the background accent flash."
  92)

(def flash-foreground-color-light
  "xterm 256 color id (light purple) for text in the background accent flash."
  183)

(def flash-foreground-color-adaptive
  "Flash text color that contrasts with the terminal background: dark purple
  on a light background, light purple on a dark one. Charm picks the variant
  at render time from the background it detected at startup."
  (charm-style/adaptive flash-foreground-color-dark
                        flash-foreground-color-light))

(def flash-presets
  "Named Enter confirmation flashes, each a vector of frame styles."
  (let [bold (charm-style/style :bold true)
        bold-purple (charm-style/style 
                     :bold true
                     :fg flash-foreground-color-medium)]
    {:foreground-accent
     [bold-purple bold-purple bold-purple bold-purple]

     :background-accent
     (let [accent (charm-style/style
                   :fg flash-foreground-color-adaptive
                   #_:bg #_flash-background-color)]
       [accent accent accent accent])}))

(def flash-preset
  "Which entry of `flash-presets` the app uses."
  :foreground-accent
  #_:background-accent
  )

(def flash-frame-styles
  "Styles for the Enter confirmation flash, from the active preset."
  (get flash-presets flash-preset))

(def ^:dynamic *flash-frame*
  "Index into `flash-frame-styles` while the flash is running, else nil."
  nil)

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

(defn bold-italic
  [s]
  (sgr "1;3" s))

(defn bold
  [s]
  (sgr "1" s))

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

(defn selected
  "Text of the focused menu row. Bold, or the current flash frame while the
  Enter confirmation flash is running."
  [s]
  (if-let [frame *flash-frame*]
    (charm-style/render (nth flash-frame-styles frame) s)
    (primary s)))

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

(defn hyperlinks-enabled-for-environment?
  "Whether a terminal environment should receive OSC-8 hyperlinks.
   NO_COLOR controls color styling, not link capabilities."
  [environment]
  (not= "dumb" (get environment "TERM")))

(defn hyperlinks-enabled?
  []
  (hyperlinks-enabled-for-environment? (System/getenv)))

(defn- osc-8-hyperlink
  [label url]
  (str "\033]8;;" url "\033\\"
       "\033[4m" label "\033[24m"
       "\033]8;;\033\\"))

(defn hyperlink-for-environment
  [label url environment]
  (if (hyperlinks-enabled-for-environment? environment)
    (osc-8-hyperlink label url)
    label))

(defn hyperlink
  [label url]
  (if (hyperlinks-enabled?)
    (osc-8-hyperlink label url)
    label))

(defn helper-lines
  "Wrap helper text by visible width, preserving authored lines and rendering links."
  [text width]
  (let [width (max 1 width)
        wrap-line
        (fn [line]
          (let [tokens (re-seq #"\*\*\*([^*]+)\*\*\*|\*\*([^*]+)\*\*|\[([^\]]+)\]\((https?://[^\s)]+)\)([.,;:!?]?)|([^\s]+)" line)
                words (mapcat
                       (fn [[_ emphasized bold-text label url punctuation plain]]
                         (let [source (or emphasized bold-text label plain)
                               link-url (or url
                                            (when (and plain
                                                       (re-matches #"https?://[^\s]+" plain))
                                              plain))
                               emphasized? (some? emphasized)
                               bold? (some? bold-text)
                               pieces (vec
                                       (for [word (str/split source #"\s+")
                                             piece (partition-all width (str/replace word "`" ""))]
                                         (apply str piece)))]
                           (map-indexed
                            (fn [index piece]
                              {:text piece
                               :url link-url
                               :emphasized? emphasized?
                               :bold? bold?
                               :suffix (if (= index (dec (count pieces))) punctuation "")})
                            pieces))) tokens)]
            (:lines
             (reduce (fn [{:keys [lines column]} {:keys [text url emphasized? bold? suffix]}]
                       (let [visible-text (str text suffix)
                             new-line? (> (+ column (if (pos? column) 1 0)
                                             (count visible-text)) width)
                             gap (if (or new-line? (zero? column)) "" " ")
                             rendered (str gap (cond
                                                 url (hyperlink text url)
                                                 emphasized? (bold-italic text)
                                                 bold? (bold text)
                                                 :else text)
                                           suffix)]
                         {:lines (if new-line? (conj lines rendered)
                                     (update lines (dec (count lines)) str rendered))
                          :column (+ (if new-line? 0 column)
                                     (count gap) (count visible-text))}))
                     {:lines [""] :column 0} words))))]
    (vec (mapcat wrap-line (str/split (str text) #"\n" -1)))))
