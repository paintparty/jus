(ns jus.tui.content
  (:require [charm.ansi.width :as ansi-width]
            [clojure.string :as str]
            [jus.tui.style :as style]))

(def content-padding-block
  "Blank rows added inside the top and bottom of a content container."
  1)

(defn- render-lines
  ([lines term-width] (render-lines lines term-width {}))
  ([lines term-width {:keys [padding-start top-row bottom-row dividers?]
                     :or {padding-start 3}}]
  (let [width       (max 4 term-width)
        inner-width (- width 4)
        padding-end   1
        border      (fn [left right]
                      (str " " (style/secondary
                                (str left
                                     (apply str (repeat inner-width "─"))
                                     right))))
        row         (fn [line]
                      (let [available (max 0 (- inner-width padding-start padding-end))
                            line (if (> (ansi-width/string-width line) available)
                                   (ansi-width/truncate line available :tail "")
                                   line)
                            visible-width (ansi-width/string-width line)]
                        (str " " (style/secondary "│")
                             (apply str (repeat padding-start " ")) line
                             (apply str (repeat (max 0 (- inner-width padding-start padding-end visible-width)) " "))
                             (apply str (repeat padding-end " ")) (style/secondary "│"))))
        block-padding (repeat (max 0 content-padding-block) (row ""))
        divider (str " " (style/dim
                            (str "│  " (apply str (repeat (max 0 (- inner-width 4)) "─"))
                                 "  │")))]
    (str/join "\n" (concat [(border "╭" "╮")]
                           (if top-row [top-row] block-padding)
                           (when dividers? [divider])
                           (map row lines)
                           (when dividers? [divider])
                           (if bottom-row [bottom-row] block-padding)
                           [(border "╰" "╯")])))))

(defn render
  "Render read-only text in a width-aware bordered container."
  [text term-width]
  (render-lines (style/helper-lines text (max 1 (- term-width 8))) term-width))

(defn page-lines
  "Page already laid-out ANSI lines without reflowing Markdown or code."
  ([lines term-width height offset] (page-lines lines term-width height offset {}))
  ([lines term-width height offset {:keys [render-overflow] :as options}]
  (let [lines (vec lines)
        capacity (max 1 height)
        max-offset (max 0 (- (count lines) capacity))
        offset (min max-offset (max 0 offset))
        end (min (count lines) (+ offset capacity))
        hidden-below (- (count lines) end)]
    {:rendered (render-lines (subvec lines offset end) term-width
                             (cond-> options
                               (and render-overflow (pos? offset))
                               (assoc :top-row (render-overflow "↑" offset))
                               (and render-overflow (pos? hidden-below))
                               (assoc :bottom-row (render-overflow "↓" hidden-below))))
     :offset offset :max-offset max-offset :total-lines (count lines)
     :hidden-above offset :hidden-below hidden-below
     :visible-lines (- end offset)})))

(defn page
  "Reflow text at the current width and render a bounded, scrollable page."
  [text term-width height offset]
  (page-lines (style/helper-lines text (max 1 (- term-width 8)))
              term-width height offset))
