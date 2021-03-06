(ns iwaswhere-web.upload
  "Provides upload via REST call."
  (:require [ring.adapter.jetty :as j]
            [compojure.core :refer [routes POST PUT]]
            [clojure.java.io :as io]
            [iwaswhere-web.imports :as i]
            [iwaswhere-web.files :as f]
            [image-resizer.util :refer :all]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [iwaswhere-web.file-utils :as fu]))

(def upload-port (Integer/parseInt (get (System/getenv) "UPLOAD_PORT" "3001")))

(defn state-fn
  "Fires up REST endpoint that accepts import files:
    - /upload/text-entry.json
    - /upload/visits.json"
  [put-fn]
  (let [post-fn
        (fn [filename req put-fn]
          (with-open [rdr (io/reader (:body req))]
            (case filename
              "text-entries.json" (i/import-text-entries-fn
                                    rdr put-fn {} filename)
              "visits.json" (i/import-visits-fn rdr put-fn {} filename)
              (prn req))
            "OK"))
        binary-post-fn
        (fn [dir filename req]
          (let [filename (str fu/data-path "/" dir "/" filename)
                file (java.io.File. filename)]
            (io/make-parents file)
            (log/info :server/upload-cmp req)
            (io/copy (:body req) file))
          "OK")
        app
        (routes
          (PUT "/upload/:dir/:file" [dir file :as r]
            (binary-post-fn dir file r))
          (POST "/upload/:filename" [filename :as r]
            (post-fn filename r put-fn)))]
    (future (j/run-jetty app {:port upload-port :join? false})))
  {:state (atom {})})

(defn cmp-map
  [cmp-id]
  {:cmp-id   cmp-id
   :state-fn state-fn
   :opts     {:reload-cmp false}})

