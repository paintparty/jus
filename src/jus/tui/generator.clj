(ns jus.tui.generator
  (:require [cljfmt.core :as cljfmt]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file FileVisitOption Files LinkOption Path Paths)
           (java.util.concurrent TimeUnit)))

(def ^{:doc "The sole pinned deps-new coordinate used for generator subprocesses."}
  deps-new-coordinate
  '{io.github.seancorfield/deps-new
    {:git/tag "v0.14.1"
     :git/sha "39d257c"}})

(def ^:private required-options
  [:template :name :target-dir :license/id :build])

(def ^:private option-order
  [:name :target-dir :top :main :developer :description :license/id :build])

(def ^:private request-options
  (conj (set option-order) :template))

(def ^:private cancellation-grace-ms 1000)

(def ^:private config-cleanup-attempts 5)

(def ^:private config-cleanup-retry-ms 50)

(defn- template-name [template]
  (let [template (cond
                   (keyword? template) (name template)
                   (symbol? template) (name template)
                   :else template)]
    (when-not (#{"lib" "app"} template)
      (throw (ex-info "Generator template must be lib or app"
                      {:template template})))
    template))

(defn- validate-request [request]
  (when-not (map? request)
    (throw (ex-info "Generator request must be a map"
                    {:request request})))
  (when-let [missing (seq (remove #(some? (get request %)) required-options))]
    (throw (ex-info "Generator request is missing required options"
                    {:missing (vec missing)})))
  (when (contains? request :overwrite)
    (throw (ex-info "Generator requests must not overwrite existing targets"
                    {:option :overwrite})))
  (when-let [unknown (seq (remove request-options (keys request)))]
    (throw (ex-info "Generator request contains unknown options"
                    {:unknown (vec unknown)})))
  request)

(defn- ordered-options [request]
  (let [request (cond-> (dissoc request :template)
                  (str/blank? (:developer request))
                  (dissoc :developer)
                  (str/blank? (:description request))
                  (dissoc :description))
        known   (keep #(find request %) option-order)]
    known))

(defn config-data
  "Builds temporary Clojure CLI configuration for a deps-new request map.

   Keeping EDN strings in a file avoids native Windows command-line quote
   normalization. Blank descriptions are omitted so deps-new can supply its
   template default."
  [request]
  (let [request  (validate-request request)
        template (template-name (:template request))]
    {:deps deps-new-coordinate
     :aliases
     {:jus/generate
      {:exec-fn (symbol "org.corfield.new" template)
       :exec-args (into {} (ordered-options request))}}}))

(defn command
  "Builds the pinned JVM Clojure command for a prepared config directory."
  [request]
  (config-data request)
  ["clojure" "-Srepro" "-X:jus/generate"])

(defn- absolute-target-dir [target-dir]
  (let [path (if (instance? Path target-dir)
               target-dir
               (Paths/get (str target-dir) (make-array String 0)))]
    (str (-> ^Path path .toAbsolutePath .normalize))))

(defn- delete-tree! [path]
  (when (and path
             (Files/exists ^Path path
                           (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
    (with-open [paths (Files/walk ^Path path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by #(.getNameCount ^Path %) >
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists entry)))))

(defn- config-cleanup [dir]
  (delay
    (loop [attempt 1]
      (let [error (try
                    (delete-tree! dir)
                    nil
                    (catch Exception error
                      error))]
        (cond
          (nil? error) nil
          (< attempt config-cleanup-attempts)
          (do
            (Thread/sleep config-cleanup-retry-ms)
            (recur (inc attempt)))
          :else nil)))))

(defn- cleanup-config-dir! [dir]
  (force (config-cleanup dir)))

(defn- create-config-dir! [request]
  (let [dir (Files/createTempDirectory
             "jus-generator-"
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [request (update request :target-dir absolute-target-dir)]
        (spit (.toFile (.resolve dir "deps.edn"))
              (pr-str (config-data request)))
        dir)
      (catch Exception error
        (cleanup-config-dir! dir)
        (throw error)))))

(defn- capture-stream-async [stream]
  (future
    (with-open [stream stream]
      (slurp stream))))

;; Single-column layout for bb.edn. cljfmt only adjusts whitespace and never
;; moves a value to a new line, so the line breaks are added here first.

(defn- comment-node? [n] (= :comment (node/tag n)))

(defn- trailing-newline? [nodes]
  (some-> (peek nodes) node/string (str/ends-with? "\n")))

(defn- ensure-newline [nodes]
  (if (trailing-newline? nodes) nodes (conj nodes (node/newlines 1))))

(defn- sexpr-or-nil [n]
  (try (node/sexpr n) (catch Exception _ nil)))

(defn- map-items
  "Splits map children into :form and :comment items. A comment on the same
  line as the preceding form is marked :trailing?."
  [children]
  (loop [[n & more] children, items [], line-break? true]
    (cond
      (nil? n) items
      (node/linebreak? n) (recur more items true)
      (node/whitespace? n) (recur more items line-break?)
      (comment-node? n) (recur more (conj items {:comment n :trailing? (not line-break?)}) true)
      :else (recur more (conj items {:form n}) false))))

(defn- single-column-map
  "Puts each key and its value on separate lines, with a blank line between
  entries. Comments stay attached to the entry that follows them. Returns the
  map node unchanged if its forms are not key/value pairs."
  [map-node indent]
  (let [items  (map-items (node/children map-node))
        indent (node/whitespace-node (apply str (repeat indent " ")))
        gap    (fn [out] (if (seq out) (conj (ensure-newline out) (node/newlines 1)) out))
        indent-after (fn [out] (if (seq out) (conj out indent) out))]
    (if (odd? (count (filter :form items)))
      map-node
      (loop [[item & more] items, out [], key? true, leading? false]
        (cond
          (nil? item)
          (node/replace-children map-node out)

          (:trailing? item)
          (recur more (conj out (node/whitespace-node " ") (:comment item)) key? leading?)

          (:comment item)
          (recur more
                 (conj (indent-after (if leading? out (gap out))) (:comment item))
                 key?
                 true)

          key?
          (recur more
                 (conj (indent-after (if leading? out (gap out))) (:form item))
                 false
                 false)

          :else
          (recur more
                 (conj out (node/newlines 1) indent (:form item))
                 true
                 false))))))

(defn- single-column-tasks
  "Applies `single-column-map` to the value of :tasks in a bb.edn map node."
  [map-node]
  (let [forms (remove node/whitespace-or-comment? (node/children map-node))
        tasks (some (fn [[k v]] (when (= :tasks (sexpr-or-nil k)) v))
                    (partition 2 forms))]
    (if (and tasks (= :map (node/tag tasks)))
      (node/replace-children
       map-node
       (mapv #(if (identical? % tasks) (single-column-map tasks 2) %) (node/children map-node)))
      map-node)))

(defn- single-column-bb-edn
  "Lays out the top-level map and the :tasks map of bb.edn source one key and
  value per line."
  [source]
  (let [root (parser/parse-string-all source)
        map-node (first (filter #(= :map (node/tag %)) (node/children root)))]
    (if-not map-node
      source
      (node/string
       (node/replace-children
        root
        (mapv #(if (identical? % map-node)
                 (single-column-map (single-column-tasks %) 1)
                 %)
              (node/children root)))))))

(defn- format-generated-bb-edn! [target-dir]
  (let [bb-edn (io/file target-dir "bb.edn")]
    (when (.isFile bb-edn)
      (-> (slurp bb-edn)
          (str/replace #":require\b" ":requires")
          single-column-bb-edn
          (cljfmt/reformat-string {:align-map-columns? true})
          (as-> formatted (spit bb-edn formatted))))))

(defn start!
  "Starts a generator command and returns an opaque process handle.

   Standard output and standard error are drained concurrently and captured
   rather than attached to the TUI terminal."
  [request]
  (let [request    (validate-request request)
        config-dir (create-config-dir! request)]
    (try
      (let [builder (ProcessBuilder. ^java.util.List (command request))
            process (.start (.directory builder (.toFile config-dir)))]
        {::process     process
         ::out         (capture-stream-async (.getInputStream process))
         ::err         (capture-stream-async (.getErrorStream process))
         ::target-dir  (:target-dir request)
         ::config-dir  config-dir
         ::cleanup     (config-cleanup config-dir)})
      (catch Exception e
        (cleanup-config-dir! config-dir)
        (throw e)))))

(defn- process-from [handle]
  (or (::process handle)
      (throw (ex-info "Invalid generator process handle"
                      {:handle handle}))))

(defn await!
  "Waits for a process handle and returns {:exit-code int :out string :err string}."
  [handle]
  (let [process (process-from handle)]
    (try
      (let [exit-code (.waitFor process)
            result {:exit-code exit-code
                    :out       @(::out handle)
                    :err       @(::err handle)}]
        (if (zero? exit-code)
          (try
            (format-generated-bb-edn! (::target-dir handle))
            result
            (catch Exception e
              (assoc result
                     :exit-code 1
                     :err (str (:err result)
                               "\nFailed to format generated bb.edn: "
                               (.getMessage e)))))
          result))
      (finally
        (force (::cleanup handle))))))

(defn cancel!
  "Terminates a running generator process.

   The process receives a graceful termination request first and is forcibly
   terminated if it has not exited within the cancellation grace period."
  [handle]
  (let [process (process-from handle)]
    (when (.isAlive process)
      (try
        (.close (.getOutputStream process))
        (catch Exception _))
      (.destroy process)
      (when-not (.waitFor process cancellation-grace-ms TimeUnit/MILLISECONDS)
        (.destroyForcibly process)
        (.waitFor process)))
    (force (::cleanup handle))
    nil))

(defn- target-path [target-dir]
  (when (or (nil? target-dir)
            (and (string? target-dir) (str/blank? target-dir)))
    (throw (ex-info "Cleanup target must not be blank"
                    {:target-dir target-dir})))
  (let [path (if (instance? Path target-dir)
               target-dir
               (Paths/get (str target-dir) (make-array String 0)))
        path (-> ^Path path .toAbsolutePath .normalize)]
    (when (nil? (.getParent path))
      (throw (ex-info "Refusing to clean a filesystem root"
                      {:target-dir (str path)})))
    path))

(defn cleanup!
  "Removes only the target directory partially created by a generator request.

   Symbolic links are deleted as links and are never followed. Missing targets
   are treated as already clean."
  [target-dir]
  (let [path (target-path target-dir)]
    (when (Files/exists path
                        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (delete-tree! path))
    nil))
