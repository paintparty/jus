(ns jus.tui.news-test
  (:require [charm.message :as msg]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jus.tui.config :as config]
            [jus.tui.core :as core]
            [jus.tui.news :as news]
            [jus.tui.news.archive :as archive]
            [jus.tui.news.runtime :as runtime]
            [jus.tui.news.ui :as ui])
  (:import (java.time Instant LocalDate)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def now (Instant/parse "2026-11-16T12:00:00Z"))
(def day (LocalDate/parse "2026-11-15"))
(def fixture (slurp (io/resource "jus/tui/fixtures/news-day.html")))

(defn post [id timestamp]
  {:id id :channel "announcements" :timestamp (Instant/parse timestamp)
   :author "Alex" :text (str/join "\n" (repeat 10 (str "Post " id)))
   :url "https://example.org/post"})

(defn loaded-state [posts]
  (reduce #(news/apply-page %1 "announcements" %2 (if (= %2 day) posts []) now)
          (news/initial-state ["announcements"]) (news/dates now)))

(deftest archive-html-boundary
  (let [posts (archive/parse-day "announcements" day fixture)
        first-post (first posts)]
    (is (= 2 (count posts)))
    (is (= "Alex & Sam" (:author first-post)))
    (is (= (Instant/parse "2026-11-15T19:13:20.000001Z") (:timestamp first-post)))
    (is (= (str archive/base-url "/announcements/2026-11-15/1794770000.000001") (:id first-post)))
    (is (str/includes? (:text first-post) "Release notes https://example.org/release"))
    (is (str/includes? (:text first-post) "(println \"hello\")"))
    (is (str/includes? (:text first-post) "After code."))
    (is (not (str/includes? (:text first-post) "🎉 3")))
    (is (not (str/includes? (:text (second posts)) "unwanted script")))
    (is (not (str/includes? (:text (second posts)) "javascript:"))))
  (is (= [] (archive/parse-day "announcements" day
                               (str "<title>2026-11-15 announcements | Clojure Slack Archive</title>"
                                    "<div class='clojurians-log_components_common__content_scroll'></div>"))))
  (is (thrown? Exception (archive/parse-day "announcements" day "max connections reached: 10")))
  (is (thrown? Exception (archive/parse-day "announcements" day
                                            (str/replace fixture "__timestamp_span" "__changed")))))

