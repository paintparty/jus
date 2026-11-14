(ns jus.tui.style-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jus.tui.logo :as logo]
            [jus.tui.style :as style]))

(deftest logo-follows-the-operating-system
  (doseq [[os-name expected] [["Mac OS X" "☯"]
                            ["macOS" "☯"]
                            ["Linux" "◒"]
                            ["Windows 11" "◒"]
                            [nil "◒"]]]
    (is (= expected (logo/for-os os-name))))
  (is (= "*" (logo/for-os "Windows 10" true)))
  (is (= logo/glyph style/logo)))

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
