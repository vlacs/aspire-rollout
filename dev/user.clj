(ns user
  (:require [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [clojure.xml :as xml]
            [clojure.string :as s]
            [clojure-csv.core :as csv]
            [clojure.java.jdbc :as sql]
            [clojure.edn]
            [aspire-rollout :refer :all]))

(def config nil)

(defn go []
  (alter-var-root #'config (fn [_] (get-config))))

(defn reset []
  (refresh :after 'user/go))
