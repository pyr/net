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


(comment
  (defn async-handler
    [{:keys [headers body] :as request}]
    (if (chunked? request)
      (let [rbody (a/chan 100)
            type  (:content-type headers "text/plain")]
        (info "handler found chunked request")
        (a/pipe body rbody)
        {:status  200
         :headers {"Content-Type"         type
                   "X-Content-Aggregated" "false"
                   "Transfer-Encoding"    "chunked"}
         :body    rbody})
      (let [payload (with-out-str (prn request))]
        (info "handler found aggregated request")
        {:status  200
         :headers {"Content-Type"         "text/plain"
                   "X-Content-Aggregated" "true"
                   "Content-Length"       (count payload)
                   "Transfer-Encoding"    "chunked"}
         :body    payload}))))
