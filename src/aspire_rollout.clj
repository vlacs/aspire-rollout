(ns aspire-rollout
  (:require [clojure.xml :as xml]
            [clojure.string :as s]
            [clojure-csv.core :as csv]
            [clojure.java.jdbc :as sql]
            [clojure.edn])
  (:import (java.io File)))

(defn get-config []
  (let [path "rollout-conf.edn"]
    (if (.isFile (File. path))
      (clojure.edn/read-string (slurp path))
      (throw (Exception. (str "Config file missing: " path))))))

(defn xml->competency [comp-node]
  (let [attrs (:attrs comp-node)]
    {:comp/id-sk (Integer. (:num attrs))
     :comp/name (:label attrs)
     :comp/description (s/trim (first (:content comp-node)))
     :comp/version "LTP"
     :comp/status :active}))

(defn file->comps [file]
  (for [x (xml-seq (xml/parse file))
        :when (= (get-in x [:attrs :Standard]) "Standard")]
    (xml->competency x)))

(defn get-comps [dir-path]
  (if (.isDir (File. dir-path))
    (reduce #(concat %1 (file->comps %2))
            []
            (->> dir-path
                 (clojure.java.io/file)
                 (file-seq)
                 (rest)))
    (throw (Exception. (str "Comps folder missing: " dir-path)))))

(defn get-content [path]
  (if (.isFile (File. path))
    (let [content (csv/parse-csv (slurp path))]
      (map #(zipmap (map keyword (first content)) %) (rest content)))
    (throw (Exception. (str "Content file missing: " path)))))

(declare get-pods
         update-pod-compids!
         load-new-pods!
         load-comps!
         load-perf-asmts!
         content->pods
         content->perf-asmts
         pods->perf-asmts)

(defn -main [& args]
  (let [config (get-config)
        moodle-db (:moodle-db config)
        aspire-db (:aspire-db config)
        comps (get-comps (:comps-path config))
        content (get-content (:content-path config))
        pods (get-pods moodle-db)]
    (update-pod-compids! moodle-db pods comps)
    (load-new-pods! moodle-db (content->pods content))
    (load-comps! aspire-db comps)
    (load-perf-asmts! aspire-db (merge (content->perf-asmts content)
                                       (pods->perf-asmts pods)))))

(comment

  (def pods  (sql/query aspire-rollout/db-spec ["select * from mdl_asmt_pod"]))
  (def comps (aspire-rollout/dir->comps "/home/dzaharee/rollout-files/"))

  )
