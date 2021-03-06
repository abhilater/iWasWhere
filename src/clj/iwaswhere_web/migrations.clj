(ns iwaswhere-web.migrations
  "This namespace is used for migrating entries to new versions."
  (:require [iwaswhere-web.files :as f]
            [iwaswhere-web.location :as loc]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [clj-uuid :as uuid]
            [camel-snake-kebab.core :refer :all]
            [cheshire.core :as cc]
            [clojure.string :as s]
            [clj-http.client :as hc]
            [clj-time.coerce :as ctc]
            [clj-time.format :as ctf]
            [clj-time.core :as ct]
            [me.raynes.fs :as fs]))

(defn add-tags-mentions
  "Parses entry for hashtags and mentions."
  [entry]
  (when-let [text (:md entry)]
    (let [tags (set (re-seq #"(?m)(?!^)#[\w-]+" text))
          mentions (set (re-seq #"@\w+" text))]
      (merge entry
             {:tags     tags
              :mentions mentions}))))

(defn migrate-entries
  "Initial state function, creates state atom and then parses all files in
  data directory into the component state."
  [conversion-fn]
  (let [files (file-seq (clojure.java.io/file "./data"))]
    (doseq [f (f/filter-by-name files #"\d{13}.edn")]
      (let [parsed (clojure.edn/read-string (slurp f))
            converted (conversion-fn parsed)
            filename (str "./data/" (:timestamp converted) ".edn")]
        (when converted
          (spit filename (with-out-str (pp/pprint converted))))))))

(defn migrate-to-uuids
  ; (migrate-to-uuids "./data/migration/daily-logs" "./data/daily-logs/2017-03-19.jrn")
  [path out-file]
  (let [files (file-seq (clojure.java.io/file path))
        ts-uuid (atom {})]
    (doseq [f (f/filter-by-name files #"\d{4}-\d{2}-\d{2}a?.jrn")]
      (with-open [reader (clojure.java.io/reader f)]
        (prn f)
        (let [lines (line-seq reader)]
          (doseq [line lines]
            (try
              (let [parsed (clojure.edn/read-string line)
                    ts (:timestamp parsed)
                    id (or (:id parsed)
                           (get-in @ts-uuid [ts])
                           (uuid/v1))
                    entry (merge parsed {:id id})
                    without-raw-exif (dissoc entry :raw-exif)
                    serialized (str (pr-str without-raw-exif) "\n")]
                (swap! ts-uuid assoc-in [ts] id)
                (spit out-file serialized :append true))
              (catch Exception ex
                (log/error "Exception" ex "when parsing line:\n" line)))))))
    (log/info (count @ts-uuid) "migrated")))

(defn migrate-books-to-sagas
  ; (migrate-to-uuids "./data/migration/book-to-saga" "./data/migration/2017-03-20.jrn")
  [path out-file]
  (let [files (file-seq (clojure.java.io/file path))
        ts-uuid (atom {})]
    (doseq [f (f/filter-by-name files #"\d{4}-\d{2}-\d{2}a?.jrn")]
      (with-open [reader (clojure.java.io/reader f)]
        (prn f)
        (let [lines (line-seq reader)]
          (doseq [line lines]
            (try
              (let [parsed (clojure.edn/read-string line)
                    entry (cond
                            (= (:entry-type parsed) :book)
                            (-> parsed
                                (assoc-in [:entry-type] :saga)
                                (assoc-in [:saga-name] (:book-name parsed))
                                (dissoc :book-name))

                            (= (:entry-type parsed) :story)
                            (-> parsed
                                (dissoc :linked-book)
                                (assoc-in [:linked-saga] (:linked-book parsed)))

                            :else parsed)
                    ts (:timestamp parsed)
                    serialized (str (pr-str entry) "\n")]
                (swap! ts-uuid assoc-in [ts] entry)
                (spit out-file serialized :append true))
              (catch Exception ex
                (log/error "Exception" ex "when parsing line:\n" line)))))))
    (log/info (count @ts-uuid) "migrated")))

(defn migrate-weight
  ; (migrate-weight "./data/migration/weight" "./data/migration/2017-03-23.jrn")
  [path out-file]
  (let [files (file-seq (clojure.java.io/file path))
        ts-uuids (atom {})
        weight-entry-uuids (atom {})]
    (doseq [f (f/filter-by-name files #"\d{4}-\d{2}-\d{2}a?.jrn")]
      (with-open [reader (clojure.java.io/reader f)]
        (prn f)
        (let [lines (line-seq reader)]
          (doseq [line lines]
            (try
              (let [parsed (clojure.edn/read-string line)
                    p [:measurements :weight :value]
                    ts (:timestamp parsed)
                    entry (if-let [w (get-in parsed p)]
                            (do
                              (swap! weight-entry-uuids assoc-in [ts] parsed)
                              (assoc-in parsed [:custom-fields "#weight" :weight] w))
                            parsed)
                    serialized (str (pr-str entry) "\n")]
                (swap! ts-uuids assoc-in [ts] entry)
                (spit out-file serialized :append true))
              (catch Exception ex
                (log/error "Exception" ex "when parsing line:\n" line)))))))
    (log/info (count @weight-entry-uuids) "-" (count @ts-uuids) "migrated.")))

(defn get-geoname [entry]
  (let [lat (:latitude entry)
        lon (:longitude entry)
        parser (fn [res] (cc/parse-string (:body res) #(keyword (->kebab-case %))))]
    (when (and lat lon)
      (let [res (hc/get (str "http://localhost:3003/geocode?latitude=" lat "&longitude=" lon))
            geoname (ffirst (parser res))]
        geoname))))

(defn add-geonames
  "Lookup geolocation for entries with lat and lon."
  ; (use 'iwaswhere-web.migrations)
  ; (time (add-geonames "./data/migration/geonames" "./data/migration/2017-04-26.jrn"))
  [path out-file]
  (let [files (file-seq (clojure.java.io/file path))
        state (atom {:countries {}})
        geonames-path "./data/geonames/"
        local-fmt (ctf/with-zone (ctf/formatters :year-month-day)
                                 (ct/default-time-zone))]
    (fs/mkdirs geonames-path)
    (doseq [f (f/filter-by-name files #"\d{4}-\d{2}-\d{2}a?.jrn")]
      (with-open [reader (clojure.java.io/reader f)]
        (prn f)
        (let [lines (line-seq reader)]
          (doseq [line lines]
            (try
              (let [parsed (clojure.edn/read-string line)
                    geoname (get-geoname parsed)
                    entry (loc/enrich-geoname parsed)
                    serialized (str (pr-str entry) "\n")]
                (when-not (= parsed entry)
                  (let [country (:country-code geoname)
                        serialized-geoname (with-out-str (pp/pprint geoname))
                        geo-name-id (:geo-name-id geoname)
                        filename (str geonames-path geo-name-id ".edn")
                        ts (:timestamp parsed)
                        day (ctf/unparse local-fmt (ctc/from-long ts))]
                    (swap! state assoc-in [:locations geo-name-id] geoname)
                    (swap! state update-in [:countries country] #(set (conj % day)))
                    (spit filename serialized-geoname)))
                (spit out-file serialized :append true))
              (catch Exception ex
                (log/error "Exception" ex "when parsing line:\n" line)))))))
    (let [countries (:countries @state)
          days-per-country (map (fn [[c days]] [c (count days)]) countries)]
      (log/info (count (:locations @state)) "locations in"
                (count countries) "countries found.")
      (doseq [[c days] (reverse (sort-by second days-per-country))]
        (println c days "days")))))
