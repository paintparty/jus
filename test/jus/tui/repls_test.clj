(ns jus.tui.repls-test
  (:require [clojure.test :refer [deftest is testing]]
            [jus.tui.repls :as repls]))

(deftest options-have-the-planned-order-and-default
  (is (= [:clojure :rebel :babashka :clojurescript]
         (mapv :id repls/options)))
  (is (= :clojure (:id (first repls/options))))
  (is (= ["clojure"] (:requires (repls/option :clojure))))
  (is (= ["clojure"] (:requires (repls/option :rebel))))
  (is (= ["bb"] (:requires (repls/option :babashka))))
  (is (= ["clojure" "node"] (:requires (repls/option :clojurescript)))))

(deftest commands-use-pinned-ephemeral-dependencies
  (testing "Clojure and Babashka use their native REPL commands"
    (is (= ["clojure"] (repls/command :clojure "/tmp/project")))
    (is (= ["bb" "repl"] (repls/command :babashka "/tmp/project"))))
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
  (is (= ["clojure" "-P" "-Sdeps"
          (pr-str {:deps {'com.bhauman/rebel-readline
                          {:mvn/version repls/rebel-readline-version}}})]
         (repls/preparation-command :rebel "/tmp/project")))
  (is (= ["clojure" "-P" "-Sdeps"
          (pr-str {:deps {'org.clojure/clojurescript
                          {:mvn/version repls/clojurescript-version}}})]
         (repls/preparation-command :clojurescript "/tmp/project"))))

(deftest executable-guidance-includes-node
  (is (= "Required executable not found: node\nInstall Node.js from https://nodejs.org/en/download and try again.\n"
         (repls/missing-executable-message "node"))))
