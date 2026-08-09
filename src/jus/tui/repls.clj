(ns jus.tui.repls
  (:require [clojure.java.io :as io])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def clojurescript-version "1.12.145")

(def rebel-readline-version "0.1.11")

(def options
  [{:id          :clojure
    :label       "Clojure"
    :description "default"
    :requires    ["clojure"]}
   {:id          :rebel
    :label       "Clojure, with rebel-readline"
    :description "nicer experience"
    :requires    ["clojure"]}
   {:id          :babashka
    :label       "Babashka"
    :description "instant startup"
    :requires    ["bb"]}
   {:id          :clojurescript
    :label       "ClojureScript"
    :description "JS"
    :requires    ["clojure" "node"]}])

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
                    "--repl-env" "node"]))

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
  (case executable
    "clojure" (str "Required executable not found: clojure\n"
                   "Install the official Clojure CLI from "
                   "https://clojure.org/guides/install_clojure and try again.\n")
    "bb" (str "Required executable not found: bb\n"
              "Install Babashka from https://babashka.org/ and try again.\n")
    "node" (str "Required executable not found: node\n"
                "Install Node.js from https://nodejs.org/en/download and try again.\n")))
