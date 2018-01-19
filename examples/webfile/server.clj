(ns webfile.server
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async         :as a]
            [webfile.http               :refer [make-http]]
            [webfile.engine             :refer [make-engine]]
            [clojure.tools.logging      :refer [debug info error warn]]))

(defn -main
  [root]
  (let [sys (-> (component/system-map :engine (make-engine root)
                                      :http   (make-http))
                (component/system-using {:http [:engine]}))]
    (try
      (component/start-system sys)
      (info "server started")
      (catch Exception e
        (error e "caught exception")
        (System/exit 1)))))
