(ns jus.tui.news.archive
  "The public archive's HTML and HTTP boundary. Only top-level posts are news."
  (:require [charm.ansi.sanitize :as sanitize]
            [clojure.string :as str])
  (:import (java.net URI HttpURLConnection)
           (java.time Instant)
           (org.jsoup Jsoup)))

(def base-url "https://clojurians-log.clojureverse.org")
(def max-page-bytes (* 4 1024 1024))

(defn clean-text
  "Remove terminal escape sequences and control characters from remote text."
  [s]
  (sanitize/strip (str s)))

(defn safe-url [s]
  (try
    (let [uri (URI. s)]
      (when (and (contains? #{"https" "http"} (.getScheme uri))
                 (.getHost uri)
                 (not (re-find #"[\s\x00-\x1f\x7f-\x9f]" s)))
        s))
    (catch Exception _ nil)))

(defn valid-channel? [channel]
  (and (string? channel) (boolean (re-matches #"[a-z0-9_][a-z0-9_-]{0,79}" channel))))

(defn day-url [channel date]
  (when-not (valid-channel? channel)
    (throw (ex-info "Invalid news channel name" {:channel channel})))
  (str base-url "/" channel "/" date))

(defn- select-one [element suffix]
  (.selectFirst element (str "[class*=__" suffix "]")))

(defn- body-text [body]
  ;; Work on a clone: selecting posts must not alter the source DOM. Jsoup repairs
  ;; the archive's HTML (including pre blocks nested inside paragraphs).
  (let [body (.clone body)]
    (doseq [el (.select body "script,style,iframe,[class*=__message_head],[class*=__reactions_row]")]
      (.remove el))
    (doseq [el (.select body "a[href]")]
      (when-let [url (safe-url (.absUrl el "href"))]
        (.text el (if (= (.text el) url) url (str (.text el) " " url)))))
    (doseq [el (.select body "br")] (.after el "\n"))
    (doseq [el (.select body "pre,blockquote,li")] (.before el "\n") (.after el "\n"))
    (str/trim (clean-text (.wholeText body)))))

(defn- parse-page
  "Parse a dated archive page; fail visibly on unexpected markup, not silently empty."
  [channel date html]
  (let [doc (Jsoup/parse html (day-url channel date))
        content (select-one doc "content_scroll")]
    (when-not (and content
                   (str/starts-with? (.title doc) (str date " " channel " |")))
      (throw (ex-info "The news archive returned an unexpected page." {})))
    (mapv
     (fn [row]
       (let [head (select-one row "message_head")
             body (select-one row "message_body")
             timestamp (some-> head (select-one "timestamp_span") .text str/trim)
             link (some-> head (.selectFirst "a[href]") (.absUrl "href") safe-url)]
         (when-not (and body timestamp link)
           (throw (ex-info "The news archive's message format has changed." {})))
         {:id link :channel channel :timestamp (Instant/parse timestamp)
          :author (some-> head (select-one "username_span") .text clean-text not-empty)
          :text (body-text (select-one row "message_content")) :url link}))
     (remove (fn [row]
               (some (fn [parent]
                       (some #(str/ends-with? % "__reply_thread") (.classNames parent)))
                     (.parents row)))
             (.select content "[class*=__message_row]")))))

(defn parse-day [channel date html]
  (try
    (parse-page channel date html)
    (catch Exception error
      (throw (ex-info (or (.getMessage error) "The news archive's format has changed.")
                      {:type :archive-format} error)))))

(defn fetch-day
  "Fetch one day with time/size bounds. The connection atom allows shutdown to abort I/O."
  ([channel date] (fetch-day channel date (atom nil)))
  ([channel date connection]
   (let [^HttpURLConnection request (.openConnection (.toURL (URI. (day-url channel date))))]
     (reset! connection request)
     (try
       (.setConnectTimeout request 10000)
       (.setReadTimeout request 10000)
       (.setRequestProperty request "User-Agent" "jus-news/0.4 (+https://github.com/paintparty/jus)")
       (.setRequestProperty request "Accept" "text/html")
       (when (.isInterrupted (Thread/currentThread))
         (throw (InterruptedException. "News request cancelled")))
       (let [status (.getResponseCode request)]
         (cond
           (= 404 status) []
           (= 200 status)
           (with-open [stream (.getInputStream request)]
             (let [bytes (.readNBytes stream (inc max-page-bytes))]
               (when (> (alength bytes) max-page-bytes)
                 (throw (ex-info "The news archive page is too large." {})))
               (parse-day channel date (String. bytes "UTF-8"))))
           :else
           (throw (ex-info (str "News archive HTTP " status)
                           {:status status :retry-after (.getHeaderField request "Retry-After")}))))
       (finally
         (reset! connection nil)
         (.disconnect request))))))
