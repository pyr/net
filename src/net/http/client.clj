(ns net.http.client
  "Small wrapper around netty for HTTP clients."
  (:require [net.codec.b64 :as b64]
            [net.ssl       :as ssl]
            [net.http      :as http])
  (:import io.netty.bootstrap.Bootstrap
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

(defn epoll?
  "Find out if epoll is available on the underlying platform."
  []
  (Epoll/isAvailable))

(defn bb->string
  "Convert a ByteBuf to a UTF-8 String."
  [bb]
  (.toString bb (Charset/forName "UTF-8")))

(defn headers
  "Get a map out of netty headers."
  [^HttpHeaders headers]
  (into
   {}
   (for [[^String k ^String v] (-> headers .entries seq)]
     [(-> k .toLowerCase keyword) v])))

(defn response-handler
  "Capture context and msg and yield a closure
   which generates a response.

   The closure may be called at once or submitted to a pool."
  [f ^ChannelHandlerContext ctx ^FullHttpResponse msg]
  (fn []
    (try
      (let [headers (headers (.headers msg))
            body    (bb->string (.content msg))
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
  [ssl-ctx channel]
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

(def log-levels
  {:debug LogLevel/DEBUG
   :info  LogLevel/INFO
   :warn  LogLevel/WARN})

(defn data->version
  [v]
  (let [e (ex-info (str "invalid http version supplied: " v) {})]
    (cond
      (nil? v)     HttpVersion/HTTP_1_1
      (string? v)  (cond (re-matches #"(?i)http/1.1" v) HttpVersion/HTTP_1_1
                         (re-matches #"(?i)http/1.0" v) HttpVersion/HTTP_1_0
                         :else                          (throw e))
      :else        (throw e))))

(defn data->method
  [m]
  (let [methods {:connect HttpMethod/CONNECT
                 :delete  HttpMethod/DELETE
                 :get     HttpMethod/GET
                 :head    HttpMethod/HEAD
                 :options HttpMethod/OPTIONS
                 :patch   HttpMethod/PATCH
                 :post    HttpMethod/POST
                 :put     HttpMethod/PUT
                 :trace   HttpMethod/TRACE}
        e       (ex-info (str "invalid HTTP method supplied: " m) {})]
    (cond
      (keyword? m) (or (get methods m) (throw e))
      (string? m)  (or (get methods (-> m .toLowerCase keyword)) (throw e))
      (nil? m)     HttpMethod/GET
      :else        (throw e))))

(defn data->headers
  [headers input host]
  (when-not (or (empty? input) (map? input))
    (throw (ex-info "HTTP headers should be supplied as a map" {})))
  (.set headers "host" host)
  (when-not (or (get headers :connection)
                (get headers "connection"))
    (.set headers "connection" "close"))
  (doseq [[k v] input]
    (.set headers (name k) (str v))))

(defn auth->headers
  [headers {:keys [user password]}]
  (when (and user password)
    (.set headers "Authorization"
          (format "Basic %s" (b64/s->b64 (str user ":" password))))))

(defn data->body
  [request method body]
  (when body
    (let [headers (.headers request)
          bytes   (cond
                    (string? body) (.getBytes body)
                    :else          (throw (ex-info "wrong body type" {})))]
      (-> request .content .clear (.writeBytes bytes))
      (when-not (.get headers "Content-Length")
        (.set headers "Content-Length" (count body))))))

(defn data->uri
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
  ([]
   (build-client {}))
  ([{:keys [ssl] :as options}]
   {:channel (http/optimal-client-channel)
    :group   (http/make-boss-group options)
    :ssl-ctx (ssl/client-context ssl)}))

(defn async-request
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
  ([request-map]
   (request (build-client {}) request-map))
  ([client request-map]
   (let [p (promise)]
     (async-request client request-map (fn [resp] (deliver p resp)))
     (deref p))))
