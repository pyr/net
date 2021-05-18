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

(def success-response
  {:status  200
   :version "HTTP/1.1"
   :body    ""
   :headers {:connection "close"}})

(def success-handler
  (constantly success-response))

(defn echo-handler
  [{:keys [body headers] :as request}]
  (assoc success-response
         :body    body
         :headers {:connection     "close"
                   :content-length (:content-length headers)}))

(defn req
  [payload]
  (let [{:keys [body] :as resp} (client/request payload)]
    (cond-> resp (some? body) (assoc :body (a/<!! body)))))

(deftest echo-server
  (let [port   (get-port)
        input  (a/to-chan (mapv buf/wrapped-string ["foo" "bar" "baz"]))
        server (server/run-server {:port port} echo-handler)
        len    "9"]
    (testing "echoing works"
      (is
       (= (req {:request-method :get
                :uri            (str "http://localhost:" port)
                :headers        {:transfer-encoding "chunked"
                                 :content-length    len}
                :transform      st/transform
                :body           input})
          (assoc success-response
                 :body "foobarbaz"
                 :headers {:connection     "close"
                           :content-length "9"}))))
    (server)))

(deftest success-server
  (let [port   (get-port)
        server (server/run-server {:port port} success-handler)]
    (testing "referential transparency"
      (is (= (req {:request-method :get
                   :uri            (str "http://localhost:" port)
                   :transform      st/transform})
             success-response)))
    (testing "uri with + supported"
      (doseq [[uri resp] [["/gong-site-bucket/gong-team-logo-2020.jpg?mtime=20210204200512&focal=50.92+26" success-response "Handle + in URI"]
                          ["/gong-site-bucket/gong-team-logo-2020.jpg?mtime=20210204200512&focal=50.92%2026" success-response "Handle %20 in URI"]]]
        (is (= resp
               (req {:request-method :get
                     :uri            (str "http://localhost:" port uri)
                     :transform      st/transform})))))
    (server)))

(deftest error-tests
  (is (thrown-with-msg?
       IllegalArgumentException
       #"SSL was required but no SSL context is present"
       (client/request nil {:request-method :get
                            :uri "https://foo.example.com"}))))
