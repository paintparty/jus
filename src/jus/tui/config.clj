(ns jus.tui.config
  (:require [babashka.fs :as fs]
            [cljfmt.core :as cljfmt]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file FileAlreadyExistsException Files LinkOption OpenOption
                          Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute)))

(def ^:private projects-path [:tui :projects])

(def ^:private config-fields
  [{:key      :groups
    :comments ["Organizations commonly used to publish projects."
               "Combined with the project name to form an identity, e.g.:"
               "io.github.joeschmoe/my-lib, org.foo/my-lib"]}
   {:key      :developers
    :comments ["People or organizations credited in generated project metadata."]}
   {:key      :parent-dirs
    :comments ["Directories where new projects typically live."]}])

(def ^:private field-label-width 15)

(declare normalize-config)

(defn global-config-path
  "Returns the Global config path."
  ([]
   (global-config-path (System/getenv "XDG_CONFIG_HOME")
                       (System/getProperty "user.home")))
  ([xdg-config-home user-home]
   (let [config-home (if (str/blank? xdg-config-home)
                       (fs/path user-home ".config")
                       (fs/path xdg-config-home))]
     (fs/path config-home "jus" "config.edn"))))

(defn config-exists?
  "Returns true when path names an entry, including a symbolic link."
  [path]
  (Files/exists (fs/path path)
                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- display-path [path]
  (let [path      (str (fs/path path))
        user-home (System/getProperty "user.home")]
    (if (str/starts-with? path (str user-home "/"))
      (str "~" (subs path (count user-home)))
      path)))

(defmacro ^:private source-location []
  (let [{:keys [line column]} (meta &form)
        file                 *file*
        path                 (if (.isAbsolute (java.io.File. file))
                               file
                               (str (System/getProperty "user.dir") "/"
                                    (if (.startsWith file "src/")
                                      file
                                      (str "src/" file))))]
    `(str ~path ":" ~line ":" ~(or column 1))))

(defn report-config-load-error!
  "Prints a startup warning for a malformed config file."
  [path error location]
  (binding [*out* *err*]
    (println "----- ERROR (Caught) ---------------------------------------------------------")
    (println (str "Type:     " (.getName (class error))))
    (println (str "Message:  " (.getMessage error)))
    (println (str "Location: " location))
    (println)
    (println (str "Unable to load " (display-path path) "."))
    (println "Possible cause: The EDN is malformed and can't be parsed.")
    (println)
    (println "The project wizard will be launched as if no config existed.")
    (println "------------------------------------------------------------------------------")))

(defn load-config-result
  "Reads EDN config from path without throwing for an absent or malformed file."
  [path]
  (if (config-exists? path)
    (try
      {:config (normalize-config (edn/read-string (slurp (str (fs/path path)))))
       :exists? true}
      (catch Exception error
        {:config   {}
         :exists?  true
         :error    error
         :location (source-location)}))
    {:config {} :exists? false}))

(defn load-config
  "Reads EDN config from path, returning an empty map when it is absent or malformed."
  [path]
  (:config (load-config-result path)))

(defn normalize-config
  "Normalizes supported config layouts to the nested :tui/:projects shape."
  [config]
  (let [groups (or (get-in config (conj projects-path :groups))
                   (:groups config)
                   [])
        developers (or (get-in config (conj projects-path :developers))
                       (:developers config)
                       [])
        parent-dirs (or (get-in config (conj projects-path :parent-dirs))
                        (:parent-dirs config)
                        [])]
    (if (and (empty? groups) (empty? developers) (empty? parent-dirs))
      {}
      {:tui {:projects {:groups groups
                        :developers developers
                        :parent-dirs parent-dirs}}})))

(defn groups
  "Returns configured project groups."
  [config]
  (get-in (normalize-config config) (conj projects-path :groups) []))

(defn developers
  "Returns configured project developers."
  [config]
  (get-in (normalize-config config) (conj projects-path :developers) []))

(defn parent-dirs
  "Returns configured parent directories."
  [config]
  (get-in (normalize-config config) (conj projects-path :parent-dirs) []))

(defn project-config
  "Builds the nested config shape used by the TUI."
  [{:keys [groups developers parent-dirs]}]
  (normalize-config {:tui {:projects {:groups groups
                                      :developers developers
                                      :parent-dirs parent-dirs}}}))

(defn- format-vector
  ([label values]
   (format-vector "" label values))
  ([base-indent label values]
   (if-let [[first-value & more-values] (seq values)]
     (let [indent (str base-indent
                       (apply str (repeat (+ 2 (count label)) " ")))]
       (str base-indent " " label "[" (pr-str first-value)
            (apply str (map #(str "\n" indent (pr-str %)) more-values))
            "]"))
     (str base-indent " " label "[]"))))

(defn- format-field
  ([field config]
   (format-field "" field config))
  ([base-indent {:keys [key comments]} config]
   (let [key-label (str key)
         label     (str key-label
                        (apply str (repeat (- field-label-width
                                              (count key-label))
                                           " ")))]
     (str (str/join "\n" (map #(str base-indent " ;; " %) comments))
          "\n"
          (format-vector base-indent
                         label
                         (case key
                           :groups (groups config)
                           :developers (developers config)
                           :parent-dirs (parent-dirs config)))))))

(defn format-config
  "Formats config as deterministic, commented, readable EDN."
  [config]
  (str "{:tui\n"
       " {;; Values used to populate the new project wizard pickers.\n"
       "  :projects\n"
       "  {\n"
       (str/join "\n\n" (map #(format-field " " % config) config-fields))
       "}}}\n"))

(defn format-config-preview
  "Formats config as deterministic EDN without instructional comments."
  [config]
  (let [preview-source (str "{:groups " (pr-str (groups config))
                            "\n:developers " (pr-str (developers config))
                            "\n:parent-dirs " (pr-str (parent-dirs config))
                            "}\n")]
    (cljfmt/reformat-string preview-source)))

(defn- already-exists-error [^Path path cause]
  (ex-info "Global config already exists; no changes were made."
           {:type :config-already-exists
            :path (str path)}
           cause))

(defn create-config!
  "Creates path from config without replacing an existing filesystem entry.
   Content is fully written to a sibling temporary file before promotion."
  [path config]
  (let [^Path target (fs/path path)
        ^Path parent (.getParent target)]
    (when-not parent
      (throw (ex-info "Global config path must have a parent directory"
                      {:type :invalid-config-path
                       :path (str target)})))
    (Files/createDirectories parent (make-array FileAttribute 0))
    (let [^Path temp-file
          (Files/createTempFile parent ".config.edn-" ".tmp"
                                (make-array FileAttribute 0))]
      (try
        (Files/writeString temp-file
                           (format-config config)
                           StandardCharsets/UTF_8
                           (into-array OpenOption
                                       [StandardOpenOption/WRITE
                                        StandardOpenOption/TRUNCATE_EXISTING]))
        (try
          (Files/createLink target temp-file)
          (catch FileAlreadyExistsException error
            (throw (already-exists-error target error))))
        target
        (finally
          (Files/deleteIfExists temp-file))))))
