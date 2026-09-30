(ns jus.tui.content-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jus.tui.content :as content]
            [jus.tui.core :as core]
            [jus.tui.style :as style]))

(deftest content-box-reflows-and-grows-with-its-wrapped-text
  (with-redefs [style/hyperlinks-enabled? (constantly false)]
    (let [wide (content/render "A sentence that wraps when the terminal narrows." 40)
          narrow (content/render "A sentence that wraps when the terminal narrows." 20)
          narrow-lines (str/split-lines (core/strip-ansi narrow))]
      (is (= 1 content/content-padding-block))
      (is (= 6 (count (str/split-lines (core/strip-ansi wide)))))
      (is (= 9 (count narrow-lines)))
      (is (every? #(= 19 (count %))
                  narrow-lines))
      (is (= " │                │"
             (second narrow-lines)
             (nth narrow-lines (- (count narrow-lines) 2))))
      (is (str/starts-with? (nth narrow-lines 2)
                            " │   A sentence")))))

(deftest content-box-preserves-active-links-through-wrapping
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (let [rendered (content/render "Built with [Babashka](https://babashka.org/)." 40)]
      (is (str/includes? rendered "\033]8;;https://babashka.org/\033\\\033[4mBabashka\033[24m\033]8;;\033\\"))
      (is (every? #(= 39 (count (core/strip-ansi
                                 (str/replace % #"\033]8;;[^\033]*\033\\" ""))))
                  (str/split-lines rendered))))))
