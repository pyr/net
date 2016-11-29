(ns net.http.server
  "Small wrapper around netty for HTTP servers."
  (:require [net.ty.buffer :as buf]
            [net.ty.future :as f]
            [net.ty.bootstrap :as bs]
            [clojure.tools.logging :refer [debug info error]]
            [clojure.core.async :as a])
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
           io.netty.handler.codec.http.DefaultLastHttpContent
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpContent
           io.netty.handler.codec.http.LastHttpContent
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.QueryStringDecoder
           io.netty.bootstrap.ServerBootstrap
           io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           io.netty.buffer.ByteBufAllocator
           io.netty.buffer.UnpooledByteBufAllocator
           java.io.InputStream
           java.io.File
           java.io.FileInputStream
           java.nio.charset.Charset
           java.nio.ByteBuffer
           clojure.core.async.impl.protocols.Channel))

(defprotocol ContentStream
  (stream [this chunk] [this chunk flush?])
  (close! [this]))

(defprotocol ChunkedRequestHandler
  (report-failure [this failure])
  (request [this req])
  (content [this chunk])
  (last-content [this chunk]))

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

(defn input-stream-chunk
  [^InputStream is]
  (let [buf (Unpooled/buffer (.available is))]
    (loop [len (.available is)]
      (when (pos? len)
        (.writeBytes buf is len)
        (recur (.available is))))
    buf))

(defn file-chunk
  [^File f]
  (input-stream-chunk (FileInputStream. f)))

(defn content-chunk?
  [x]
  (or (string? x)
      (byte-array? x)
      (instance? ByteBuf x)
      (instance? ByteBuffer x)
      (instance? File x)
      (instance? HttpContent x)
      (instance? InputStream x)
      (instance? Channel x)))

(defn chunk->http-object
  [chunk]
  (cond

    (instance? InputStream chunk)
    (DefaultHttpContent. (input-stream-chunk chunk))

    (instance? File chunk)
    (DefaultHttpContent. (file-chunk chunk))

    (string? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer (.getBytes chunk)))

    (byte-array? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk))

    (instance? HttpContent chunk)
    chunk

    (instance? ByteBuf chunk)
    (DefaultHttpContent. chunk)

    (instance? ByteBuffer chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk))

    :else
    (throw (ex-info "cannot convert chunk to HttpContent" {}))))

(def ^:dynamic *request-ctx* nil)

