(defproject aspire-rollout "0.1.0-SNAPSHOT"
  :description "VLACS Show Evidence rollout data munging"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clojure-csv/clojure-csv "2.0.1"]
                 [org.clojure/java.jdbc "0.3.2"]
                 [org.clojure/data.json "0.2.5"]
                 [org.postgresql/postgresql "9.3-1101-jdbc4"]
                 [http-kit "2.1.16"]
                 ^{:voom {:repo "https://github.com/vlacs/navigator" :branch "master"}}
                 [org.vlacs/navigator "0.1.2-20140610_174531-g9089f2d"]]
  :pedantic? :warn ; :abort
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.4"]
                                  [com.datomic/datomic-free "0.9.4707"]]}})
