(ns net.http-test
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

(defn echo-handler
  [request]
  {:status  200
   :headers {:connection "close"}
   :body    (a/pipe (:body request) (a/chan 10))})

(defn mktxt
  [i]
  (reduce str "" (repeat i "foobar")))

(defn req
  [port payload]
  (let [resp (client/request {:uri       (str "http://localhost:" port)
                              :headers   {:content-length (count payload)}
                              :body      (buf/wrapped-string payload)
                              :transform st/transform})]
    (assoc resp :body (a/<!! (:body resp)))))

(deftest http-echo
  (testing "in and out"
    (let [port   (get-port)
          server (server/run-server {:port port} echo-handler)
          client (client/build-client {})]
      (doseq [i (range 1) :let [txt (mktxt i) resp (req port txt)]]
        (is (= 200 (:status resp)))
        (is (= "close" (get-in resp [:headers :connection])))
        (is (= txt (:body resp)))))))
