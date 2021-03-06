(ns iwaswhere-web.ui.entry.briefing.tasks
  (:require [matthiasn.systems-toolbox.component :as st]
            [reagent.ratom :refer-macros [reaction]]
            [re-frame.core :refer [subscribe]]
            [iwaswhere-web.utils.misc :as u]
            [iwaswhere-web.ui.entry.actions :as a]
            [iwaswhere-web.utils.parse :as up]
            [clojure.string :as s]
            [iwaswhere-web.ui.entry.utils :as eu]))

(defn task-sorter
  "Sorts tasks."
  [x y]
  (let [c (compare (get-in x [:task :priority] :X)
                   (get-in y [:task :priority] :X))]
    (if (not= c 0) c (compare (get-in x [:task :active-from])
                              (get-in y [:task :active-from])))))

(defn m-to-hhmm
  [minutes]
  (let [dur (.duration js/moment minutes "minutes")
        ms (.asMilliseconds dur)
        utc (.utc js/moment ms)
        fmt (.format utc "HH:mm")]
    fmt))

(defn started-tasks
  "Renders table with open entries, such as started tasks and open habits."
  [tab-group local local-cfg put-fn]
  (let [cfg (subscribe [:cfg])
        query-cfg (subscribe [:query-cfg])
        started-tasks (subscribe [:started-tasks])
        options (subscribe [:options])
        entries-map (subscribe [:entries-map])
        options (subscribe [:options])
        stories (reaction (:stories @options))
        not-on-hold #(not (:on-hold (:task %)))
        on-hold-filter (fn [entry]
                         (let [on-hold (:on-hold (:task entry))]
                           (if (:on-hold @local)
                             on-hold
                             (not on-hold))))
        saga-filter (fn [entry]
                      (if-let [selected (:selected @local)]
                        (let [story (get @stories (:linked-story entry))]
                          (= selected (:linked-saga story)))
                        true))
        open-filter (fn [entry] (not (-> entry :task :done)))
        filter-btn (fn [fk]
                     [:span.filter {:class    (when (:on-hold @local) "current")
                                    :on-click #(swap! local update-in [:on-hold] not)}
                      (name fk)])
        update-search (fn [ts]
                        (fn [_ev]
                          (let [query-id (:query-id local-cfg)
                                q (get-in @query-cfg [:queries query-id])]
                            (put-fn [:search/update
                                     (update-in q [:linked] #(when-not (= % ts) ts))]))))
        entries-list (reaction
                       (let [entries-map @entries-map
                             find-missing (u/find-missing-entry entries-map put-fn)
                             entries (->> @started-tasks
                                          (map (fn [ts] (find-missing ts)))
                                          (filter on-hold-filter)
                                          (filter saga-filter)
                                          (filter open-filter)
                                          (sort task-sorter))
                             conf (merge @cfg @options)]
                         (if (:show-pvt @cfg)
                           entries
                           (filter (u/pvt-filter conf entries-map) entries))))]
    (fn started-tasks-list-render [tab-group local local-cfg put-fn]
      (let [entries-list @entries-list]
        (when (seq entries-list)
          [:div.linked-tasks
           [:table.tasks
            [:tbody
             [:tr
              [:th ""]
              [:th [:span.fa.fa-diamond]]
              [:th [:span.fa.fa-clock-o]]
              [:th
               [:div
                "started tasks: "
                [filter-btn :on-hold]]]]
             (for [entry entries-list]
               (let [ts (:timestamp entry)]
                 ^{:key ts}
                 [:tr {:on-click (update-search ts)}
                  [:td
                   (when-let [prio (-> entry :task :priority)]
                     [:span.prio {:class prio} prio])]
                  [:td.award-points
                   (when-let [points (-> entry :task :points)]
                     points)]
                  [:td.estimate
                   (when-let [estimate (-> entry :task :estimate-m)]
                     (m-to-hhmm estimate))]
                  [:td
                   [:strong (some-> entry
                                    :md
                                    (s/replace "#task" "")
                                    (s/replace "#habit" "")
                                    (s/replace "##" "")
                                    s/split-lines
                                    first)]]]))]]])))))

(defn open-linked-tasks
  "Show open tasks that are also linked with the briefing entry."
  [ts local local-cfg put-fn]
  (let [entry (:entry (eu/entry-reaction ts))
        cfg (subscribe [:cfg])
        query-cfg (subscribe [:query-cfg])
        results (subscribe [:results])
        options (subscribe [:options])
        stories (reaction (:stories @options))
        entries-map (subscribe [:entries-map])
        update-search (fn [ts]
                        (fn [_ev]
                          (let [query-id (:query-id local-cfg)
                                q (get-in @query-cfg [:queries query-id])]
                            (put-fn [:search/update
                                     (update-in q [:linked] #(when-not (= % ts) ts))]))))
        linked-filters {:active  (up/parse-search "#task ~#done ~#closed ~#backlog")
                        :open    (up/parse-search "#task ~#done ~#closed ~#backlog")
                        :done    (up/parse-search "#task #done")
                        :closed  (up/parse-search "#task #closed")
                        :backlog (up/parse-search "#task #backlog")}
        filter-btn (fn [fk text]
                     [:span.filter {:class    (when (= fk (:filter @local)) "current")
                                    :on-click #(swap! local assoc-in [:filter] fk)}
                      (name fk) (when (= fk (:filter @local)) text)])]
    (fn open-linked-tasks-render [ts local local-cfg put-fn]
      (let [{:keys [tab-group query-id]} local-cfg
            linked-entries-set (into (sorted-set) (:linked-entries-list @entry))
            entries-w-done (into {} (map (fn [[k v]]
                                           [k (if (-> v :task :done)
                                                (update-in v [:tags] conj "#done")
                                                v)])
                                         @entries-map))
            linked-mapper (u/find-missing-entry entries-w-done put-fn)
            linked-entries (mapv linked-mapper linked-entries-set)
            conf (merge @cfg @options)
            linked-entries (if (:show-pvt conf)
                             linked-entries
                             (filter (u/pvt-filter conf entries-w-done) linked-entries))
            current-filter (get linked-filters (:filter @local))
            filter-fn (u/linked-filter-fn entries-w-done current-filter put-fn)
            saga-filter (fn [entry]
                          (if-let [selected (:selected @local)]
                            (let [story (get @stories (:linked-story entry))]
                              (= selected (:linked-saga story)))
                            true))
            active-filter (fn [t]
                            (let [active-from (-> t :task :active-from)
                                  current-filter (get linked-filters (:filter @local))]
                              (if (and active-from (= (:filter @local) :active))
                                (let [from-now (.fromNow (js/moment active-from))]
                                  (s/includes? from-now "ago"))
                                true)))
            linked-tasks (->> linked-entries
                              (filter filter-fn)
                              (filter saga-filter)
                              (filter active-filter)
                              (sort-by #(or (-> % :task :priority) :X)))
            time-reducer (fn [acc t] (+ acc (get-in t [:task :estimate-m] 0)))
            total-time (reduce time-reducer 0 linked-tasks)]
        [:div.linked-tasks
         [filter-btn :active (str " - " (m-to-hhmm total-time))]
         [filter-btn :open (str " - " (m-to-hhmm total-time))]
         [filter-btn :done]
         [filter-btn :closed]
         [filter-btn :backlog]
         [:table.tasks
          [:tbody
           [:tr
            [:th ""]
            [:th [:span.fa.fa-diamond]]
            [:th [:span.fa.fa-clock-o]]
            [:th [:strong "tasks"]]]
           (for [task linked-tasks]
             (let [ts (:timestamp task)
                   on-drag-start (a/drag-start-fn task put-fn)]
               ^{:key ts}
               [:tr {:on-click (update-search ts)}
                (let [prio (or (-> task :task :priority) "-")]
                  [:td
                   [:span.prio {:class         prio
                                :draggable     true
                                :on-drag-start on-drag-start}
                    prio]])
                [:td.award-points
                 (when-let [points (-> task :task :points)]
                   points)]
                [:td.estimate
                 (when-let [estimate (-> task :task :estimate-m)]
                   (m-to-hhmm estimate))]
                [:td.left [:strong (some-> task
                                           :md
                                           (s/replace "#task" "")
                                           (s/replace "##" "")
                                           s/trim
                                           s/split-lines
                                           first)]]]))]]]))))
