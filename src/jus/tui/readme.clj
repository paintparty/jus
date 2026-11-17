(ns jus.tui.readme
  "Apply jus's library README template to a freshly generated project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn- generated-build-data [target]
  (let [forms (->> (node/children (parser/parse-string-all (slurp (io/file target "build.clj"))))
                   (filter node/sexpr-able?)
                   (map node/sexpr))
        artifact (some (fn [form]
                         (when (and (seq? form) (= 'def (first form)) (= 'lib (second form)))
                           (second (nth form 2))))
                       forms)
        url (some #(when (and (vector? %) (= :url (first %)) (string? (second %)))
                     (second %))
                  (tree-seq coll? seq forms))]
    (when-not (and artifact url)
      (throw (ex-info "Generated build.clj has no library artifact or repository URL." {})))
    {"PROJECT_ARTIFACT" (str artifact) "PROJECT_GIT_URL" url}))

(defn- generated-namespace [target]
  (or (some (fn [file]
              (when (and (.isFile ^java.io.File file) (str/ends-with? (.getName ^java.io.File file) ".clj"))
                (with-open [reader (java.io.PushbackReader. (io/reader file))]
                  (binding [*read-eval* false]
                    (let [form (read {:eof nil} reader)]
                      (when (and (seq? form) (= 'ns (first form)))
                        (second form)))))))
            (file-seq (io/file target "src")))
      (throw (ex-info "Generated library has no source namespace." {}))))

(defn write-library-readme!
  "Replace the generated library README, retaining deps-new's resolved defaults
   and license text. Read metadata from generated files rather than duplicating
   deps-new's group and namespace normalization rules."
  [{:keys [name target-dir description]}]
  (let [readme (io/file target-dir "README.md")
        original (slurp readme)
        [_ year developer] (re-find #"(?m)^Copyright © (\d{4}) ([^\r\n]*)\r?$" original)
        license (re-find #"(?m)^Distributed under [^\r\n]+" original)
        _ (when-not (and year developer license)
            (throw (ex-info "Generated README is missing its copyright or license." {})))
        data (merge (generated-build-data target-dir)
                    {"PROJECT" (clojure.core/name (symbol name))
              "PROJECT_DESC" (if (str/blank? description)
                               (second (str/split original #"\r?\n\r?\n" 3))
                               description)
              "PROJECT_CORE_NS" (str (generated-namespace target-dir))
              "YEAR" year
              "MONTH" (.format (LocalDate/now) (DateTimeFormatter/ofPattern "MMMM" Locale/ENGLISH))
              "DEVELOPER" developer
              "LICENSE" license})
        template (slurp (io/resource "jus/tui/templates/library-readme.md"))]
    (spit readme
          (str/replace template #"\{\{([A-Z_]+)\}\}"
                       (fn [[_ key]]
                         (or (get data key)
                             (throw (ex-info "Unknown library README placeholder." {:placeholder key}))))))
    data))
