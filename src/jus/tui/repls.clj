(ns jus.tui.repls
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jus.tui.style :as style :refer [error-prefix]])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def clojurescript-version "1.12.145")

(def rebel-readline-version "0.1.11")

(def options
  [{:id          :clojure
    :label       "Clojure"
    :description "JVM, default"
    :requires    ["clojure"]}
   {:id          :rebel
    :label       "Clojure with rebel-readline"
    :description "JVM, nicer experience"
    :requires    ["clojure"]}
   {:id          :babashka
    :label       "Babashka"
    :description "Instant startup, SCI"
    :requires    ["bb"]}
   {:id          :clojurescript
    :label       "ClojureScript"
    :description "JS"
    :requires    ["clojure" "node"]}
   {:id          :jolt
    :label       "Jolt"
    :description "Chez Scheme"
    :requires    ["jolt"]
    :installer "jolt"
    :guide "https://jolt-lang.github.io/docs/getting-started.html"}
   {:id          :let-go
    :label       "let-go"
    :description "Go"
    :requires    ["lg"]
    :installer "lg"
    :guide "https://github.com/nooga/let-go#install"}
   {:id :glojure :label "Glojure" :description "Go" :requires ["glj"]
    :installer "glj" :extended? true
    :guide "https://github.com/glojurelang/glojure#installation"}
   {:id :gloat :label "Gloat" :description "Go" :requires ["gloat"]
    :installer "gloat" :extended? true :args ["--repl"]
    :guide "https://github.com/gloathub/gloat#installation"}
   {:id :gobb :label "Gobb" :description "Go" :requires ["gobb"]
    :installer "gobb" :extended? true
    :guide "https://github.com/gloathub/gobb"}
   {:id :hy :label "Hy" :description "Python" :requires ["hy"]
    :installer "hy" :extended? true :guide "https://hylang.org/hy/doc/stable/"}
   {:id :janet :label "Janet" :description "C" :requires ["janet"]
    :installer "janet" :extended? true :guide "https://janet-lang.org/docs/"}
   {:id :joker :label "Joker" :description "Go" :requires ["joker"]
    :installer "joker" :extended? true :guide "https://github.com/candid82/joker#installation"}
   {:id :phel :label "Phel" :description "PHP" :requires ["phel"]
    :installer "phel" :extended? true :guide "https://phel-lang.org/documentation/installation/"}])

(defn installation-supported?
  ([] (installation-supported? (System/getProperty "os.name" "")))
  ([os-name] (boolean (re-find #"^(linux|mac)" (str/lower-case os-name)))))

(defn available-options
  ([] (available-options (System/getProperty "os.name" "")))
  ([os-name] (filterv #(or (not (:extended? %))
                           (installation-supported? os-name)) options)))

(defn option
  [id]
  (some #(when (= id (:id %)) %) options))

(defn- option!
  [id]
  (or (option id)
      (throw (ex-info "Unknown REPL runtime" {:id id}))))

(defn- sha-256
  [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (format "%064x" (BigInteger. 1 digest))))

(defn cljs-output-dir
  [working-directory]
  (let [cache-home (or (System/getenv "XDG_CACHE_HOME")
                       (str (System/getProperty "user.home")
                            java.io.File/separator
                            ".cache"))
        canonical-directory (.getCanonicalPath (io/file working-directory))]
    (str cache-home
         java.io.File/separator "jus"
         java.io.File/separator "cljs-repl"
         java.io.File/separator clojurescript-version
         java.io.File/separator (sha-256 canonical-directory))))

(defn- deps-edn
  [coordinate version]
  (pr-str {:deps {coordinate {:mvn/version version}}}))

(defn command
  [id working-directory]
  (case (:id (option! id))
    :clojure ["clojure"]
    :rebel ["clojure"
            "-J--enable-native-access=ALL-UNNAMED"
            "-Sdeps" (deps-edn 'com.bhauman/rebel-readline rebel-readline-version)
            "-M" "-m" "rebel-readline.main"
            "--color-theme" "neutral-screen-theme"]
    :babashka ["bb" "repl"]
    :clojurescript ["clojure"
                    "-Sdeps" (deps-edn 'org.clojure/clojurescript clojurescript-version)
                    "-M" "-m" "cljs.main"
                    "--output-dir" (cljs-output-dir working-directory)
                    "--repl-env" "node"]
    :jolt ["jolt"]
    :let-go ["lg"]
    (into [(first (:requires (option! id)))] (:args (option! id)))))

(defn preparation-command
  [id _working-directory]
  (case (:id (option! id))
    :rebel ["clojure" "-P" "-Sdeps"
            (deps-edn 'com.bhauman/rebel-readline rebel-readline-version)]
    :clojurescript ["clojure" "-P" "-Sdeps"
                    (deps-edn 'org.clojure/clojurescript clojurescript-version)]
    nil))

(defn required-executables
  [id]
  (:requires (option! id)))

(defn missing-executable-message
  [executable]
  ;; TODO - use bling formatting,
  ;;        de-bold second hint line
  ;;        helper fn to do indentation
  (case executable
    "clojure" (str error-prefix
                   "Required executable not found: clojure\n"
                   style/margin-inline-start-str
                   "Install the official Clojure CLI from "
                   "https://clojure.org/guides/install_clojure and try again.")
    "bb"      (str error-prefix
                   "Required executable not found: bb\n"
                   style/margin-inline-start-str
                   "Install Babashka from https://babashka.org/ and try again.")
    "node"    (str error-prefix
                   "Required executable not found: node\n"
                   style/margin-inline-start-str
                   "Install Node.js from https://nodejs.org/en/download and try again.")
    "jolt"    (str error-prefix
                   "Required executable not found: jolt\n"
                   style/margin-inline-start-str
                   "Refer to https://jolt-lang.github.io/docs/getting-started.html and try again.")
    "lg"      (str error-prefix
                   "Required executable not found: lg\n"
                   style/margin-inline-start-str
                   "Refer to https://github.com/nooga/let-go#install and try again.")))

(defn environment
  "Installation locations and PATH, injectable for isolated validation."
  []
  {:home (or (System/getenv "HOME") (System/getProperty "user.home"))
   :tmp (or (not-empty (System/getenv "TMPDIR")) "/tmp")
   :path (or (System/getenv "PATH") "")})

(defn install-prefix
  [mode {:keys [home tmp]}]
  (case mode
    :temporary (str (io/file tmp "in-1"))
    :persistent (str (io/file home ".local"))))

(defn executable-path
  "Return an absolute executable file path, or nil."
  [path]
  (let [file (io/file path)]
    (when (and (.isFile file) (.canExecute file)) (.getAbsolutePath file))))

(defn find-on-path
  [executable {:keys [path]}]
  (some #(executable-path (io/file (if (str/blank? %) "." %) executable))
        (str/split path (re-pattern (java.util.regex.Pattern/quote
                                     java.io.File/pathSeparator)) -1)))

(defn discover
  "PATH wins over persistent and then temporary in-1 wrappers."
  ([id] (discover id (environment)))
  ([id env]
   (when-let [executable (:installer (option! id))]
     (or (find-on-path executable env)
         (some #(executable-path (io/file (install-prefix % env) "bin" executable))
               [:persistent :temporary])))))
