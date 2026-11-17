(ns jus.tui.news.ui
  "Channel navigation and scrollable readers, with no archive I/O."
  (:require [charm.message :as msg]
            [charm.program :as program]
            [clojure.string :as str]
            [jus.tui.content :as content]
            [jus.tui.footer :as footer]
            [jus.tui.style :as style])
  (:import (java.time ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def steps #{:news-channels :news-reader})

(def ^:private post-date-format
  (DateTimeFormatter/ofPattern "MMM d, yyyy 'at' h:mm a z" Locale/ENGLISH))

(defn- post-date [timestamp]
  (.format (.withZone post-date-format (ZoneId/systemDefault)) timestamp))

(defn- archive-link-lines [url width]
  (let [label (str "View in archive" (if @style/windows-10? ">" "↗"))]
    ;; Wrap plain text first so each displayed line, including its spaces,
    ;; becomes one continuous underlined terminal link.
    (mapv #(style/hyperlink % url) (style/helper-lines label width))))

(declare status-text)

(defn- height [state]
  (max 1 (- (:term-height state) 12
            (count (style/helper-lines (status-text state) (max 1 (- (:term-width state) 4)))))))

(defn reader-lines [state]
  (let [channel (:news-channel state)
        width (max 1 (- (:term-width state) 8))]
    (vec
     (mapcat
      (fn [{:keys [id timestamp author text url]}]
        (map-indexed
         (fn [index line] {:key [id index] :text line})
         (concat (style/helper-lines
                  (str (when (contains? (get-in state [:news :new-ids]) id) "NEW · ")
                       (post-date timestamp) " · " (or author "Unknown author")) width)
                 (style/helper-lines text width)
                 (archive-link-lines url width)
                 [""])))
      (get-in state [:news :messages channel])))))

(defn- status-text [state]
  (let [news (:news state)
        error (if (= :news-reader (:step state))
                (get-in news [:errors (:news-channel state)])
                (seq (:errors news)))]
    (cond
      (:worker-error news) (:worker-error news)
      error "Archive unavailable; keeping cached posts. Will retry automatically."
      (:loading? news) "Loading recent posts…"
      (not news) "Loading recent posts…"
      :else "Last 7 days · refreshes about every 5 minutes")))

(defn page [state]
  (let [rows (reader-lines state)
        lines (if (seq rows) (mapv :text rows)
                  [(cond
                     (get-in state [:news :errors (:news-channel state)])
                     "Posts are unavailable. Will retry automatically."
                     (not (contains? (get-in state [:news :ready]) (:news-channel state)))
                     "Loading recent posts…"
                     :else "No posts in the last 7 days.")])]
    (content/page-lines lines (:term-width state) (height state) (or (:news-offset state) 0))))

(defn preserve-position [old-state new-state]
  (if (and (= :news-reader (:step old-state)) (pos? (or (:news-offset old-state) 0)))
    (let [old-rows (reader-lines old-state)
          anchor (:key (get old-rows (:offset (page old-state))))
          new-rows (reader-lines new-state)
          index (first (keep-indexed #(when (= anchor (:key %2)) %1) new-rows))]
      (assoc new-state :news-offset (or index (:offset (page new-state)))))
    new-state))

(defn update-screen [state message]
  (let [reader? (= :news-reader (:step state))
        channels (get-in state [:news :channels])
        current (when reader? (page state))
        offset (:offset current)
        capacity (height state)
        move (fn [offset] [(assoc state :news-offset
                                  (max 0 (min (:max-offset current) offset))) nil])]
    (cond
      (msg/key-match? message "ctrl+c")
      [(assoc state :done? true :exit-code 130) program/quit-cmd]
      (msg/key-match? message :escape)
      [(if reader?
         (assoc state :step :news-channels)
         (assoc state :step :main-menu :menu-idx (or (:news-menu-idx state) 0))) nil]
      (and (not reader?) (seq channels) (msg/key-match? message "enter"))
      [(assoc state :step :news-reader :news-channel (nth channels (or (:news-index state) 0))
              :news-offset 0) nil]
      (msg/key-match? message :up)
      (if reader? (move (dec offset))
          [(update state :news-index #(max 0 (dec (or % 0)))) nil])
      (msg/key-match? message :down)
      (if reader? (move (inc offset))
          [(update state :news-index #(max 0 (min (dec (count channels)) (inc (or % 0))))) nil])
      (and reader? (or (msg/key-match? message " ") (msg/key-match? message :pgdown)))
      (move (+ offset capacity))
      (and reader? (msg/key-match? message :pgup)) (move (- offset capacity))
      (and reader? (msg/key-match? message :home)) (move 0)
      (and reader? (msg/key-match? message :end)) (move (:max-offset current))
      :else [state nil])))

(defn render [state render-list header]
  (let [reader? (= :news-reader (:step state))
        channels (get-in state [:news :channels])
        page (when reader? (page state))
        labels (mapv #(str "#" % " (" (count (get-in state [:news :messages %])) ")") channels)]
    (str header (style/italic "Newsfeed")
         (when reader? (str " / " (style/italic (str "#" (:news-channel state)))))
         "\n\n\n  " (if reader? (str "#" (:news-channel state)) "Newsfeed (Clojurians Slack)") "\n"
         (if reader? (:rendered page)
             (render-list labels (or (:news-index state) 0) (:term-width state) (height state)))
         "\n" (str/join "\n" (map #(str "  " %) (style/helper-lines (status-text state)
                                                                    (max 1 (- (:term-width state) 4)))))
         (footer/render (if reader?
                          "↑↓: scroll, Space/PgDn/PgUp: page, Esc: channels, Ctrl-C: quit"
                          "Enter: open, ↑↓: channels, Esc: back, Ctrl-C: quit"))
         "\n")))
