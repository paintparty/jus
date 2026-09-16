(ns jus.tui.content
  (:require [clojure.string :as str]
            [jus.tui.style :as style]))

(def content-padding-block
  "Blank rows added inside the top and bottom of a content container."
  1)

(defn render
  "Render read-only text in a width-aware bordered container.
   The container grows vertically with its wrapped contents."
  [text term-width]
  (let [width       (max 4 term-width)
        inner-width (- width 4)
        padding-start 3
        padding-end   1
        border      (fn [left right]
                      (str " " (style/secondary
                                (str left
                                     (apply str (repeat inner-width "─"))
                                     right))))
        lines       (style/helper-lines text (max 1 (- inner-width padding-start padding-end)))
        row         (fn [line]
                      (let [visible-width (count (str/replace line #"\033\[[0-9;]*m|\033]8;;[^\033]*\033\\" ""))]
                        (str " " (style/secondary "│")
                             (apply str (repeat padding-start " ")) line
                             (apply str (repeat (max 0 (- inner-width padding-start padding-end visible-width)) " "))
                             (apply str (repeat padding-end " ")) (style/secondary "│"))))
        block-padding (repeat (max 0 content-padding-block) (row ""))]
    (str/join "\n" (concat [(border "╭" "╮")]
                           block-padding
                           (map row lines)
                           block-padding
                           [(border "╰" "╯")]))))
