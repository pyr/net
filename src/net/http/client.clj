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
           io.netty.handler.codec.http.FullHttpRequest
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpMethod
           io.netty.handler.codec.http.HttpHeaders
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.DefaultFullHttpRequest
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.QueryStringDecoder
           io.netty.buffer.Unpooled
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

(def method->data
  "Yield a keyword representing an HTTP method."
  {HttpMethod/CONNECT :connect
   HttpMethod/DELETE  :delete
   HttpMethod/GET     :get
   HttpMethod/HEAD    :head
   HttpMethod/OPTIONS :options
   HttpMethod/PATCH   :patch
   HttpMethod/POST    :post
   HttpMethod/PUT     :put
   HttpMethod/TRACE   :trace})

(defn headers
  "Get a map out of netty headers."
  [^HttpHeaders headers]
  (into
   {}
   (for [[^String k ^String v] (-> headers .entries seq)]
     [(-> k .toLowerCase keyword) v])))

(defn data->response
  "Create a netty full http response from a map."
  [{:keys [status body headers]} version]
  (let [resp (DefaultFullHttpResponse.
               version
               (HttpResponseStatus/valueOf (int status))
               (Unpooled/wrappedBuffer (.getBytes body)))
        hmap (.headers resp)]
    (doseq [[k v] headers]
      (.set hmap (name k) v))
    resp))

(defn ->params
  [^QueryStringDecoder dx]
  (reduce
   merge {}
   (for [[k vlist] (.parameters dx)
         :let [vs (seq vlist)]]
     [(keyword (str k)) (if (< 1 (count vs)) vs (first vs))])))

(defn body-params
  [^FullHttpRequest msg headers body]
  (when-let [content-type (:content-type headers)]
    (when (.startsWith content-type "application/x-www-form-urlencoded")
      (QueryStringDecoder. body false))))

(defn request-handler
  "Capture context and msg and yield a closure
   which generates a response.

   The closure may be called at once or submitted to a pool."
  [f ^ChannelHandlerContext ctx ^FullHttpRequest msg]
  (fn []
    (try
      (let [headers (headers (.headers msg))
            body    (bb->string (.content msg))
            dx      (QueryStringDecoder. (.getUri msg))
            p1      (->params dx)
            p2      (some-> msg (body-params headers body) ->params)
            req     {:uri            (.path dx)
                     :get-params     p1
                     :body-params    p2
                     :params         (merge p1 p2)
                     :request-method (method->data (.getMethod msg))
                     :version        (-> msg .getProtocolVersion .text)
                     :headers        headers
                     :body           body}
            resp (data->response (f req) (.getProtocolVersion msg))]
        (-> (.writeAndFlush ctx resp)
            (.addListener ChannelFutureListener/CLOSE)))
      (finally
        ;; This actually releases the content
        (.release msg)))))

(defn netty-handler
  "Simple netty-handler, everything may happen in
   channel read, since we're expecting a full http request."
  [f]
  (proxy [ChannelInboundHandlerAdapter] []
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (f {:request-method })
      (error e "http server exception caught"))
    (channelRead [^ChannelHandlerContext ctx ^FullHttpRequest msg]
      (let [callback (request-handler f ctx msg)]
        (callback)))))

(defn ssl-ctx-handler
  []
  (let [ctx ]))
(defn initializer
  "Our channel initializer."
  [ssl-ctx-handler handler]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [pipeline (.pipeline channel)]
        (when ssl-ctx-handler
          (.addLast pipeline "ssl" (ssl-ctx-handler channel)))
        (.addLast pipeline "ssl"        )
        (.addLast pipeline "codec"      (HttpServerCodec.))
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
  [headers input]
  (when-not (map? input)
    (throw (ex-info "HTTP headers should be supplied as a map" {})))
  (doseq [[k v] input]
    (.set headers (name k) (str v))))

(defn data->body
  [request method body params]

  )

(defn data->req
  [{:keys [body headers method version uri params]}]
  (let [version (data->version version)
        method  (data->method method)
        request (DefaultFullHttpRequest. version method uri)]
    (data->headers (.headers request) headers)
    (data->body request method body params)))

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
                        (NioEventLoopGroup.   thread-count))]
     {:group   boss-group
      :channel (if use-epoll? EpollSocketChannel NioSocketChannel)})))

(defn request
  ([request-map]
   (request (build-client) request-map))
  ([{:keys [group channel]} request-map]
   (when-not (:url request-map)
     (throw (ex-info "malformed request-map, needs :url key" {})))
   (let [uri    (URI. (:url request-map))
         scheme (if-let [scheme (.getScheme uri) (.toLowerCase scheme) "http"])
         port   (cond (not= -1 (.getPort uri) (.getPort uri))
                      (= "http" scheme) 80
                      (= "https" scheme) 443)
         ssl?   (= "https" scheme)
         bootstrap (doto (Bootstrap.)
                     (.group   group)
                     (.channel channel)
                     (.handler)
                     (.childHandler (request-initalizer ssl?))
                     )
         ])
   ))

(defn build-client
  "Prepare a bootstrap channel and start it."
  ([]
   (build-client {}))
  ([options]
   (let [log-handler  (when-let [level (some-> (:logging options)
                                               keyword
                                               (get log-levels))]
                        (LoggingHandler. level))
         thread-count (or (:loop-thread-count options) 1)
         boss-group   (if (and (epoll?) (not (:disable-epoll options)))
                        (EpollEventLoopGroup. thread-count)
                        (NioEventLoopGroup.   thread-count))
         so-backlog   (int (or (:so-backlog options) 1024))]
     (try
       (let [bootstrap (doto (Bootstrap.)
                         (.group boss-group)
                         (.channel (if (epoll?)
                                     EpollServerSocketChannel
                                     NioServerSocketChannel))
                         (.handler )
                         (.childHandler (initializer (:ring-handler options))))
             channel   (-> bootstrap
                           (cond-> log-handler (.handler log-handler))
                           (.bind ^String (or (:host options) "127.0.0.1")
                                  (int (or (:port options) 8080)))
                           (.sync)
                           (.channel))])))))
