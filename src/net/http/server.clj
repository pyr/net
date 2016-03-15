(ns net.http.server
  "Small wrapper around netty for HTTP servers."
  (:import io.netty.channel.ChannelHandlerContext
           io.netty.channel.ChannelHandlerAdapter
           io.netty.channel.ChannelInboundHandlerAdapter
           io.netty.channel.ChannelOutboundHandlerAdapter
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelOption
           io.netty.channel.ChannelInitializer
           io.netty.channel.ChannelFutureListener
           io.netty.channel.nio.NioEventLoopGroup
           io.netty.channel.socket.nio.NioServerSocketChannel
           io.netty.channel.epoll.Epoll
           io.netty.channel.epoll.EpollServerSocketChannel
           io.netty.channel.epoll.EpollEventLoopGroup
           io.netty.handler.logging.LoggingHandler
           io.netty.handler.logging.LogLevel
           io.netty.handler.codec.http.FullHttpRequest
           io.netty.handler.codec.http.HttpServerCodec
           io.netty.handler.codec.http.HttpMethod
           io.netty.handler.codec.http.HttpHeaders
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.DefaultHttpResponse
           io.netty.handler.codec.http.DefaultHttpContent
           io.netty.handler.codec.http.HttpContent
           io.netty.handler.codec.http.LastHttpContent
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.QueryStringDecoder
           io.netty.bootstrap.ServerBootstrap
           io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           java.nio.charset.Charset))

(defprotocol ContentStream
  (stream [this chunk] [this chunk flush?])
  (close! [this]))

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
  [{:keys [status headers]} version]
  (let [resp (DefaultHttpResponse.
              version
              (HttpResponseStatus/valueOf (int status)))
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

(defn byte-array?
  [x]
  (instance? (Class/forName "[B") x))

(defn chunk->http-object
  [chunk]
  (cond
    (string? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer (.getBytes chunk)))

    (byte-array? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk))

    (instance? HttpContent chunk)
    chunk

    (instance? ByteBuf chunk)
    (DefaultHttpContent. chunk)

    :else
    (throw (ex-info "cannot convert chunk to HttpContent" {}))))

(def ^:dynamic *request-ctx* nil)

(defn stream-body
  []
  (let [ctx         *request-ctx*]
    (reify ContentStream
      (stream [this chunk]
        (stream this chunk true))
      (stream [this chunk flush?]
        (let [obj (chunk->http-object chunk)]
          (if flush?
            (.writeAndFlush ctx obj)
            (.write ctx obj))))
      (close! [this]
        (-> (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT)
            (.addListener ChannelFutureListener/CLOSE))))))

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
            output  (binding [*request-ctx* ctx] (f req))
            content (:body output)]
        (.write ctx (data->response output (.getProtocolVersion msg)))
        (cond
          (nil? content)
          nil

          (string? content)
          (-> (.writeAndFlush ctx (chunk->http-object content))
              (.addListener ChannelFutureListener/CLOSE))

          (satisfies? ContentStream content)
          nil

          :else
          (throw (ex-info "cannot coerce provided content to HttpObject" {}))))

      (finally
        (.release msg)))))

(defn netty-handler
  "Simple netty-handler, everything may happen in
   channel read, since we're expecting a full http request."
  [f]
  (proxy [ChannelInboundHandlerAdapter] []
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (f {:request-method :error :error e}))
    (channelRead [^ChannelHandlerContext ctx ^FullHttpRequest msg]
      (let [callback (request-handler f ctx msg)]
        (callback)))))

(defn initializer
  "Our channel initializer."
  [handler]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [pipeline (.pipeline channel)]
        (.addLast pipeline "codec"      (HttpServerCodec.))
        (.addLast pipeline "aggregator" (HttpObjectAggregator. 1048576))
        (.addLast pipeline "handler"    (netty-handler handler))))))

(def log-levels
  {:debug LogLevel/DEBUG
   :info  LogLevel/INFO
   :warn  LogLevel/WARN})

(defn run-server
  "Prepare a bootstrap channel and start it."
  ([options handler]
   (run-server (assoc options :ring-handler handler)))
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
       (let [bootstrap (doto (ServerBootstrap.)
                         (.option ChannelOption/SO_BACKLOG so-backlog)
                         (.group boss-group)
                         (.channel (if (epoll?)
                                     EpollServerSocketChannel
                                     NioServerSocketChannel))
                         (.childHandler (initializer (:ring-handler options))))
             channel   (-> bootstrap
                           (cond->
                               log-handler (.handler log-handler))
                           (.bind ^String (or (:host options) "127.0.0.1")
                                  (int (or (:port options) 8080)))
                           (.sync)
                           (.channel))]
         (future
           (-> channel .closeFuture .sync))
         (fn []
           (.close channel)
           (.shutdownGracefully boss-group)))))))
