(ns net.http.client
  "Small wrapper around netty for HTTP clients."
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
           io.netty.handler.ssl.SslContextBuilder
           io.netty.handler.ssl.SslContext
           java.net.URI
           java.nio.charset.Charset))

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
  [ssl-ctx handler]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [pipeline (.pipeline channel)]
        (when ssl-ctx
          (.addLast pipeline "ssl" (ssl-ctx-handler ssl-ctx channel)))
        (.addLast pipeline "codec"      (HttpClientCodec.))
        (.addLast pipeline "aggregator" (HttpObjectAggregator. 1048576))
        (.addLast pipeline "handler"    (netty-handler handler))))))

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

(defn data->body
  [request method body]
  (when body
    (let [bytes (cond
                  (string? body) (.getBytes body)
                  :else          (throw (ex-info "wrong body type" {})))]
      (-> request .content .clear .writeBytes bytes))))

(defn data->request
  [{:keys [body headers request-method version uri]}]
  (let [version (data->version version)
        method  (data->method request-method)
        path    (str (.getRawPath uri) "?" (.getRawquery uri))
        request (DefaultFullHttpRequest. version method uri)]
    (data->headers (.headers request) headers (.getHost uri))
    (data->body request method body)
    request))

(defn build-client
  ([]
   (build-client {}))
  ([options]
   (let [use-epoll?   (and (epoll?) (not (:disable-epoll options)))
         log-handler  (when-let [level (some-> (:logging options)
                                               (keyword)
                                               (get log-levels))]
                        (LoggingHandler. level))
         thread-count (or (:loop-thread-count options) 1)
         boss-group   (if use-epoll?
                        (EpollEventLoopGroup. thread-count)
                        (NioEventLoopGroup.   thread-count))
         ctx          (.build (SslContextBuilder/forClient))]
     {:group   boss-group
      :ssl-ctx ctx
      :channel (if use-epoll? EpollSocketChannel NioSocketChannel)})))

(defn request
  ([request-map handler]
   (request (build-client {}) request-map handler))
  ([{:keys [group channel ssl-ctx]} request-map handler]
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
                  (.handler (request-initializer (when ssl? ssl-ctx) handler)))
         ch     (some-> bs (.connect host (int port)) .sync .channel)]
     (.writeAndFlush ch (data->request (assoc request-map :uri uri)))
     (-> ch .closeFuture .sync))))
