(ns aspire-rollout
  (:require [clojure.xml :as xml]
            [clojure.string :as s]
            [clojure-csv.core :as csv]
            [clojure.java.jdbc :as sql]
            [clojure.edn]
            [datomic.api :as d]
            [navigator])
  (:import (java.io File)))

(defn get-config []
  (let [path "rollout-conf.edn"]
    (if (.isFile (File. path))
      (clojure.edn/read-string (slurp path))
      (throw (Exception. (str "Config file missing: " path))))))

(defn get-db-conn [{:keys [datomic-uri] :as system}]
  (assoc system :db-conn (d/connect datomic-uri)))

;; TODO: version probably isn't correct
(defn xml->competency [comp-node]
  (let [attrs (:attrs comp-node)]
    {:comp/id-sk (:num attrs)
     :comp/name (:label attrs)
     :comp/description (s/trim (first (:content comp-node)))
     :comp/version "LTP"
     :comp/status :comp.status/active}))

(defn file->comps [file]
  (for [x (xml-seq (xml/parse file))
        :when (= (get-in x [:attrs :Standard]) "Standard")]
    (xml->competency x)))

(defn get-comps [dir-path]
  (if (.isDirectory (File. dir-path))
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

(defn comps->comp-ids [comps]
  (into #{} (for [c comps] (:comp/id-sk c))))

;; TODO: segment degenerate comps
(defn update-pod-compid [comp-ids dummy-compid pod]
  (assoc pod :comp_id_sk (if (contains? comp-ids (:id_sk pod))
                           (:id_sk pod)
                           dummy-compid)))

(defn get-pods-with-compids [moodle-db comps dummy-compid]
  (let [comp-ids (comps->comp-ids comps)]
    (map (partial update-pod-compid comp-ids dummy-compid)
         (sql/query moodle-db ["select * from mdl_asmt_pod"]))))

(defn update-moodle-pods! [moodle-db pods]
  (doseq [pod pods]
    (sql/update! moodle-db :mdl_asmt_pod {:comp_id_sk (:comp_id_sk pod)} ["id = ?" (:id pod)])))

;; competencyName or lmName?
;; TODO: make sure this is correct:
(defn content->pod [moodle-db
                    origin
                    pod-type
                    {:keys [id code] name :lmName description :competencyName}]
  (let [code (clojure.edn/read-string code)]
    (if (number? code)
      (let [query [(str "select mcv.id "
                        "from mdl_master_course mc "
                        "join mdl_master_course_version mcv on mcv.master_course_idstr = mc.master_course_idstr "
                        "where mcv.version = 'LTP' "
                        "and mc.name ilike ("
                        "  select mc2.name"
                        "  from mdl_asmt_pod ap"
                        "  join mdl_master_course_version mcv2 on ap.master_course_version_id = mcv2.id"
                        "  join mdl_master_course mc2 on mcv2.master_course_idstr = mc2.master_course_idstr"
                        "  where ap.id = ?"
                        ") || '%LTP'") code]]
        {:id_sk id
         :id_sk_origin origin
         :comp_id_sk code
         :asmt_pod_type_id pod-type
         :name name
         :description description
         :master_course_version_id (:id (first (sql/query moodle-db query)))})
      nil)))

(defn load-new-pods! [moodle-db status pods]
  (let [now (quot (System/currentTimeMillis) 1000)]
    (doseq [pod pods]
      (sql/insert! moodle-db :mdl_asmt_pod (merge {:timecreated now
                                                   :timemodified now
                                                   :status_idstr status}
                                                  pod)))))

(defn load-navigator-entities! [db-conn type entities]
  (doseq [ent entities]
    (navigator/tx-entity! db-conn type ent)))

(defn pod->perf-asmt [type {:keys [id_sk id_sk_origin name comp_id_sk]}]
  {:perf-asmt/id-sk id_sk
   :perf-asmt/id-sk-origin (keyword id_sk_origin)
   :perf-asmt/name name
   :perf-asmt/version "figgitybloop"  ; TODO: construct a version
   :perf-asmt/type type
   :perf-asmt/comps [[:comp/id-sk comp_id_sk]]})

(defn content->perf-asmt [origin type {:keys [id code] name :lmName}]
  {:perf-asmt/id-sk id
   :perf-asmt/id-sk-origin origin
   :perf-asmt/name name
   :perf-asmt/version "figgitybloop"  ; TODO: construct a version
   :perf-asmt/type type
   :perf-asmt/comps [[:comp/id-sk code]]})

(defn -main [& args]
  (let [system (get-config)
        system (get-db-conn system)
        comps (get-comps (:comps-dir system))
        content (get-content (:content-path system))
        pods (get-pods-with-compids (:moodle-db system) comps (:comp/id-sk (:dummy-comp system)))]
    (update-moodle-pods! (:moodle-db system) pods)
    (load-new-pods! (:moodle-db system)
                    (:se-pod-status system)
                    (->> content (map (partial content->pod
                                               (:moodle-db system)
                                               (:se-origin system)
                                               (:se-pod-type system)))
                         (remove nil?)))  ;; FIXME: WE SHOULDN'T HAVE TO DO THIS WITH THE REAL DATA
    (load-navigator-entities! (:db-conn system) :comp (concat [(:dummy-comp system)] comps))
    (load-navigator-entities! (:db-conn system) :perf-asmt (concat (map (partial content->perf-asmt
                                                                                (keyword (:se-origin system))
                                                                                (:se-perf-asmt-type system))
                                                                       content)
                                                                  (map (partial pod->perf-asmt
                                                                                (:pod-perf-asmt-type system))
                                                                       pods)))))