(defn content-stream
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

(defn ->request
  [^HttpRequest msg]
  (let [dx      (QueryStringDecoder. (.getUri msg))
        headers (headers (.headers msg))
        p1      (->params dx)]
    {:uri            (.path dx)
     :get-params     p1
     :params         p1
     :request-method (method->data (.getMethod msg))
     :version        (-> msg .getProtocolVersion .text)
     :headers        headers}))

(defn request-handler
  "Capture context and msg and yield a closure
   which generates a response.

   The closure may be called at once or submitted to a pool."
  [f ^ChannelHandlerContext ctx ^FullHttpRequest msg]
  (fn []
    (try
      (let [body    (bb->string (.content msg))
            bparams (some-> msg (body-params headers body) ->params)
            req     (-> (->request msg)
                        (assoc :body-params bparams)
                        (update :params merge bparams))
            output  (binding [*request-ctx* ctx] (f req))
            content (:body output)]
        (f/with-result [ftr (.write ctx (data->response output (.getProtocolVersion msg)))]
          (println "successfully wrote data"))
        (cond
          (nil? content)
          nil

          (content-chunk? content)
          (-> (.writeAndFlush ctx (chunk->http-object content))
              (.addListener ChannelFutureListener/CLOSE))

          (satisfies? ContentStream content)
          nil

          :else
          (do
            (error "cannot coerce provided content to HttpObject")
            (throw (ex-info "cannot coerce provided content to HttpObject" {})))))

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

(defn write-response
  [^ChannelHandlerContext ctx ^HttpVersion version {:keys [body] :as resp}]
  (.writeAndFlush ctx (data->response resp version))
  (let [listener (reify io.netty.channel.ChannelFutureListener
                   (operationComplete [this future]
                     (let [chunk (a/<!! body)]
                       (if chunk
                         (.addListener (.writeAndFlush ctx (chunk->http-object chunk)) this)
                         (.addListener (.writeAndFlush ctx LastHttpContent/EMPTY_LAST_CONTENT)
                                       ChannelFutureListener/CLOSE)))))]
    (cond
      (satisfies? ContentStream body)
      ::content-stream-handles-output

      (content-chunk? content)
      (-> (.writeAndFlush ctx (chunk->http-object content))
          (.addListener ChannelFutureListener/CLOSE))

      (instance? Channel body)
      (a/go
        (.operationComplete listener nil)))))

(defn chunked-netty-handler
  ([handler]
   (chunked-netty-handler handler 10))
  ([handler inbuf]
   (let [body (a/chan inbuf)]
     (proxy [ChannelInboundHandlerAdapter] []
       (exceptionCaught [^ChannelHandlerContext ctx e]
         (handler {:type           :error
                   :request-method :error
                   :error          e
                   :ctx            ctx}))
       (channelRead [^ChannelHandlerContext ctx msg]
         (cond
           (instance? HttpRequest msg)
           (binding [*request-ctx* ctx]
             (let [version (.getProtocolVersion msg)
                   resp    (handler (assoc (->request msg) :body body))]
               (if (instance? Channel resp)
                 (a/take! resp (partial write-response ctx version))
                 (write-response ctx version resp))))

           (buf/last-http-content? msg)
           (do
             (a/put! body msg)
             (a/close! body))

           :else
           (a/put! body msg)))))))

(defn initializer
  "Our channel initializer."
  [handler]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [pipeline (.pipeline channel)]
        (.addLast pipeline "codec"      (HttpServerCodec.)
        (.addLast pipeline "aggregator" (HttpObjectAggregator. 1048576))
        (.addLast pipeline "handler"    (netty-handler handler)))))))

(defn chunked-decoder
  [max-size]
  (let [content (buf/buffer-holder)]
    (proxy [io.netty.handler.codec.MessageToMessageDecoder] []
      (isSharable []
        false)
      (decode [ctx msg out]
        (try
          (cond
            (instance? HttpRequest msg)
            (.add out msg)

            (buf/last-http-content? msg)
            (doseq [chunk (buf/release-contents max-size content msg)]
              (.add out chunk))

            :else
            (when-let [chunk (buf/update-content max-size content msg)]
              (.add out chunk)))
          (finally))))))

(defn chunked-initializer
  [chunk-size inbuf handler]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [pipeline (.pipeline channel)]
        (.addLast pipeline "codec" (HttpServerCodec. (int 4096)
                                                     (int 8192)
                                                     (int chunk-size)))
        (.addLast pipeline "aggregator" (chunked-decoder chunk-size))
        (.addLast pipeline "handler" (chunked-netty-handler handler inbuf))))))

(def log-levels
  {:debug LogLevel/DEBUG
   :info  LogLevel/INFO
   :warn  LogLevel/WARN})

(defn optimal-channel
  []
  (if (epoll?) EpollServerSocketChannel NioServerSocketChannel))

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
                         (.channel (optimal-channel))
                         (.childHandler (initializer (:ring-handler options))))
             channel   (-> bootstrap
                           (cond-> log-handler (.handler log-handler))
                           (bs/bind! (or (:host options) "127.0.0.1")
                                     (or (:port options) 8080))
                           (.sync)
                           (.channel))]
         (future
           (-> channel .closeFuture .sync))
         (fn []
           (.close channel)
           (.shutdownGracefully boss-group)))))))

(defn run-chunked-server
  "A server handler which for which the body consists of a list of
  chunks"
  ([options handler]
   (run-chunked-server (assoc options :ring-handler handler)))
  ([options]
   (let [chunk-size   (or (:chunk-size options) (* 16 1024 1024))
         log-handler  (when-let [level (some-> (:logging options)
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
                         (.channel (optimal-channel))
                         (.childHandler (chunked-initializer chunk-size
                                                             (or (:inbuf options) 10)
                                                             (:ring-handler options))))
             channel   (-> bootstrap
                           (cond-> log-handler (.handler log-handler))
                           (bs/bind! (or (:host options) "127.0.0.1")
                                     (or (:port options) 8080))
                           (.sync)
                           (.channel))]
         (future
           (-> channel .closeFuture .sync))
         (fn []
           (.close channel)
           (.shutdownGracefully boss-group)))))))

(defn async-chunk-handler
  [{:keys [headers] :as request}]
  (debug (pr-str headers))
  (a/go
    (let [body (a/chan 10)
          type (:content-type headers "text/plain")]
      (a/pipe (:body request) body)
      {:status  200
       :headers {"Content-Type" type
                 "Transfer-Encoding" "chunked"}
       :body body})))
