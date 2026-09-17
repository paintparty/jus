(ns jus.tui.repls-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [jus.tui.style :as style]
            [jus.tui.repls :as repls]))

(deftest options-have-the-planned-order-and-default
  (is (= [:clojure :rebel :babashka :clojurescript :jolt :let-go
          :glojure :gloat :gobb :hy :janet :joker :phel]
         (mapv :id repls/options)))
  (is (= :clojure (:id (first repls/options))))
  (is (= ["clojure"] (:requires (repls/option :clojure))))
  (is (= ["clojure"] (:requires (repls/option :rebel))))
  (is (= ["bb"] (:requires (repls/option :babashka))))
  (is (= ["clojure" "node"] (:requires (repls/option :clojurescript))))
  (is (= ["jolt"] (:requires (repls/option :jolt))))
  (is (= ["lg"] (:requires (repls/option :let-go)))))

(deftest commands-use-pinned-ephemeral-dependencies
  (testing "Clojure and Babashka use their native REPL commands"
    (is (= ["clojure"] (repls/command :clojure "/tmp/project")))
    (is (= ["bb" "repl"] (repls/command :babashka "/tmp/project")))
    (is (= ["lg"] (repls/command :let-go "/tmp/project")))
    (is (= ["jolt"] (repls/command :jolt "/tmp/project"))))
  (testing "Rebel retains its pin, native-access option, and neutral theme"
    (let [[_clojure native-access _sdeps deps-edn
           main-flag main-opt module color-theme-flag color-theme]
          (repls/command :rebel "/tmp/project")]
      (is (= "-J--enable-native-access=ALL-UNNAMED" native-access))
      (is (= {'com.bhauman/rebel-readline
              {:mvn/version repls/rebel-readline-version}}
             (:deps (read-string deps-edn))))
      (is (= ["-M" "-m" "rebel-readline.main"
              "--color-theme" "neutral-screen-theme"]
             [main-flag main-opt module color-theme-flag color-theme]))))
  (testing "ClojureScript uses the Node REPL and managed output cache"
    (let [command (repls/command :clojurescript "/tmp/project")]
      (is (= "clojure" (first command)))
      (is (= {'org.clojure/clojurescript
              {:mvn/version repls/clojurescript-version}}
             (:deps (read-string (nth command 2)))))
      (is (= ["-M" "-m" "cljs.main"] (subvec command 3 6)))
      (is (= ["--output-dir" (repls/cljs-output-dir "/tmp/project")
              "--repl-env" "node"]
             (subvec command 6))))))

(deftest preparation-commands-expand-only-ephemeral-dependencies
  (is (nil? (repls/preparation-command :clojure "/tmp/project")))
  (is (nil? (repls/preparation-command :babashka "/tmp/project")))
  (is (nil? (repls/preparation-command :jolt "/tmp/project")))
  (is (nil? (repls/preparation-command :let-go "/tmp/project")))
  (is (= ["clojure" "-P" "-Sdeps"
          (pr-str {:deps {'com.bhauman/rebel-readline
                          {:mvn/version repls/rebel-readline-version}}})]
         (repls/preparation-command :rebel "/tmp/project")))
  (is (= ["clojure" "-P" "-Sdeps"
          (pr-str {:deps {'org.clojure/clojurescript
                          {:mvn/version repls/clojurescript-version}}})]
         (repls/preparation-command :clojurescript "/tmp/project"))))

(deftest executable-guidance-includes-runtime-specific-installation-links
  (is (= (str style/error-prefix
              "Required executable not found: node\n"
              style/margin-inline-start-str
              "Install Node.js from https://nodejs.org/en/download and try again.")
         (repls/missing-executable-message "node")))
  (is (= (str style/error-prefix
              "Required executable not found: jolt\n"
              style/margin-inline-start-str
              "Refer to https://jolt-lang.github.io/docs/getting-started.html and try again.")
         (repls/missing-executable-message "jolt")))
  (is (= (str style/error-prefix
              "Required executable not found: lg\n"
              style/margin-inline-start-str
              "Refer to https://github.com/nooga/let-go#install and try again.")
         (repls/missing-executable-message "lg"))))

(deftest extended-dialects-are-platform-filtered
  (let [options (repls/available-options "Mac OS X")]
    (is (= 12 (count options)))
    (is (not (some #(= :phel (:id %)) options)))))

(deftest in-1-installations-respect-intel-mac-build-targets
  (with-redefs [style/intel-mac? true]
    (is (false? (repls/in-1-installation-supported? :jolt)))
    (is (false? (repls/in-1-installation-supported? :janet)))
    (is (true? (repls/in-1-installation-supported? :phel))))
  (with-redefs [style/intel-mac? false]
    (is (true? (repls/in-1-installation-supported? :janet)))))

(deftest install-snippets-install-and-launch-in-a-fresh-bash-session
  (is (= "bash -c 'source <(curl -fsSL https://in-1.cc) --temp glj && exec glj'"
         (repls/install-snippet :glojure :temporary)))
  (is (= (str "bash -c 'source <(curl -fsSL https://in-1.cc) --local glj "
              "PREFIX=\"$HOME/.local\" && exec glj'")
         (repls/install-snippet :glojure :persistent)))
  (is (= (str "bash -c 'source <(curl -fsSL https://in-1.cc) --temp gloat "
              "&& exec gloat --repl'")
         (repls/install-snippet :gloat :temporary))))

(deftest discovery-prefers-path-then-persistent-then-temporary
  (let [root (fs/create-temp-dir {:prefix "jus repl discovery "})
        bin (fs/create-dirs (fs/path root "tools"))
        env {:home (str (fs/create-dirs (fs/path root "home")))
             :tmp (str (fs/create-dirs (fs/path root "tmp")))
             :path (str bin)}
        temporary (io/file (repls/install-prefix :temporary env) "bin/glj")
        persistent (io/file (repls/install-prefix :persistent env) "bin/glj")
        on-path (io/file (str bin) "glj")
        make-executable! (fn [file]
                           (io/make-parents file)
                           (spit file "#!/bin/sh\nexit 0\n")
                           (.setExecutable file true)
                           file)]
    (try
      (is (nil? (repls/discover :glojure env)))
      (doseq [file [temporary persistent on-path]]
        (make-executable! file)
        (is (= (str file) (repls/discover :glojure env))))
      (.setExecutable on-path false)
      (is (= (str persistent) (repls/discover :glojure env)))
      (finally (fs/delete-tree root)))))

(deftest platform-and-native-command-contracts
  (is (= 12 (count (repls/available-options "Linux")))))

(deftest windows-keeps-the-original-menu
  (is (= [:clojure :rebel :babashka :clojurescript :jolt :let-go]
         (mapv :id (repls/available-options "Windows 11"))))
  (is (= ["gloat" "--repl"] (repls/command :gloat ".")))
  (is (= "Python" (:description (repls/option :hy)))))
