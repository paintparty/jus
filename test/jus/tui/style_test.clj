(ns jus.tui.style-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jus.tui.style :as style]))

(defn- restore-os-name
  [test-fn]
  (let [os-name (System/getProperty "os.name")]
    (try
      (test-fn)
      (finally
        (if os-name
          (System/setProperty "os.name" os-name)
          (System/clearProperty "os.name"))
        (require 'jus.tui.style :reload)))))

(use-fixtures :each restore-os-name)

(defn- logo-for
  [os-name]
  (System/setProperty "os.name" os-name)
  (require 'jus.tui.style :reload)
  style/logo)

(deftest windows-uses-a-text-presentation-logo
  (is (= "◒" (logo-for "Windows 11"))))

(deftest non-windows-uses-the-yin-yang-logo
  (is (= "◒" #_"☯" (logo-for "Linux"))))

(deftest helper-lines-preserve-authored-line-breaks
  (with-redefs [style/hyperlinks-enabled? (constantly false)]
    (is (= ["first line" "second line"]
           (style/helper-lines "first line\nsecond line" 80)))))

(deftest helper-lines-render-bold-italic-markdown
  (let [rendered (first (style/helper-lines "A ***temporary*** install" 80))]
    (is (= "A temporary install" (str/replace rendered #"\u001b\[[0-9;]*m" "")))
    (is (str/includes? rendered "\u001b[1;3m"))))

(deftest helper-lines-render-bold-markdown
  (let [rendered (first (style/helper-lines "A **temporary** install" 80))]
    (is (= "A temporary install" (str/replace rendered #"\u001b\[[0-9;]*m" "")))
    (is (str/includes? rendered "\u001b[1m"))
    (is (not (str/includes? rendered "\u001b[1;3m")))))

(deftest no-color-does-not-disable-terminal-hyperlinks
  (is (= "\033]8;;https://babashka.org/\033\\\033[4mBabashka\033[24m\033]8;;\033\\"
         (style/hyperlink-for-environment
          "Babashka"
          "https://babashka.org/"
          {"NO_COLOR" "1" "TERM" "xterm-ghostty"})))
  (is (= "Babashka"
         (style/hyperlink-for-environment
          "Babashka"
          "https://babashka.org/"
          {"TERM" "dumb"}))))
