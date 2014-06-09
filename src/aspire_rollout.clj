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

;; TODO: version?
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

(defn get-content [path master-master-courses]
  (if (.isFile (File. path))
    (let [content (csv/parse-csv (slurp path))]
      (map #(let [c (zipmap (map keyword (first content)) %)
                  code (clojure.edn/read-string (:code c))]
              (-> c
                  (assoc :code code)
                  (assoc :master-master-course (master-master-courses code))))
           (rest content)))
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

(def name-query
  (str "select mc2.name "
       "from mdl_asmt_pod ap "
       "join mdl_master_course_version mcv2 on ap.master_course_version_id = mcv2.id "
       "join mdl_master_course mc2 on mcv2.master_course_idstr = mc2.master_course_idstr "
       "where ap.id = ?"))

(defn pod-id->master-master-course [moodle-db pod-id]
  (if (contains? #{1790 1791} pod-id)   ;; special case where the "master master course" has changed
    "U.S. History"
    (:name (first (sql/query moodle-db [name-query pod-id])))))

(defn get-comp-master-master-courses [moodle-db comps]
  (into {} (for [c comps]
             (let [comp-id (clojure.edn/read-string (:comp/id-sk c))]
               [comp-id (pod-id->master-master-course moodle-db comp-id)]))))

(def mcv-query
  (str "select mcv.id "
       "from mdl_master_course mc "
       "join mdl_master_course_version mcv on mcv.master_course_idstr = mc.master_course_idstr "
       "where mcv.version = 'LTP' "
       "and mc.name ilike ? || '%LTP'"))

;; competencyName or lmName?
;; TODO: make sure this is correct:
(defn content->pod [moodle-db origin pod-type now status {:keys [id code master-master-course] name :lmName description :competencyName}]
  (let [master-course-version-id (:id (first (sql/query moodle-db [mcv-query master-master-course])))]
    {:id_sk id
     :id_sk_origin origin
     :comp_id_sk code
     :asmt_pod_type_id pod-type
     :name name
     :description description
     :master_course_version_id master-course-version-id
     :status_idstr status
     :timecreated now
     :timemodified now}))

(defn load-se-pod-type! [moodle-db pod-type]
  (sql/insert! moodle-db :mdl_asmt_pod_type pod-type))

(defn get-new-pods [system content]
  (let [moodle-db (:moodle-db system)
        se-pod-type-id (-> (sql/query (:moodle-db system) ["select id from mdl_asmt_pod_type where name = ?" (get-in system [:se-pod-type :name])])
                           (first)
                           (:id))
        now (quot (System/currentTimeMillis) 1000)]
    (map (partial content->pod (:moodle-db system) (:se-origin system) se-pod-type-id now (:se-pod-status system)) content)))

(defn load-new-pods! [system new-pods]
  (doseq [p new-pods]
    (sql/insert! (:moodle-db system) :mdl_asmt_pod p)))

(defn load-navigator-entities! [db-conn type entities]
  (doseq [ent entities]
    (navigator/tx-entity! db-conn type ent)))

(defn load-comps! [system comps]
  (load-navigator-entities! (:db-conn system) :comp (concat [(:dummy-comp system)] comps)))

(defn content->perf-asmt [origin type {:keys [id code version] name :lmName}]
  {:perf-asmt/id-sk id
   :perf-asmt/id-sk-origin origin
   :perf-asmt/name name
   :perf-asmt/version version
   :perf-asmt/type type
   :perf-asmt/comps [[:comp/id-sk code]]})

(defn pod->perf-asmt [type {:keys [id_sk id_sk_origin name comp_id_sk]}]
  {:perf-asmt/id-sk id_sk
   :perf-asmt/id-sk-origin (keyword id_sk_origin)
   :perf-asmt/name name
   :perf-asmt/version "figgitybloop"  ; TODO: construct a version
   :perf-asmt/type type
   :perf-asmt/comps [[:comp/id-sk comp_id_sk]]})

(defn get-perf-asmts [system content pods]
  (concat (map (partial content->perf-asmt (keyword (:se-origin system)) (:se-perf-asmt-type system)) content)
          (map (partial pod->perf-asmt (:pod-perf-asmt-type system)) pods)))

(defn load-perf-asmts! [system perf-asmts]
  (load-navigator-entities! (:db-conn system) :perf-asmt perf-asmts))

(defn collision-aware-map-invert [map]
  "Like map-invert, but vals of resulting map are vectors of the
  original map keys.

  user=> (collision-aware-map-invert {:a 1, :b 2, :c 1})
  {2 [:b], 1 [:c :a]}"
  (reduce (fn collision-aware-map-invert- [r [k v]]
            (assoc r v (if-let [keys (r v)]
                         (conj keys k)
                         [k])))
          {} map))

;; TODO: tags probably need more than just this
(defn get-tag [[tag-name comps]]
  {:comp-tag/name tag-name
   :comp-tag/child-comps (map (partial vector :comp/id-sk) comps)})

(defn get-tags [comp-master-master-courses]
  (->> comp-master-master-courses
       (collision-aware-map-invert)
       (map get-tag)))

(defn -main [& args]
  (let [system (get-db-conn (get-config))
        comps (get-comps (:comps-dir system))
        comp-master-master-courses (get-comp-master-master-courses (:moodle-db system) comps)
        tags (get-tags comp-master-master-courses)
        content (get-content (:content-path system) comp-master-master-courses)
        pods (get-pods-with-compids (:moodle-db system) comps (:comp/id-sk (:dummy-comp system)))
        new-pods (get-new-pods system content)
        perf-asmts (get-perf-asmts system content pods)]
    (update-moodle-pods! (:moodle-db system) pods)
    (load-se-pod-type! (:moodle-db system) (:se-pod-type system))
    (load-new-pods! system new-pods)
    (load-comps! system comps)
    (load-perf-asmts! system perf-asmts)))

(comment

  (def system (get-db-conn (get-config))) ; (reset) does this one for you
  (def comps (get-comps (:comps-dir system)))
  (def comp-master-master-courses (get-comp-master-master-courses (:moodle-db system) comps))
  (def tags (get-tags comp-master-master-courses))
  (def content (get-content (:content-path system) comp-master-master-courses))
  (def pods (get-pods-with-compids (:moodle-db system) comps (:comp/id-sk (:dummy-comp system))))
  (def new-pods (get-new-pods system content))
  (def perf-asmts (get-perf-asmts system content pods))

  )
