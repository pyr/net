(ns net.http.client
  "Small wrapper around netty for HTTP clients."
  (:require [net.codec.b64      :as b64]
            [net.ssl            :as ssl]
            [net.http           :as http]
            [clojure.spec       :as s]
            [clojure.core.async :as async])
  (:import io.netty.bootstrap.Bootstrap
           io.netty.channel.Channel
           io.netty.channel.ChannelHandlerContext
           io.netty.channel.ChannelHandlerAdapter
           io.netty.channel.ChannelInboundHandlerAdapter
           io.netty.channel.ChannelOutboundHandlerAdapter
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelOption
           io.netty.channel.ChannelInitializer
           io.netty.channel.ChannelFutureListener

           io.netty.channel.nio.NioEventLoopGroup
           io.netty.channel.socket.nio.NioSocketChannel
           io.netty.channel.epoll.Epoll
           io.netty.channel.epoll.EpollSocketChannel
           io.netty.channel.epoll.EpollEventLoopGroup

           io.netty.handler.logging.LoggingHandler
           io.netty.handler.logging.LogLevel
           io.netty.handler.codec.http.HttpClientCodec
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpMethod
           io.netty.handler.codec.http.HttpHeaders
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.FullHttpResponse
           io.netty.handler.codec.http.DefaultFullHttpRequest
           io.netty.handler.codec.http.QueryStringEncoder
           io.netty.handler.ssl.SslContext
           java.net.URI
           java.nio.charset.Charset
           javax.xml.bind.DatatypeConverter))

(defn response-handler
  "Capture context and msg and yield a closure
   which generates a response.

   The closure may be called at once or submitted to a pool."
  [f ^ChannelHandlerContext ctx ^FullHttpResponse msg]
  (fn []
    (try
      (let [headers (http/headers (.headers msg))
            body    (http/bb->string (.content msg))
            req     {:status         (some-> msg .getStatus .code)
                     :headers        headers
                     :version        (-> msg .getProtocolVersion .text)
                     :body           body}]
        (f req))
      (finally
        ;; This actually releases the content
        (.release msg)))))

(defn netty-handler
  "Simple netty-handler, everything may happen in
   channel read, since we're expecting a full http request."
  [f]
  (proxy [ChannelInboundHandlerAdapter] []
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (f {:status 5555 :error e}))
    (channelRead [^ChannelHandlerContext ctx ^FullHttpResponse msg]
      (let [callback (response-handler f ctx msg)]
        (callback)))))

(defn ssl-ctx-handler
  "Add an SSL context handler to a channel"
  [ssl-ctx ^Channel channel]
  (.newHandler ssl-ctx (.alloc channel)))

(defn request-initializer
  "Our channel initializer."
  ([ssl-ctx handler]
   (request-initializer ssl-ctx handler 1048576))
  ([ssl-ctx handler max-body-size]
   (proxy [ChannelInitializer] []
     (initChannel [channel]
       (let [pipeline (.pipeline channel)]
         (when ssl-ctx
           (.addLast pipeline "ssl" (ssl-ctx-handler ssl-ctx channel)))
         (.addLast pipeline "codec"      (HttpClientCodec.))
         (.addLast pipeline "aggregator" (HttpObjectAggregator. (int max-body-size)))
         (.addLast pipeline "handler"    (netty-handler handler)))))))

(def http-versions
  "Keyword HTTP versions"
  {:http-1-1 HttpVersion/HTTP_1_1
   :http-1-0 HttpVersion/HTTP_1_0})

