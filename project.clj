(defproject aspire-rollout "0.1.0-SNAPSHOT"
  :description "VLACS Show Evidence rollout data munging"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clojure-csv/clojure-csv "2.0.1"]
                 [org.clojure/java.jdbc "0.3.2"]
                 [org.postgresql/postgresql "9.3-1101-jdbc4"]]
  :pedantic? :warn ; :abort
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.4"]]}})
