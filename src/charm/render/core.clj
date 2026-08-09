(ns ^:no-doc charm.render.core
  "Renderer override for jus.

   charm.clj's stock renderer sends each line through JLine Display as an
   AttributedString. JLine rewrites light Unicode box drawing: through a live
   terminal it can emit DEC alternate-character-set q/x, and without ACS it
   falls back to ASCII -/|. Both break jus's thin-line design.

   This namespace keeps charm.program's public renderer API but writes the
   already ANSI-styled view string directly, preserving Unicode box-drawing
   glyphs."
  (:require [charm.render.screen :as scr]
            [charm.terminal :as term]
            [clojure.string :as str])
  (:import [org.jline.terminal Terminal]))

(defn create-renderer
  "Create a renderer compatible with charm.program."
  [^Terminal terminal & {:keys [fps alt-screen hide-cursor]
                         :or {fps 60 alt-screen false hide-cursor true}}]
  (let [{:keys [width height]} (term/get-size terminal)]
    (atom {:terminal terminal
           :fps fps
           :alt-screen alt-screen
           :in-alt-screen false
           :hide-cursor hide-cursor
           :width width
           :height height
           :rendered-line-count 0
           :running false})))

(defn- write-terminal!
  [renderer ^String s]
  (let [^Terminal terminal (:terminal @renderer)
        writer (.writer terminal)]
    (.print writer s)
    (.flush writer)))

(defn show-cursor! [renderer]
  (term/show-cursor (:terminal @renderer)))

(defn hide-cursor! [renderer]
  (term/hide-cursor (:terminal @renderer)))

(defn move-cursor! [renderer col row]
  (term/move-cursor (:terminal @renderer) col row))

(defn enter-alt-screen! [renderer]
  (when-not (:in-alt-screen @renderer)
    (let [terminal (:terminal @renderer)]
      (term/enter-alt-screen terminal)
      (swap! renderer assoc :in-alt-screen true)
      (term/clear-screen terminal)
      (term/cursor-home terminal))))

(defn exit-alt-screen! [renderer]
  (when (:in-alt-screen @renderer)
    (term/exit-alt-screen (:terminal @renderer))
    (swap! renderer assoc :in-alt-screen false)))

(defn clear-screen! [renderer]
  (let [terminal (:terminal @renderer)]
    (term/clear-screen terminal)
    (term/cursor-home terminal)))

(defn enable-mouse! [renderer mode]
  (case mode
    :normal (do
              (write-terminal! renderer scr/enable-mouse-normal)
              (write-terminal! renderer scr/enable-mouse-sgr))
    :cell (do
            (write-terminal! renderer scr/enable-mouse-cell-motion)
            (write-terminal! renderer scr/enable-mouse-sgr))
    :all (do
           (write-terminal! renderer scr/enable-mouse-all-motion)
           (write-terminal! renderer scr/enable-mouse-sgr))
    nil))

(defn disable-mouse! [renderer]
  (write-terminal! renderer scr/disable-mouse-sgr)
  (write-terminal! renderer scr/disable-mouse-normal)
  (write-terminal! renderer scr/disable-mouse-cell-motion)
  (write-terminal! renderer scr/disable-mouse-all-motion))

(defn enable-focus-reporting! [renderer]
  (write-terminal! renderer scr/enable-focus-reporting))

(defn disable-focus-reporting! [renderer]
  (write-terminal! renderer scr/disable-focus-reporting))

(defn enable-bracketed-paste! [renderer]
  (write-terminal! renderer scr/enable-bracketed-paste))

(defn disable-bracketed-paste! [renderer]
  (write-terminal! renderer scr/disable-bracketed-paste))

(defn set-window-title! [renderer title]
  (write-terminal! renderer (scr/set-window-title title)))

(defn copy-to-clipboard! [renderer text]
  (write-terminal! renderer (scr/copy-to-clipboard text)))

(defn- visible-lines
  [content width height]
  (let [content (if (empty? content) " " content)
        lines   (mapv #(scr/truncate-line % width) (scr/content->lines content))
        lines   (if (and (pos? height) (> (count lines) height))
                  (subvec lines (- (count lines) height))
                  lines)]
    (str/join "\n" lines)))

(defn render!
  "Render by repainting the view string directly.

   This intentionally avoids org.jline.utils.AttributedString#print, because
   that path translates light Unicode box drawing into ACS q/x or ASCII -/|."
  [renderer content]
  (let [{:keys [width height alt-screen rendered-line-count]} @renderer
        terminal (:terminal @renderer)
        visible  (visible-lines content width height)
        lines    (str/split visible #"\n" -1)
        last-idx (dec (count lines))]
    (if alt-screen
      (term/cursor-home terminal)
      (do
        (when (> rendered-line-count 1)
          (term/cursor-up terminal (dec rendered-line-count)))
        (write-terminal! renderer "\r")))
    (doseq [[idx line] (map-indexed vector lines)]
      (write-terminal! renderer line)
      (term/clear-to-end-of-line terminal)
      (when (< idx last-idx)
        (write-terminal! renderer "\n")))
    (term/clear-to-end-of-screen terminal)
    (swap! renderer assoc :rendered-line-count (count lines))))

(defn repaint! [renderer]
  (clear-screen! renderer))

(defn update-size! [renderer width height]
  (swap! renderer assoc :width width :height height)
  (when (:alt-screen @renderer)
    (clear-screen! renderer)))

(defn get-size [renderer]
  [(:width @renderer) (:height @renderer)])

(defn start! [renderer]
  (let [{:keys [alt-screen hide-cursor]} @renderer]
    (when hide-cursor
      (hide-cursor! renderer))
    (when alt-screen
      (enter-alt-screen! renderer))
    (swap! renderer assoc :running true)))

(defn stop! [renderer]
  (let [{:keys [in-alt-screen hide-cursor]} @renderer]
    (when in-alt-screen
      (exit-alt-screen! renderer))
    (when hide-cursor
      (show-cursor! renderer))
    (disable-mouse! renderer)
    (disable-focus-reporting! renderer)
    (swap! renderer assoc :running false)))
