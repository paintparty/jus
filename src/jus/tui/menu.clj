(ns jus.tui.menu)

(defn visible-window
  "Return the focus-following slice of items that fits within capacity,
   together with its original start index and hidden item counts."
  [items selected-idx capacity]
  (let [items        (vec items)
        item-count   (count items)
        capacity     (max 1 capacity)
        selected-idx (max 0 (min (or selected-idx 0)
                                 (max 0 (dec item-count))))
        max-start    (max 0 (- item-count capacity))
        start        (min max-start
                          (max 0 (- selected-idx (dec capacity))))
        end          (min item-count (+ start capacity))]
    {:items        (subvec items start end)
     :start        start
     :hidden-above start
     :hidden-below (- item-count end)}))

(defn render-window
  "Render a visible menu window and automatically add its overflow rows.
   item-capacity preserves the desired number of visible items; row-capacity
   is the hard terminal-line budget including overflow rows. Renderers may
   return one line, multiple lines, or nil."
  [items selected-idx item-capacity row-capacity render-item render-overflow]
  (let [as-lines (fn [rendered]
                   (cond
                     (nil? rendered) []
                     (string? rendered) [rendered]
                     :else (vec rendered)))
        row-capacity (max 1 row-capacity)]
    (loop [item-capacity (max 1 item-capacity)]
      (let [{visible-items :items
             :keys [start hidden-above hidden-below]
             :as window} (visible-window items selected-idx item-capacity)
            top-lines (when (pos? hidden-above)
                        (as-lines (render-overflow "↑" hidden-above)))
            item-lines (mapv (fn [index item]
                               (as-lines (render-item index item)))
                             (range start (+ start (count visible-items)))
                             visible-items)
            bottom-lines (when (pos? hidden-below)
                           (as-lines (render-overflow "↓" hidden-below)))
            rendered-lines (vec (concat top-lines
                                        (mapcat identity item-lines)
                                        bottom-lines))]
        (cond
          (<= (count rendered-lines) row-capacity)
          (assoc window :lines rendered-lines)

          (> item-capacity 1)
          (recur (dec item-capacity))

          :else
          (let [focused-lines (first item-lines)
                overflow-budget (max 0 (- row-capacity
                                          (if (seq focused-lines) 1 0)))
                kept-top (take overflow-budget top-lines)
                kept-bottom (take (- overflow-budget (count kept-top))
                                  bottom-lines)
                item-budget (- row-capacity
                               (count kept-top)
                               (count kept-bottom))]
            (assoc window :lines
                   (vec (concat kept-top
                                (take item-budget focused-lines)
                                kept-bottom)))))))))
