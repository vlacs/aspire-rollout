(ns rollout
  (require [clojure.xml :as xml]
           [clojure.string :as s]))

(defn clean-content [content-str]
  content-str)

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

(defn dir->comps [dir-path]
  (reduce #(concat %1 (rollout/file->comps %2))
          []
          (->> dir-path
               (clojure.java.io/file)
               (file-seq)
               (rest))))
