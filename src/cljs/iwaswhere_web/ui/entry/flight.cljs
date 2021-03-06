(ns iwaswhere-web.ui.entry.flight
  (:require [matthiasn.systems-toolbox.component :as st]
            [reagent.ratom :refer-macros [reaction]]
            [re-frame.core :refer [subscribe]]
            [iwaswhere-web.utils.misc :as u]
            [iwaswhere-web.utils.parse :as up]
            [clojure.string :as s]
            [reagent.core :as r]))

(defn flight-view
  [entry put-fn edit-mode? local-cfg]
  (let [url (-> entry :flight :url)
        input-fn (fn [entry]
                   (fn [ev]
                     (let [day (-> ev .-nativeEvent .-target .-value)
                           updated (assoc-in entry [:flight :url] day)]
                       (put-fn [:entry/update-local updated]))))]
    (fn flight-render [entry put-fn edit-mode? local-cfg]
      (when (contains? (:tags entry) "#flight")
        (let [ts (:timestamp entry)]
          (when-not (-> entry :flight :duration)
            (put-fn [:import/flight entry]))
          [:div.flight
           [:form
            [:fieldset
             [:legend " FlightAware: "]
             [:div
              [:label "Duration"]
              [:span (-> entry :flight :duration)]]
             [:div
              [:label "Arrival"]
              [:span (-> entry :flight :arrival)]]
             [:div
              [:label "Miles"]
              [:span (-> entry :flight :miles)]]
             [:div
              [:label "URL"]
              [:input.url {:type     :text
                           :on-input (input-fn entry)
                           :value    url}]]
             [:iframe {:src url}]]]])))))
