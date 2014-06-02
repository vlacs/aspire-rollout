(ns user
  (:require [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [clojure.xml :as xml]
            [clojure.string :as s]
            [clojure.pprint :refer (pprint)]
            [clojure-csv.core :as csv]
            [clojure.java.jdbc :as sql]
            [clojure.edn]
            [datomic.api :as d]
            [navigator]
            [aspire-rollout :refer :all]
            [datomic-schematode.core :as schematode]
            [navigator.schema :as schema]))

(def system nil)

(defn start-datomic! [{:keys [datomic-uri] :as system}]
  (d/create-database datomic-uri)
  (get-db-conn system))

(defn load-schema! [{:keys [db-conn] :as system}]
  [(schematode/init-schematode-constraints! db-conn)
   (schematode/load-schema! db-conn schema/schema)])

(defn stop-datomic! [{:keys [datomic-uri] :as system}]
  (dissoc system :db-conn)
  (d/delete-database datomic-uri))

(defn start! []
  (alter-var-root #'system (fn [_] (get-config)))
  (alter-var-root #'system start-datomic!)
  (load-schema! system))

(defn stop! []
  (alter-var-root #'system (fn [s] (when s (stop-datomic! s)))))


(defn reset []
  (stop!)
  (refresh :after 'user/start!))

(defn touch-that
  "Execute the specified query on the current DB and return the
   results of touching each entity.

   The first binding must be to the entity.
   All other bindings are ignored."
  [query & data-sources]
  (map #(d/touch
         (d/entity
          (d/db (:db-conn system))
          (first %)))
       (apply d/q query (d/db (:db-conn system)) data-sources)))

(defn ptouch-that
  "Example: (ptouch-that '[:find ?e :where [?e :user/username]])"
  [query & data-sources]
  (pprint (apply touch-that query data-sources)))
