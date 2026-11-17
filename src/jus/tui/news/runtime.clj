(ns jus.tui.news.runtime
  "One cancellable archive worker per running TUI; queued snapshots enter the Elm loop."
  (:require [charm.program :as program]
            [jus.tui.news :as news]
            [jus.tui.news.archive :as archive])
  (:import (java.net HttpURLConnection)
           (java.time Instant)
           (java.util.concurrent LinkedBlockingQueue)))

(defn- archive-wide-error? [error]
  (let [{:keys [type status]} (ex-data error)]
    (and (not= :archive-format type)
         (or (nil? status) (= 429 status) (>= status 500)))))

(defn start!
  ([channels previous] (start! channels previous {}))
  ([channels previous {:keys [fetch now interval-ms request-gap-ms]
                       :or {fetch archive/fetch-day now #(Instant/now)
                            interval-ms news/refresh-ms request-gap-ms 250}}]
   (let [stop (promise)
         connection (atom nil)
         state (merge (news/initial-state channels) previous {:channels channels})
         latest (atom state)
         updates (LinkedBlockingQueue. 1)
         publish (fn [state]
                   ;; Coalesce progress when input/rendering temporarily outpaces updates.
                   (reset! latest state)
                   (when-not (realized? stop)
                     (.poll updates)
                     (.offer updates state)))
         service {:id (random-uuid) :stop stop :connection connection :updates updates}
         worker
         (doto (Thread.
                ^Runnable
                (fn []
                  (try
                    (loop [state state failures 0]
                      (when-not (realized? stop)
                        (let [state (assoc (news/expire state (now)) :loading? true)
                              _ (publish state)
                              failed-channels (atom #{})
                              result
                              (reduce
                               (fn [state [channel date]]
                                 (cond
                                   (realized? stop) (reduced state)
                                   (@failed-channels channel) state
                                   :else
                                   (let [result (try
                                                  {:messages (fetch channel date connection)}
                                                  (catch Exception error {:error error}))]
                                     (if-let [error (:error result)]
                                       (let [state (assoc-in state [:errors channel]
                                                             (archive/clean-text (.getMessage error)))]
                                         (swap! failed-channels conj channel)
                                         (if (archive-wide-error? error)
                                           (reduced (assoc state :errors
                                                           (zipmap channels (repeat "The archive is unavailable."))
                                                           :retry-after (:retry-after (ex-data error))))
                                           state))
                                       (let [state (news/apply-page state channel date (:messages result) (now))]
                                         (publish state)
                                         (deref stop request-gap-ms nil)
                                         state)))))
                               (dissoc state :retry-after) (news/requests state (now)))
                              failures (if (seq (:errors result)) (inc failures) 0)
                              delay (if (pos? failures)
                                      (max interval-ms (news/retry-delay failures (:retry-after result)))
                                      interval-ms)
                              result (assoc result :loading? false)]
                          (publish result)
                          (when (= ::timeout (deref stop delay ::timeout))
                            (recur result failures)))))
                    (catch InterruptedException _ nil)
                    (catch Exception _
                      (publish (assoc @latest :loading? false
                                      :worker-error "News refresh stopped. Restart jus to retry.")))))
                "jus-news")
           (.setDaemon true)
           (.start))]
     (assoc service :worker worker))))

(defn stop! [service]
  (when service
    (deliver (:stop service) true)
    (.interrupt ^Thread (:worker service))
    (when-let [^HttpURLConnection connection @(:connection service)]
      (.disconnect connection))
    (.join ^Thread (:worker service) 250)
    (.clear ^LinkedBlockingQueue (:updates service))
    (.offer ^LinkedBlockingQueue (:updates service) ::stopped)))

(defn next-update-command [service]
  (when service
    (program/cmd
     #(let [snapshot (.take ^LinkedBlockingQueue (:updates service))]
        (when-not (or (realized? (:stop service)) (= ::stopped snapshot))
          {:type :news-updated :service-id (:id service) :news snapshot})))))
