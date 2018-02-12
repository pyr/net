(ns net.http-client-test
  (:require [net.http.client      :as client]
            [net.http.server      :as server]
            [net.ty.buffer        :as buf]
            [net.transform.string :as st]
            [clojure.core.async   :as a]
            [clojure.test         :refer :all]))

(defn get-port
  []
  (let [sock (java.net.ServerSocket. 0)
        port (.getLocalPort sock)]
    (.close sock)
    port))

(defn req
  [port payload]
  (let [resp (client/request {:uri       (str "http://localhost:" port)
                              :headers   {:content-length (count payload)}
                              :body      (buf/wrapped-string payload)
                              :transform st/transform})]
    (assoc resp :body (a/<!! (:body resp)))))


(def success-response
  {:status  200
   :body    "foobar"
   :headers {:connection     "close"
             :content-length "6"
             :content-type   "text/plain"}})

(def success-handler
  (constantly success-response))

(deftest success-server
  (let [port   (get-port)
        server (server/run-server {:port port} success-handler)
        client (client/build-client {})]
    (testing "referential transparency"
      (= (client/request {:request-method :get :uri (str "http://localhost:" port)})
         success-response))))
