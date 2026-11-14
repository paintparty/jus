(ns jus.tui.content
  (:require [clojure.string :as str]
            [jus.tui.style :as style]))

(def content-padding-block
  "Blank rows added inside the top and bottom of a content container."
  1)

(defn- render-lines
  [lines term-width]
  (let [width       (max 4 term-width)
        inner-width (- width 4)
        padding-start 3
        padding-end   1
        border      (fn [left right]
                      (str " " (style/secondary
                                (str left
                                     (apply str (repeat inner-width "─"))
                                     right))))
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

(defn render
  "Render read-only text in a width-aware bordered container."
  [text term-width]
  (render-lines (style/helper-lines text (max 1 (- term-width 8))) term-width))

(defn page
  "Reflow text at the current width and render a bounded, scrollable page."
  [text term-width height offset]
  (let [lines (vec (style/helper-lines text (max 1 (- term-width 8))))
        capacity (max 1 height)
        max-offset (max 0 (- (count lines) capacity))
        offset (min max-offset (max 0 offset))]
    {:rendered (render-lines (subvec lines offset (min (count lines) (+ offset capacity))) term-width)
     :offset offset :max-offset max-offset :total-lines (count lines)
     :visible-lines (min capacity (count lines))}))
