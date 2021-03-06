(ns iwaswhere-web.ui.entry.briefing
  (:require [matthiasn.systems-toolbox.component :as st]
            [reagent.ratom :refer-macros [reaction]]
            [re-frame.core :refer [subscribe]]
            [iwaswhere-web.charts.data :as cd]
            [iwaswhere-web.ui.charts.common :as cc]
            [iwaswhere-web.utils.misc :as u]
            [iwaswhere-web.ui.entry.briefing.tasks :as tasks]
            [iwaswhere-web.ui.entry.briefing.habits :as habits]
            [iwaswhere-web.ui.entry.briefing.time :as time]
            [iwaswhere-web.utils.parse :as up]
            [clojure.string :as s]
            [reagent.core :as r]))

(defn planned-actual
  "Draws vertical stacked barchart."
  [entry]
  (let [stats (subscribe [:stats])
        chart-data (subscribe [:chart-data])
        options (subscribe [:options])
        sagas (reaction (:sagas @options))
        y-scale 0.0045]
    (fn [entry]
      (let [{:keys [pomodoro-stats]} @chart-data
            day (-> entry :briefing :day)
            day-stats (get pomodoro-stats day)
            allocation (-> entry :briefing :time-allocation)
            sagas @sagas
            actual-times (:time-by-saga day-stats)
            remaining (cd/remaining-times actual-times allocation)
            rect (fn [entity x v y]
                   (let [h (* y-scale v)
                         x (inc (* y-scale x))
                         entity-name (or (:saga-name (get sagas entity)) "none")]
                     ^{:key (str entity)}
                     [:rect {:fill   (cc/item-color entity-name)
                             :y      y
                             :x      x
                             :width  h
                             :height 9}]))
            legend (fn [text x y]
                     [:text {:x           x
                             :y           y
                             :stroke      "none"
                             :fill        "#333"
                             :text-anchor :left
                             :style       {:font-size 7}}
                      text])]
        (when (seq allocation)
          [:svg.planned-actual
           {:shape-rendering "crispEdges"
            :style           {:height "41px"}}
           [:g
            [:line {:x1           1
                    :x2           260
                    :y1           38
                    :y2           38
                    :stroke-width 0.5
                    :stroke       "#333"}]
            (for [h (range 16)]
              (let [x (inc (* y-scale h 60 60))
                    stroke-w (if (zero? (mod h 3)) 1.5 0.5)]
                ^{:key h}
                [:line {:x1           x
                        :x2           x
                        :y1           36
                        :y2           40.5
                        :stroke-width stroke-w
                        :stroke       "#333"}]))
            (for [[entity {:keys [x v]}] (cd/time-by-entity-stacked allocation)]
              (rect entity x v 3))
            (for [[entity {:keys [x v]}] (cd/time-by-entity-stacked actual-times)]
              (rect entity x v 14))
            (for [[entity {:keys [x v]}] (cd/time-by-entity-stacked remaining)]
              (rect entity x v 25))
            [legend "allocation" 3 10]
            [legend "actual" 3 21]
            [legend "remaining" 3 32]]])))))

(defn briefing-view
  [entry put-fn edit-mode? local-cfg]
  (let [chart-data (subscribe [:chart-data])
        query-cfg (subscribe [:query-cfg])
        cfg (subscribe [:cfg])
        day (-> entry :briefing :day)
        today (.format (js/moment.) "YYYY-MM-DD")
        filter-btn (if (= day today) :active :open)
        local (r/atom {:filter                  filter-btn
                       :outstanding-time-filter true
                       :on-hold                 false})
        input-fn
        (fn [entry]
          (fn [ev]
            (let [day (-> ev .-nativeEvent .-target .-value)
                  updated (assoc-in entry [:briefing :day] day)]
              (put-fn [:entry/update-local updated]))))
        time-alloc-input-fn
        (fn [entry saga]
          (fn [ev]
            (let [m (js/parseInt (-> ev .-nativeEvent .-target .-value))
                  s (* m 60)
                  updated (assoc-in entry [:briefing :time-allocation saga] s)]
              (put-fn [:entry/update-local updated]))))]
    (fn briefing-render [entry put-fn edit-mode? local-cfg]
      (when (contains? (:tags entry) "#briefing")
        (let [ts (:timestamp entry)
              {:keys [pomodoro-stats task-stats wordcount-stats]} @chart-data
              day (-> entry :briefing :day)
              day-stats (get pomodoro-stats day)
              excluded (:excluded (:briefing @cfg))
              logged-s (->> day-stats
                            :time-by-saga
                            (filter (fn [[s _]] (not (contains? excluded s))))
                            (map second)
                            (apply +))
              dur (u/duration-string logged-s)
              word-stats (get wordcount-stats day)
              {:keys [tasks-cnt done-cnt closed-cnt]} (get task-stats day)
              tab-group (:tab-group local-cfg)
              query (reaction (get-in @query-cfg [:queries (:query-id local-cfg)]))
              linked (:linked @query)
              close (fn [_ev]
                      (let [query-id (:query-id local-cfg)
                            q (get-in @query-cfg [:queries query-id])]
                        (put-fn [:search/update (assoc-in q [:linked] nil)])))]
          [:div.briefing
           [:form.briefing-details
            [:fieldset
             [:legend (or day "date not set")]
             (when edit-mode?
               [:div
                [:label " Day: "]
                [:input {:type     :date
                         :on-input (input-fn entry)
                         :value    day}]])
             (when tasks-cnt
               [:div
                "Tasks: " [:strong tasks-cnt] " created, "
                [:strong done-cnt] " done, "
                [:strong closed-cnt] " closed. "
                [:strong (or (:word-count word-stats) 0)] " words written."])
             [planned-actual entry]
             [:div
              "Total planned: "
              [:strong
               (u/duration-string
                 (apply + (map second (-> entry :briefing :time-allocation))))]
              (when (seq dur)
                [:span
                 " Logged: " [:strong dur] " in " (:total day-stats) " entries."])]
             [time/time-by-sagas entry day-stats local edit-mode? put-fn]
             (if linked
               [:div
                [:span.btn.delete-warn
                 [:span.fa.fa-close] [:span {:on-click close} "close"]]]
               [:div
                [tasks/started-tasks tab-group local local-cfg put-fn]
                [tasks/open-linked-tasks ts local local-cfg put-fn]
                [habits/waiting-habits tab-group entry local-cfg put-fn]
                (when day-stats
                  [time/time-by-stories day-stats local put-fn])])]]])))))