(defn string->version
  "When possible, get appropriate HttpVersion from a string.
   Throws when impossible."
  [^String s ^Exception e]
  (cond
    (re-matches #"(?i)http/1.1" s) HttpVersion/HTTP_1_1
    (re-matches #"(?i)http/1.0" s) HttpVersion/HTTP_1_0
    :else                          (throw e)))

(defn data->version
  "Get appropriate HttpVersion from a number of possible inputs.
   Defaults to HTTP/1.1 when nil, translate known keywords and
   strings or pass an HttpVersion through.

   Throws on other values."
  [v]
  (let [e (IllegalArgumentException. (str "invalid http version: " v))]
    (cond
      (nil? v)                  HttpVersion/HTTP_1_1
      (keyword? v)              (or (get http-versions v) (throw e))
      (string? v)               (string->version v e)
      (instance? HttpVersion v) v
      :else                     (throw e))))

(def http-methods
  "Keyword HTTP methods"
  {:connect HttpMethod/CONNECT
   :delete  HttpMethod/DELETE
   :get     HttpMethod/GET
   :head    HttpMethod/HEAD
   :options HttpMethod/OPTIONS
   :patch   HttpMethod/PATCH
   :post    HttpMethod/POST
   :put     HttpMethod/PUT
   :trace   HttpMethod/TRACE})

(defn data->method
  "Get appropriate HttpMethod from a number of possible inputs.
   Defaults to GET when nil, translate known keywords and strings,
   or pass a HttpMethod through.

   Throws on other values."
  [m]
  (let [e  (IllegalArgumentException. (str "invalid http method: " m))
        kw (when (string? m) (-> m .toLowerCase keyword))]
    (cond
      (instance? HttpMethod m) m
      (nil? m)                 HttpMethod/GET
      (keyword? m)             (or (get http-methods m) (throw e))
      (string? m)              (or (get http-methods kw) (throw e))
      :else                    (throw e))))

(defn ^HttpHeaders data->headers
  [^HttpHeaders headers input ^String host]
  (when-not (or (empty? input) (map? input))
    (throw (ex-info "HTTP headers should be supplied as a map" {})))
  (.set headers "host" host)
  (when-not (or (get headers :connection)
                (get headers "connection"))
    (.set headers "connection" "close"))
  (doseq [[k v] input]
    (.set headers (name k) (str v)))
  headers)

(defn auth->headers
  "If basic auth is present as a map within a request, add the
   corresponding header."
  [headers {:keys [user password]}]
  (when (and user password)
    (.set headers "Authorization"
          (format "Basic %s" (b64/s->b64 (str user ":" password)))))
  headers)

(defn data->body
  "Add body to a request"
  [request method body]
  (when body
    (let [headers (.headers request)
          bytes   (cond
                    (string? body) (.getBytes body)
                    :else          (throw (ex-info "wrong body type" {})))]
      (-> request .content .clear (.writeBytes bytes))
      (when-not (.get headers "Content-Length")
        (.set headers "Content-Length" (count body))))))

(defn ^URI data->uri
  "Produce a valid URI from arguments found in a request map"
  [^URI uri query]
  (if (seq query)
    (let [qse (QueryStringEncoder. (str uri))]
      (doseq [[k v] query]
        (if (sequential? v)
          (doseq [subv v]
            (.addParam qse (name k) (str subv)))
          (.addParam qse (name k) (str v))))
      (.toUri qse))
    uri))

(defn data->request
  "Produce a valid request from a "
  [{:keys [body headers request-method version query uri auth]}]
  (let [uri     (data->uri uri query)
        version (data->version version)
        method  (data->method request-method)
        path    (str (.getRawPath uri) "?" (.getRawQuery uri))
        request (DefaultFullHttpRequest. version method path)]
    (data->headers (.headers request) headers (.getHost uri))
    (when auth
      (auth->headers (.headers request) auth))
    (data->body request method body)
    request))

(defn build-client
  "Create an http client instance. In most cases you will need only
   one per JVM. You may need several if you want to operate under
   different TLS contexts"
  ([]
   (build-client {}))
  ([{:keys [ssl] :as options}]
   (let [disable-epoll? (-> options :disable-epoll boolean)]
     {:channel (http/optimal-client-channel disable-epoll?)
      :group   (http/make-boss-group options)
      :ssl-ctx (ssl/client-context ssl)})))

(defn async-request
  "Execute an asynchronous HTTP request, produce the response
   asynchronously on the provided `handler` function.

   If no client is provided, create one."
  ([request-map handler]
   (async-request (build-client {}) request-map handler))
  ([{:keys [group channel ssl-ctx max-size]} request-map handler]
   (when-not (:uri request-map)
     (throw (ex-info "malformed request-map, needs :uri key" {})))
   (let [uri    (URI. (:uri request-map))
         scheme (if-let [scheme (.getScheme uri)] (.toLowerCase scheme) "http")
         host   (.getHost uri)
         port   (cond (not= -1 (.getPort uri)) (.getPort uri)
                      (= "http" scheme) 80
                      (= "https" scheme) 443)
         ssl?   (= "https" scheme)
         bs     (doto (Bootstrap.)
                  (.group   group)
                  (.channel channel)
                  (.handler (request-initializer (when ssl? ssl-ctx)
                                                 handler
                                                 (or max-size (* 1024 1024 10)))))
         ch     (some-> bs (.connect host (int port)) .sync .channel)]
     (.writeAndFlush ch (data->request (assoc request-map :uri uri)))
     (-> ch .closeFuture .sync))))

(defn request
  "Execute a request against an asynchronous client. If no client exists, create one.
   Waits for the response and returns it."
  ([request-map]
   (request (build-client {}) request-map))
  ([client request-map]
   (let [p (promise)]
     (async-request client request-map (fn [resp] (deliver p resp)))
     (deref p))))

(defn request-chan
  "Execute a request against an asynchronous client and produce the response on
   a promise channel."
  ([client request-map]
   (let [ch (async/promise-chan)]
     (try
       (async-request request-map
                      (fn [response]
                        (if response
                          (async/put! ch response)
                          (async/close! ch))))
       (catch Throwable t
         (async/put! ch t)))
     ch))
  ([request-map]
   (request-chan (build-client {}) request-map)))

;; Specs
;; =====

;; The URI is the only required part of a request map, if it
;; is a string, it will be parsed to a URI.

(s/def ::uri (s/or :uri #(instance? java.net.URI %) :string string?))

;; We parse request methods liberally, they may be
;; a string, keyword or a Netty HttpMethod instance.
;; A nil request method implies GET.

(def method-re #"(?i)^(connect|delete|get|head|options|patch|post|put|trace)$")

(s/def ::keyword-method #{:connect :delete :get :head :options
                          :patch :post :put :trace})
(s/def ::string-method  #(re-matches method-re %))
(s/def ::request-method (s/or :keyword ::keyword-method
                              :string  ::string-method
                              :method  #(instance? HttpMethod %)))

;; Version specifications are also parsed loosely.
;; nil versions mean HTTP 1.1, strings, keywords and HttpVersion instances
;; are also allowed.

(def version-re #"(?i)^http/1.[01]$")

(s/def ::version (s/or :keyword #{:http-1-1 :http-1-0}
                       :string  (s/and string? #(re-matches version-re %))
                       :version #(instance? HttpVersion %)))

;; Query args are maps of keyword or string to anything.
;; When values are sequential, arguments are looped over. Any other
;; value is coerced to a string.

(s/def ::query (s/map-of (s/or :keyword keyword? :string string?) any?))

;; When auth is present, it should be a map of `:user` and `:password`.

(s/def ::user string?)
(s/def ::password string?)
(s/def ::auth (s/keys :req-un [::user ::password]))

;; Bring everything together in our request map

(s/def ::request (s/keys
                  :req-un [::uri]
                  :opt-un [::request-method ::body ::version ::query ::auth]))

;;
(s/def ::build-client-opts
  (s/keys :opt-un [::ssl ::http/loop-thread-count
                   ::http/disable-epoll]))

(s/def ::client
  (s/keys :req-un [::channel ::group ::ssl-ctx]))

(s/fdef data->request
        :args (s/cat :request ::request)
        :ret #(instance? HttpRequest %))

(s/fdef string->version
        :args (s/cat :version string? :e #(instance? Exception %))
        :ret  #(instance? HttpVersion %))

(s/fdef data->version
        :args (s/cat :version (s/nilable ::version))
        :ret  #(instance? HttpVersion %))

(s/fdef data->method
        :args (s/cat :method (s/nilable ::request-method))
        :ret  #(instance? HttpMethod %))

(s/fdef data->uri
        :args (s/cat :uri #(instance? URI %) :query (s/nilable ::query))
        :ret  #(instance? URI %))

(s/fdef data->headers
        :args (s/cat :headers #(instance? HttpHeaders %)
                     :input (s/nilable ::headers)
                     :host string?)
        :ret  #(instance? HttpHeaders %))

(s/fdef auth->headers
        :args (s/cat :headers #(instance? HttpHeaders %)
                     :auth   (s/nilable ::auth))
        :ret #(instance? HttpHeaders %))

(s/fdef build-client
        :args (s/cat :opts (s/nilable ::build-client-opts))
        :ret  ::client)
