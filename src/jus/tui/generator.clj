(ns jus.tui.generator
  (:require [cljfmt.core :as cljfmt]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file FileVisitOption Files LinkOption Path Paths)
           (java.util.concurrent TimeUnit)))

(def ^{:doc "The sole pinned deps-new coordinate used for generator subprocesses."}
  deps-new-coordinate
  '{io.github.seancorfield/deps-new
    {:git/tag "v0.12.2"
     :git/sha "465b303"}})

(def ^:private required-options
  [:template :name :target-dir :license/id :build])

(def ^:private option-order
  [:name :target-dir :top :main :developer :description :license/id :build])

(def ^:private request-options
  (conj (set option-order) :template))

(def ^:private cancellation-grace-ms 1000)

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

(defn- encode-option [[option value]]
  [(pr-str option) (pr-str value)])

(defn command
  "Builds the pinned JVM Clojure command for a deps-new request map.

   Values are EDN-encoded as individual argv entries and are never interpolated
   into a shell command. Blank descriptions are omitted so deps-new can supply
   its template default."
  [request]
  (let [request  (validate-request request)
        template (template-name (:template request))]
    (into ["clojure"
           "-Sdeps"
           (pr-str {:deps deps-new-coordinate})
           "-X"
           (str "org.corfield.new/" template)]
          (mapcat encode-option)
          (ordered-options request))))

(defn- capture-stream-async [stream]
  (future
    (with-open [stream stream]
      (slurp stream))))

(defn- format-generated-bb-edn! [target-dir]
  (let [bb-edn (io/file target-dir "bb.edn")]
    (when (.isFile bb-edn)
      (-> (slurp bb-edn)
          (str/replace #":require\b" ":requires")
          (cljfmt/reformat-string {:align-map-columns? true})
          (as-> formatted (spit bb-edn formatted))))))

(defn start!
  "Starts a generator command and returns an opaque process handle.

   Standard output and standard error are drained concurrently and captured
   rather than attached to the TUI terminal."
  [request]
  (let [request (validate-request request)
        process (.start (ProcessBuilder. ^java.util.List (command request)))]
    {::process    process
     ::out        (capture-stream-async (.getInputStream process))
     ::err        (capture-stream-async (.getErrorStream process))
     ::target-dir (:target-dir request)}))

(defn- process-from [handle]
  (or (::process handle)
      (throw (ex-info "Invalid generator process handle"
                      {:handle handle}))))

(defn await!
  "Waits for a process handle and returns {:exit-code int :out string :err string}."
  [handle]
  (let [process (process-from handle)
        exit-code (.waitFor process)]
    (let [result {:exit-code exit-code
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
        result))))

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
      (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
        (doseq [entry (sort-by #(.getNameCount ^Path %) >
                               (iterator-seq (.iterator paths)))]
          (Files/deleteIfExists entry))))
    nil))
