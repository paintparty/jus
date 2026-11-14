(ns jus.tui.content-test
  (:require [charm.ansi.width :as ansi-width]
            [clojure.string :as str]
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

(deftest rendered-pages-preserve-code-spacing-and-terminal-cell-width
  (let [page (content/page-lines ["first" "\033[1m    你好\033[0m" "last"] 20 1 1)
        lines (str/split-lines (:rendered page))]
    (is (= 1 (:offset page)))
    (is (= 2 (:max-offset page)))
    (is (= 3 (:total-lines page)))
    (is (str/includes? (:rendered page) "\033[1m    你好\033[0m"))
    (is (every? #(= 19 (ansi-width/string-width %)) lines))))

(deftest readme-overflow-rows-match-menu-alignment-and-preserve-page-height
  (let [state (assoc (core/main-menu-state {}) :step :project-readme
                     :readme {:path "README.md"
                              :lines (mapv #(str "row " %) (range 30))})
        render #(core/view (assoc state :readme-offset %))
        top (render 0)
        middle (render 5)
        bottom (render 999)
        middle-lines (str/split-lines (core/strip-ansi middle))]
    (is (str/includes? top (style/secondary "  ↓ 22 more lines")))
    (is (not (str/includes? top "↑ 0 more")))
    (is (str/includes? middle (style/secondary "  ↑ 5 more lines")))
    (is (str/includes? middle (style/secondary "  ↓ 17 more lines")))
    (is (some #(str/starts-with? % " │    row 5") middle-lines))
    (is (some #(str/starts-with? % " │  ↑ 5 more lines") middle-lines))
    (is (str/includes? bottom (style/secondary "  ↑ 22 more lines")))
    (is (not (str/includes? bottom "↓ 0 more")))
    (doseq [rendered [top middle bottom
                    (core/view (assoc-in state [:readme :lines] ["Short README"]))]]
      (let [divider (str " " (style/dim (str "│  " (apply str (repeat 72 "─")) "  │")))]
        (is (= 2 (count (filter #{divider} (str/split-lines rendered)))))))
    (is (= (count (str/split-lines top))
           (count middle-lines)
           (count (str/split-lines bottom))))))
