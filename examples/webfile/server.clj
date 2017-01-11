(ns webfile.server
  (:require [com.stuartsierra.component :as component]
            [webfile.http               :refer [make-http]]
            [webfile.engine             :refer [make-engine]]
            [clojure.tools.logging      :refer [debug info error warn]]))

(defn -main
  [chunk-size root]
  (let [sys (-> (component/system-map :engine (make-engine root)
                                      :http   (make-http (Long/parseLong chunk-size)))
                (component/system-using {:http [:engine]}))]
    (try
      (component/start-system sys)
      (info "server started")
      (catch Exception e
        (error e "caught exception")
        (System/exit 1)))))
