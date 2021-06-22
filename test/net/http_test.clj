(ns net.http-test
  (:require [net.http.chunk     :as chunk]
            [net.http.client      :as client]
            [net.http.server      :as server]
            [net.http             :as http]
            [net.ty.bootstrap   :as bs]
            [net.ty.buffer        :as buf]
            [net.ty.channel     :as chan]
            [net.ty.future      :as f]
            [net.transform.string :as st]
            [clojure.core.async   :as a]
            [clojure.test         :refer :all]
            [net.http.request :as req]
            [net.http.headers :as headers])
  (:import  io.netty.handler.codec.http.HttpHeaders
            io.netty.handler.codec.http.HttpMethod
            io.netty.handler.codec.http.HttpVersion
            io.netty.handler.codec.http.DefaultHttpRequest
            clojure.core.async.impl.protocols.Channel))

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
  [{:keys [body headers]}]
  (assoc success-response
         :body    body
         :headers {:connection     "close"
                   :content-length (or (:content-length headers) 0)}))

(defn async-request
  "Execute an asynchronous HTTP request, produce the response
   asynchronously on the provided `handler` function.

   If no client is provided, create one."
  ([request-map handler]
   (async-request (client/build-client {}) request-map handler))
  ([{:keys [group channel ssl-ctx]} request-map handler]
   (when-not (:uri request-map)
     (throw (ex-info "malformed request-map, needs :uri key" {})))
   (let [transform   (:transform request-map)
         initializer (client/request-initializer false ssl-ctx handler transform nil nil)
         bs          (bs/bootstrap {:group   group
                                    :channel channel
                                    :handler initializer})
         chan        (some-> bs (bs/connect! "localhost" (request-map :port)) chan/sync! chan/channel)
         body        (chunk/prepare-body (:body request-map))
         req         (DefaultHttpRequest. HttpVersion/HTTP_1_1
                                          (req/data->method (:method request-map))
                                          ^String (:uri request-map)
                                          (headers/prepare (:headers request-map)))]
     (f/with-result [ftr (chan/write-and-flush! chan req)]
       (if (instance? Channel body)
         (chunk/start-write-listener chan body)
         (chan/write-and-flush! chan body)))
     chan)))

(defn extract-body [{:keys [body] :as resp}]
  (cond-> resp (some? body) (assoc :body (a/<!! body))))

(defn raw-req [payload]
  (let [p (promise)]
    (async-request payload #(deliver p %))
    (extract-body @p)))

(defn req [payload] (extract-body (client/request payload)))

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

(defn http-request [uri]
  (reify io.netty.handler.codec.http.HttpRequest
    (uri [this] uri)
    (headers [this] (HttpHeaders/EMPTY_HEADERS))
    (method [this] (HttpMethod/GET))
    (protocolVersion [this] (HttpVersion/HTTP_1_1))))

(def invalid-uri "file.jpg?mtime=20210218162931&focal=30.92%%2050.13%&tmtime=20210324130011")

(deftest bad-request-invalid-uri
  (let [port (get-port)
        server (server/run-server {:port port} (constantly 42))]
    (testing "invalid uri"
      (is
       (= (raw-req {:request-method :post
                    :port port
                    :body "foo"
                    :transform st/transform
                    :uri (str "http://localhost:" port "/" invalid-uri)})
          {:status 414
           :headers {}
           :version "HTTP/1.1"
           :body ""})))
    (server)))

(deftest bad-request-invalid-uri-chunked
  (let [port (get-port)
        server (server/run-server {:port port} (constantly 42))]
    (testing "invalid uri + chunked"
      (is
       (= (raw-req {:request-method :post
                    :headers {:transfer-encoding "chunked"
                              :content-length 9}
                    :port port
                    :body (a/to-chan (mapv buf/wrapped-string ["foo" "bar" "baz"]))
                    :transform st/transform
                    :uri (str "http://localhost:" port "/" invalid-uri)})
          {:status 414
           :headers {}
           :version "HTTP/1.1"
           :body ""})))
    (server)))

(deftest ->request-test
  (testing "invalid uri"
    (is (= :net.http/unparsable-request
           (http/->request (http-request invalid-uri)))))
  (testing "valid uri"
    (is (= {:uri "/file1",
            :raw-uri "/file1",
            :get-params {},
            :params {},
            :request-method :get,
            :version "HTTP/1.1",
            :headers {}}
           (http/->request (http-request "/file1"))))))
