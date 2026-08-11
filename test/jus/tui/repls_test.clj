(ns jus.tui.repls-test
  (:require [clojure.test :refer [deftest is testing]]
            [jus.tui.style :as style]
            [jus.tui.repls :as repls]))

(deftest options-have-the-planned-order-and-default
  (is (= [:clojure :rebel :babashka :clojurescript :jolt :let-go]
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
