(ns jus.tui.footer)

(defn render
  "Render controls with two blank rows above them and the shared left margin.
   Call directly after the screen's final content or status row."
  [controls]
  (str "\n\n\n  " controls))
