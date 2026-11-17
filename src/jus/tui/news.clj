(ns jus.tui.news
  "In-memory seven-day catch-up state; independent of the terminal and HTTP."
  (:require [clojure.set :as set])
  (:import (java.time Instant LocalDate ZoneOffset)))

(def refresh-ms 300000)

(defn today [^Instant now]
  (LocalDate/ofInstant now ZoneOffset/UTC))

(defn dates [^Instant now]
  (let [date (today now)] (mapv #(.minusDays date %) (range 7))))

(defn initial-state [channels]
  {:channels channels :pages {} :messages {} :ready #{} :new-ids #{} :errors {}})

(defn expire [state now]
  (let [days (set (dates now))
        start (.toInstant (.atStartOfDay ^LocalDate (last (dates now)) ZoneOffset/UTC))
        end (.toInstant (.atStartOfDay (.plusDays (today now) 1) ZoneOffset/UTC))
        pages (into {} (filter (fn [[[channel date] _]]
                                 (and (some #{channel} (:channels state)) (days date)))) (:pages state))
        messages (into {}
                       (for [channel (:channels state)]
                         [channel (->> pages
                                       (filter #(= channel (ffirst %)))
                                       (mapcat val)
                                       (filter #(and (not (.isBefore ^Instant (:timestamp %) start))
                                                     (.isBefore ^Instant (:timestamp %) end)))
                                       (reduce #(assoc %1 (:id %2) %2) {})
                                       vals
                                       (sort-by (juxt :timestamp :id) #(compare %2 %1)) vec)]))
        retained (set (map :id (mapcat val messages)))]
    (assoc state :pages pages :messages messages
           :new-ids (set/intersection retained (:new-ids state)))))

(defn apply-page [state channel date messages now]
  (let [old-ids (set (map :id (get-in state [:messages channel])))
        state (expire (assoc-in state [:pages [channel date]] messages) now)
        fresh (when ((:ready state) channel)
                (remove old-ids (map :id (get-in state [:messages channel]))))
        ready? (every? #(contains? (:pages state) [channel %]) (dates now))]
    (cond-> (-> state
                (update :new-ids into fresh)
                (assoc :last-refresh now))
      ready? (update :ready conj channel)
      ready? (update :errors dissoc channel))))

(defn requests [state now]
  (vec (for [channel (:channels state)
             date (dates now)
             :when (or (not (contains? (:pages state) [channel date]))
                       (some #{date} (take 2 (dates now))))]
         [channel date])))

(defn retry-delay [failures retry-after]
  (let [exponential (* refresh-ms (bit-shift-left 1 (min 4 failures)))
        requested (try (* 1000 (Long/parseLong (str retry-after)))
                       (catch Exception _ 0))]
    (max exponential (min 86400000 requested))))
