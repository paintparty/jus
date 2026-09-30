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
  "Primary REPL choices, kept in their intentional menu order."
  [{:id                      :clojure
    :label                   "Clojure"
    :description             "JVM, default"
    :requires                ["clojure"]
    :intel-mac-build-target? true}
   {:id                      :rebel
    :label                   "Clojure"
    :description             "With rebel-readline (nicer REPL)"
    :requires                ["clojure"]
    :intel-mac-build-target? true}
   {:id                      :cljr
    :label                   "Clojure CLR"
    :description             "Microsoft's .NET CLR"
    :requires                ["cljr"]
    :installer               "cljr"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://clojure.org/about/clojureclr"}
   {:id                      :babashka
    :label                   "Babashka"
    :description             "Instant startup, SCI"
    :requires                ["bb"]
    :intel-mac-build-target? true}
   {:id                      :clojurescript
    :label                   "ClojureScript"
    :description             "JS"
    :requires                ["clojure" "node"]
    :intel-mac-build-target? true}
   {:id                      :jolt
    :label                   "Jolt"
    :description             "Chez Scheme"
    :requires                ["jolt"]
    :installer               "jolt"
    :install-with-in-1?      true
    :intel-mac-build-target? false
    :intel-mac-note          "Install script exits on x86_64-macos; no Intel branch in the Homebrew formula. Build from source (needs Chez Scheme + a C compiler)."
    :guide                   "https://jolt-lang.github.io/docs/getting-started.html"}
   {:id                      :jank
    :label                   "Jank"
    :description             "C++"
    :requires                ["jank"]
    :installer               "jank"
    :args                    ["repl"]
    :install-with-in-1?      false
    :intel-mac-build-target? true
    :guide                   "https://jank-lang.org/"}
   ])

(def more-options
  "Additional REPL dialects, alphabetized so additions do not reshuffle primary choices.

  :install-with-in-1? explicitly opts an installed runtime into the in-1 copy
  flow. Entries without it must use normal missing-executable guidance."
  [{:id                      :basilisp
    :label                   "Basilisp"
    :description             "Python"
    :requires                ["basilisp"]
    :installer               "basilisp"
    :args                    ["repl"]
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://docs.basilisp.org/en/latest/"}
   
   {:id                      :let-go
    :label                   "let-go"
    :description             "Go"
    :requires                ["lg"]
    :installer               "lg"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://github.com/nooga/let-go#install"}
   {:id                      :cljgo
    :label                   "cljgo"
    :description             "Go"
    :requires                ["cljgo"]
    :installer               "cljgo"
    :args                    ["repl"]
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://muthuishere.github.io/cljgo/"}
   {:id                      :fennel
    :label                   "Fennel"
    :description             "Lua"
    :requires                ["fennel"]
    :installer               "fennel"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://fennel-lang.org/"}
   {:id                      :glojure
    :label                   "Glojure"
    :description             "Go"
    :requires                ["glj"]
    :installer               "glj"
    :install-with-in-1?      true
    :intel-mac-build-target? false
    :intel-mac-note          "Source install only: go install ... cmd/glj@latest, needs Go 1.24+. Releases ship darwin_arm64 but no darwin_amd64."
    :guide                   "https://github.com/glojurelang/glojure#prerequisites"}
   {:id                      :gloat
    :label                   "Gloat"
    :description             "Go"
    :requires                ["gloat"]
    :installer               "gloat"
    :args                    ["--repl"]
    :install-with-in-1?      true
    :intel-mac-build-target? false
    :intel-mac-note          "Source install only: no release assets at all. Makefile installer clones the repo and builds glj from source; it maps macos-int64 to darwin_amd64."
    :guide                   "https://github.com/gloathub/gloat#installation"}
   {:id                      :gobb
    :label                   "Gobb"
    :description             "Go"
    :requires                ["gobb"]
    :installer               "gobb"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://github.com/gloathub/gobb"}
   {:id                      :hy
    :label                   "Hy"
    :description             "Python"
    :requires                ["hy"]
    :installer               "hy"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://hylang.org/hy/doc/stable/"}
   {:id                      :janet
    :label                   "Janet"
    :description             "C"
    :requires                ["janet"]
    :installer               "janet"
    :install-with-in-1?      true
    :intel-mac-build-target? false
    :intel-mac-note          "No prebuilt macOS x64 since v1.38.0 (current releases ship macos-aarch64 only). Use Homebrew or build from source."
    :guide                   "https://janet-lang.org/docs/documentation.html"}
   {:id                      :joker
    :label                   "Joker"
    :description             "Go"
    :requires                ["joker"]
    :installer               "joker"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://github.com/candid82/joker#installation"}
   {:id                      :phel
    :label                   "Phel"
    :description             "PHP"
    :requires                ["phel"]
    :installer               "phel"
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://phel-lang.org/documentation/installation/"}
   {:id                      :squint
    :label                   "Squint"
    :description             "Node.js"
    :requires                ["squint"]
    :installer               "squint"
    :args                    ["repl"]
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://squint-cljs.github.io/squint/"}
   {:id                      :ys
    :label                   "YAMLScript"
    :description             "GraalVM"
    :requires                ["ys"]
    :installer               "ys"
    :args                    ["--help"]
    :install-with-in-1?      true
    :intel-mac-build-target? true
    :guide                   "https://yamlscript.org/"}])

(defn installation-supported?
  ([] (installation-supported? (System/getProperty "os.name" "")))
  ([os-name] (boolean (re-find #"^(linux|mac)" (str/lower-case os-name)))))

(defn available-options
  ([] (available-options (System/getProperty "os.name" "")))
  ([os-name] (into (filterv #(:enabled? % true) options)
                   (filterv #(and (:enabled? % true)
                                  (installation-supported? os-name))
                            more-options))))

(defn option
  [id]
  (some #(when (= id (:id %)) %) (into options more-options)))

(defn in-1-installation-supported?
  "Whether the advertised in-1 install path supports this runtime here."
  [id]
  (let [runtime (option id)]
    (and (:install-with-in-1? runtime)
         (or (not style/intel-mac?)
             (not= false (:intel-mac-build-target? runtime))))))

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
                   "Refer to https://github.com/nooga/let-go#install and try again.")
    (str error-prefix
         "Required executable not found: " executable "\n"
         style/margin-inline-start-str
         "Install " executable " and try again.")))

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
    :local (str (io/file home ".local"))))

(defn install-snippet
  "Builds an in-1 command that installs a runtime and launches it."
  [id mode]
  (let [{:keys [installer args]} (option! id)
        install-flag (case mode
                       :temporary "--temp"
                       :local "--local")
        launch (str/join " " (cons installer args))]
    (str "in-1 " install-flag " " installer " && " launch)))

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
  "PATH wins over local and then temporary in-1 wrappers."
  ([id] (discover id (environment)))
  ([id env]
   (when-let [executable (:installer (option! id))]
     (or (find-on-path executable env)
         (some #(executable-path (io/file (install-prefix % env) "bin" executable))
               [:local :temporary])))))


