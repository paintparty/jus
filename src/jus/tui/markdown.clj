(ns jus.tui.markdown
  "Render README Markdown through Glow without giving it terminal ownership."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [charm.ansi.width :as ansi-width]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jus.tui.style :as style])
  (:import (java.net URLDecoder)))

(defn- image-marker [source]
  ;; Hex survives Glow's wrapping without losing spaces or Unicode in names.
  ;; The image format in markdown.json emits the same intermediate form.
  (str "\n\ue000\n"
       (apply str (map #(format "%02x" (bit-and 255 %)) (.getBytes ^String source "UTF-8")))
       "\n\ue001\n"))

(defn- image-name [source]
  (let [path (first (str/split source #"[?#]"))
        name (last (str/split (str/replace path "\\" "/") #"/"))]
    (if (str/blank? name)
      "Image"
      (try (URLDecoder/decode (str/replace name "+" "%2B") "UTF-8")
           (catch Exception _ name)))))

(defn- image-box [source width]
  (let [label (str "image: " (image-name source))
        label (ansi-width/truncate label (max 1 (- width 5)) :tail "…")
        inside (+ 3 (ansi-width/string-width label))]
    (mapv style/dim [(str "┌" (apply str (repeat inside "─")) "┐")
                     (str "│ " label "  │")
                     (str "└" (apply str (repeat inside "─")) "┘")])))

(defn- blank-line? [line]
  (str/blank? (ansi-width/strip-ansi line)))

(defn- render-image-boxes [lines width]
  (loop [remaining (seq lines) result []]
    (if-let [line (first remaining)]
      (if (= "\ue000" (str/trim (ansi-width/strip-ansi line)))
        (let [[encoded tail] (split-with #(not= "\ue001" (str/trim (ansi-width/strip-ansi %)))
                                         (next remaining))]
          (if (seq tail)
            (let [hex (str/replace (ansi-width/strip-ansi (str/join "" encoded)) #"\s" "")
                  source (String. (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                                                  (partition 2 hex))) "UTF-8")
                  result (loop [rows result]
                           (if (and (seq rows) (blank-line? (peek rows)))
                             (recur (pop rows))
                             rows))]
              (recur (drop-while blank-line? (next tail))
                     (into (conj result "") (conj (image-box source width) ""))))
            (recur (next remaining) (conj result line))))
        (recur (next remaining) (conj result line)))
      result)))

(defn- prepare-html
  "Replace HTML images/breaks outside comments and common Markdown code forms.
   Glow itself handles Markdown images, including reference-style images."
  [text]
  (str/replace
   text
   ;; Consume code and comments before considering HTML tags. Closing fences
   ;; must use the opening delimiter and at least its length.
   #"(?ms)^ {0,3}(`{3,}|~{3,})[^\n]*\n.*?(?:^ {0,3}\1[~`]*[ \t]*(?:\n|$)|\z)|^(?:(?: {4}|\t)[^\n]*(?:\n|$))+|(`+)(?!`).*?(?<!`)\2(?!`)|<!--.*?(?:-->|\z)|\\.|(?i:<img\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>|<br\s*/?>)"
   (fn [match]
     (let [token (first match)]
       (cond
         (re-find #"(?i)^<img\b" token)
         (let [[_ double-quoted single-quoted unquoted]
               (re-find #"(?i)\ssrc\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s>]+))" token)]
           (image-marker (or double-quoted single-quoted unquoted "")))
         (re-find #"(?i)^<br\b" token) "\n"
         :else token)))))

(defn glow-output
  "Return captured ANSI output; enforce noninteractive mode and a bounded wait."
  [text width directory]
  (let [theme (fs/create-temp-file {:prefix "jus-markdown-" :suffix ".json"})]
    (try
      (spit (str theme) (slurp (io/resource "jus/tui/markdown.json")))
      (let [child (process/process
                   ["glow" "--style" (str theme) "--width" (str width)
                    "--pager=false" "--tui=false" "--preserve-new-lines" "-"]
                   (cond-> {:in text :out :string :err :string}
                     directory (assoc :dir directory)))
            result (deref child 10000 ::timeout)]
        (when (= ::timeout result)
          (process/destroy-tree child)
          (throw (ex-info "Glow timed out." {})))
        (when-not (zero? (:exit result))
          (throw (ex-info "Glow could not render this README." {})))
        (:out result))
      (finally (fs/delete-if-exists theme)))))

(defn render-readme
  "Return cached-layout data. Other README formats and Glow failures stay readable."
  [{:keys [path text] :as readme} term-width]
  (let [width (max 1 (- term-width 9))
        markdown? (boolean (re-find #"(?i)(?:\.md|\.markdown)$" (or path "")))
        plain #(vec (style/helper-lines text width))]
    (merge readme
           {:width term-width :notice nil}
           (if markdown?
             (try
               {:lines (let [lines (str/split-lines
                                    (glow-output (prepare-html text) width
                                                 (some-> path fs/parent str)))]
                         (if (seq lines) (render-image-boxes lines width) [""]))}
               (catch Exception _
                 {:lines (plain)
                  :notice "Showing text. Markdown preview needs Glow (charmbracelet/glow) on PATH."}))
             {:lines (plain)}))))
