(ns jus.tui.projects
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.file Files Path StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes)))

(def recent-days 14)
(def recent-limit 5)
(def ^:private ignored-directories
  #{".git" ".hg" ".svn" "node_modules" "target" "dist" "build" "vendor"
    ".cpcache" ".shadow-cljs" ".clj-kondo" ".jolt" ".cache" ".venv" "__pycache__"})

(defn canonical-path [path]
  (let [expanded (fs/path (fs/expand-home path))]
    (when-not (.isAbsolute expanded)
      (throw (ex-info "Project paths must be absolute or start with ~/" {:path path})))
    (.getCanonicalPath (fs/file expanded))))

(defn activity
  "Newest file modification time, excluding generated trees and symbolic links.
   Bounds each scan to 20,000 entries, 24 levels, and one second."
  [directory]
  (let [latest (atom nil)
        visited (atom 0)
        limited? (atom false)
        deadline (+ (System/nanoTime) 1000000000)
        continue? (fn []
                    (if (or (> (swap! visited inc) 20000)
                            (> (System/nanoTime) deadline))
                      (do (reset! limited? true) :terminate)
                      :continue))]
    (fs/walk-file-tree
     directory
     {:max-depth 24
      :pre-visit-dir (fn [^Path dir _]
         (if (and (not= (fs/path directory) dir)
                  (contains? ignored-directories (str (.getFileName dir))))
           :skip-subtree
           (continue?)))
      :visit-file (fn [_ ^BasicFileAttributes attrs]
         (when (.isRegularFile attrs)
           (swap! latest #(max (or % 0) (.toMillis (.lastModifiedTime attrs)))))
         (when (.isDirectory attrs) (reset! limited? true))
         (continue?))
      :visit-file-failed (fn [_ _]
         (reset! limited? true)
         (continue?))})
    {:modified-at @latest :activity-limited? @limited?}))

(defn project [path]
  (try
    (let [canonical (canonical-path path)
          exists? (fs/directory? canonical)]
      (merge {:path canonical
              :label (or (some-> (fs/file-name canonical) str) canonical)
              :missing? (not exists?)}
             (when exists? (activity canonical))))
    (catch Exception error
      {:path path :label path :missing? true :error (.getMessage error)})))

(defn sections
  "Recent entries appear once, newest first; the remaining entries sort by name."
  [projects now]
  (let [cutoff (- now (* recent-days 24 60 60 1000))
        alphabetical (fn [p] [(str/lower-case (:label p)) (:path p)])
        recent (->> projects
                    (filter #(and (not (:missing? %))
                                  (>= (or (:modified-at %) 0) cutoff)))
                    (sort-by (juxt #(unchecked-negate (long (:modified-at %))) alphabetical))
                    (take recent-limit)
                    vec)
        recent-paths (set (map :path recent))]
    {:recent recent
     :other (->> projects (remove #(recent-paths (:path %)))
                 (sort-by alphabetical) vec)}))

(defn catalog [paths now]
  (sections (vals (reduce (fn [result path]
                           (let [entry (project path)]
                             (assoc result (:path entry) entry)))
                         {} paths))
            now))

(defn readme
  "Read an existing top-level README; prefer Markdown, then plain text."
  [directory]
  (let [files (->> (fs/list-dir directory)
                   (filter fs/regular-file?)
                   (filter #(re-matches #"(?i)readme(?:\.(?:md|markdown|txt|rst|adoc|org))?"
                                        (str (fs/file-name %))))
                   (sort-by (fn [p]
                              [(case (str/lower-case (str (fs/file-name p)))
                                 "readme.md" 0 "readme.markdown" 1 "readme" 2 3)
                               (str p)])))]
    (if-let [path (first files)]
      (do
        (when (> (Files/size (fs/path path)) (* 2 1024 1024))
          (throw (ex-info "README is larger than the 2 MB viewer limit." {})))
        {:path (str path) :text (slurp (str path))})
      (throw (ex-info "No README found in the project root." {})))))

(defn- changelog-paths
  [directory]
  (filter #(= "changelog.md" (str/lower-case (str (fs/file-name %))))
          (fs/list-dir directory)))

(defn changelog-exists?
  "Recognize a nonempty changelog or a conflicting directory, regardless of case."
  [directory]
  (boolean (some #(or (not (fs/regular-file? %)) (pos? (Files/size %)))
                 (changelog-paths directory))))

(defn- description [directory]
  (or (try
        (let [path (fs/path directory "deps.edn")]
          (when (fs/regular-file? path)
            (let [value (:description (edn/read-string (slurp (str path))))]
              (when (and (string? value) (not (str/blank? value))) value))))
        (catch Exception _ nil))
      (try
        (some (fn [paragraph]
                (let [text (str/trim paragraph)]
                  (when-not (or (str/blank? text)
                                (re-find #"^(?:#|<|!\[|```|~~~|\|)" text))
                    (-> text
                        (str/replace #"<[^>]*>" "")
                        (str/replace #"\[([^\]]+)\]\([^)]*\)" "$1")
                        (str/replace #"[*`]" "")
                        (str/replace #"\s+" " ")
                        str/trim))))
              (str/split (:text (readme directory)) #"\r?\n\s*\r?\n"))
        (catch Exception _ nil))
      "FIXME: Add a project description."))

(defn create-changelog!
  "Create or initialize an empty changelog; never replace existing content."
  [{:keys [path label]}]
  (when (changelog-exists? path)
    (throw (ex-info "A CHANGELOG.md already exists." {})))
  (let [existing (first (sort-by str (changelog-paths path)))
        target (or existing (fs/path path "CHANGELOG.md"))
        content (str "# Changelog\n" label ": " (description path)
                     "\n\n\n## Unreleased\n\n\n## 0.1.0\n\n### Added\n- Initial commit.\n")]
    (Files/write target (.getBytes content "UTF-8")
                 (into-array StandardOpenOption
                             (if existing
                               [StandardOpenOption/WRITE]
                               [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))
    (str target)))
