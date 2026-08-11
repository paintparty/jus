(ns jus.tui.repls
  (:require [clojure.java.io :as io]
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
    :requires    ["jolt"]}
   {:id          :let-go
    :label       "let-go"
    :description "Go"
    :requires    ["lg"]}])

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
    :let-go ["lg"]))

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
                   "Refer to https://github.com/nooga/let-go#install and try again.")
    ))