(deftest remote-text-and-channel-validation
  (is (= "hello" (archive/clean-text "\u001b]52;c;bad\u0007he\u001b[2Jllo\r")))
  (is (nil? (archive/safe-url "https://example.org/\u001b]52;c;bad")))
  (is (nil? (archive/safe-url "javascript:alert(1)")))
  (is (= {:channels ["announcements" "events"]}
         (config/news-settings {:tui {:news {:channels ["#announcements" "events" "announcements"]}}})))
  (doseq [settings [{:channels ["../../etc"]} {:channels "announcements"} :bad]]
    (let [cfg {:tui {:news settings :projects {:groups ['example]}}}]
      (is (seq (:error (config/news-settings cfg))))
      (is (= ['example] (config/groups cfg)))))
  (let [cfg {:tui {:news {:channels ["events"]}}}]
    (is (= {:channels ["events"]} (get-in (read-string (config/format-config cfg)) [:tui :news])))))

(deftest baseline-new-posts-expiration-and-recovery
  (let [old (post "old" "2026-11-15T01:00:00Z")
        fresh (post "fresh" "2026-11-15T02:00:00Z")
        initial (loaded-state [old old])
        refreshed (news/apply-page initial "announcements" day [old fresh fresh] now)]
    (is (= #{"announcements"} (:ready initial)))
    (is (empty? (:new-ids initial)))
    (is (= ["fresh" "old"] (map :id (get-in refreshed [:messages "announcements"]))))
    (is (= #{"fresh"} (:new-ids refreshed)))
    (is (= (select-keys refreshed [:messages :new-ids])
           (select-keys (news/apply-page refreshed "announcements" day [old fresh] now)
                        [:messages :new-ids])))
    (is (= 2 (count (news/requests refreshed now))))
    (is (empty? (:new-ids (news/expire refreshed (Instant/parse "2026-11-22T00:00:00Z")))))
    (is (empty? (get-in (news/expire refreshed (Instant/parse "2026-11-22T00:00:00Z"))
                        [:messages "announcements"])))
    (is (nil? (get-in (news/apply-page (assoc-in initial [:errors "announcements"] "offline")
                                       "announcements" day [old] now) [:errors "announcements"]))))
  (testing "midnight advances the UTC window and fetches the new day"
    (is (= [["announcements" (LocalDate/parse "2026-11-17")]
            ["announcements" (LocalDate/parse "2026-11-16")]]
           (news/requests (loaded-state []) (Instant/parse "2026-11-17T00:00:00Z")))))
  (is (>= (news/retry-delay 1 "1800") 1800000)))

(defn await-idle [service]
  (loop [remaining 15]
    (when (zero? remaining) (throw (ex-info "News worker never became idle" {})))
    (let [value (.poll ^LinkedBlockingQueue (:updates service) 2 TimeUnit/SECONDS)]
      (if (and (map? value) (false? (:loading? value))) value
          (recur (dec remaining))))))

(deftest worker-is-sequential-backs-off-and-stops
  (let [calls (atom [])
        service (runtime/start! ["announcements"] nil
                                {:now (constantly now) :request-gap-ms 0
                                 :fetch (fn [channel date _] (swap! calls conj [channel date]) [])})]
    (try
      (is (= #{"announcements"} (:ready (await-idle service))))
      (is (= 7 (count @calls)))
      (finally (runtime/stop! service)))
    (is (.isDaemon ^Thread (:worker service)))
    (is (not (.isAlive ^Thread (:worker service))))
    (is (nil? ((:fn (runtime/next-update-command service))))))
  (let [calls (atom 0)
        service (runtime/start! ["announcements" "events"] nil
                                {:now (constantly now) :request-gap-ms 0
                                 :fetch (fn [& _] (swap! calls inc)
                                          (throw (ex-info "Throttled" {:status 429 :retry-after "1800"})))})]
    (try
      (is (seq (:errors (await-idle service))))
      (is (= 1 @calls) "stop the batch after an error instead of hammering other days/channels")
      (finally (runtime/stop! service)))))

(deftest worker-cancels-pending-requests-and-releases-update-waiters
  (let [entered (promise)
        interrupted (promise)
        gate (promise)
        service (runtime/start! ["announcements"] nil
                                {:fetch (fn [& _]
                                          (deliver entered true)
                                          (try @gate
                                               (catch InterruptedException error
                                                 (deliver interrupted true)
                                                 (throw error))))})]
    (try
      (is (= true (deref entered 2000 false)))
      (runtime/stop! service)
      (is (= true (deref interrupted 2000 false)))
      (is (nil? ((:fn (runtime/next-update-command service)))))
      (finally (runtime/stop! service)))))

(deftest one-malformed-channel-does-not-block-other-subscriptions
  (let [calls (atom [])
        service (runtime/start! ["announcements" "events"] nil
                                {:now (constantly now) :request-gap-ms 0
                                 :fetch (fn [channel date _]
                                          (swap! calls conj [channel date])
                                          (if (= channel "announcements")
                                            (throw (ex-info "Changed HTML" {:type :archive-format}))
                                            []))})]
    (try
      (let [snapshot (await-idle service)]
        (is (= #{"events"} (:ready snapshot)))
        (is (= #{"announcements"} (set (keys (:errors snapshot))))))
      (is (= 1 (count (filter #(= "announcements" (first %)) @calls))))
      (is (= 7 (count (filter #(= "events" (first %)) @calls))))
      (finally (runtime/stop! service)))))

(deftest dashboard-config-reloads-do-not-change-active-news-subscriptions
  (let [cfg {:tui {:news {:channels ["events"]}}}
        disabled (assoc (core/main-menu-state {}) :global-config cfg)
        enabled (assoc (core/main-menu-state cfg) :global-config {})]
    (is (not (some #{:news} (core/main-menu-choices disabled))))
    (is (some #{:news} (core/main-menu-choices enabled)))
    (let [picker (assoc disabled :step :news-channels)]
      (is (= picker (first (core/update-fn picker (msg/key-press "enter"))))))))

(deftest channel-navigation-empty-reader-and-background-results
  (with-redefs [core/flash-on-enter? false]
    (let [cfg {:tui {:news {:channels ["announcements" "events"]}}}
          base (core/main-menu-state cfg)
          idx (.indexOf (core/main-menu-choices base) :news)
          [picker _] (core/update-fn (assoc base :menu-idx idx) (msg/key-press "enter"))
          [focused _] (core/update-fn picker (msg/key-press :down))
          [reader _] (core/update-fn focused (msg/key-press "enter"))
          snapshot (assoc (:news base) :ready #{"announcements" "events"} :loading? false)
          [loaded _] (core/update-fn (assoc reader :news-service {:id :test})
                                     {:type :news-updated :service-id :test :news snapshot})
          [back _] (core/update-fn loaded (msg/key-press :escape))
          [main _] (core/update-fn back (msg/key-press :escape))]
      (is (= :news-channels (:step picker)))
      (is (str/includes? (core/strip-ansi (core/view picker)) "#events (0)"))
      (is (= "events" (:news-channel reader)))
      (is (str/includes? (core/strip-ansi (core/view loaded)) "No posts in the last 7 days."))
      (is (= 1 (:news-index back)))
      (is (= idx (:menu-idx main)))
      (is (= snapshot (:news main)))
      (is (= reader (first (core/update-fn reader {:type :news-updated :service-id :stale :news snapshot}))))
      (is (= 130 (:exit-code (first (core/update-fn loaded (msg/key-press "c" :ctrl true))))))))
  (is (not (some #{:news} (core/main-menu-choices (core/main-menu-state {}))))))

(deftest reader-preserves-position-and-supports-paging
  (let [old (post "old" "2026-11-15T01:00:00Z")
        fresh (post "fresh" "2026-11-15T02:00:00Z")
        state (assoc (core/main-menu-state {}) :step :news-reader :news-channel "announcements"
                     :term-height 18 :news (loaded-state [old]) :news-offset 3)
        next-state (ui/preserve-position state
                                         (assoc state :news (news/apply-page (:news state) "announcements"
                                                                             day [old fresh] now)))
        [end _] (ui/update-screen next-state (msg/key-press :end))
        [home _] (ui/update-screen end (msg/key-press :home))]
    (is (= (:key (get (ui/reader-lines state) 3))
           (:key (get (ui/reader-lines next-state) (:news-offset next-state)))))
    (is (pos? (:news-offset end)))
    (is (zero? (:news-offset home)))
    (is (some #(str/includes? (:text %) "\u001b[4m") (ui/reader-lines next-state)))
    (is (= 5 (:news-offset (first (ui/update-screen home (msg/key-press :page-down))))))))
