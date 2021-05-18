(ns net.http
  "Functions common to HTTP clients and servers"
  (:require [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [net.ty.buffer      :as buf]
            [net.http.headers   :as headers])
  (:import io.netty.channel.ChannelHandlerContext
           io.netty.channel.ChannelHandlerAdapter
           io.netty.channel.ChannelInboundHandlerAdapter
           io.netty.channel.ChannelOutboundHandlerAdapter
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelOption
           io.netty.channel.ChannelInitializer
           io.netty.channel.ChannelFutureListener
           io.netty.channel.EventLoopGroup
           io.netty.channel.nio.NioEventLoopGroup
           io.netty.channel.socket.SocketChannel
           io.netty.channel.socket.nio.NioServerSocketChannel
           io.netty.channel.socket.nio.NioSocketChannel
           io.netty.channel.epoll.Epoll
           io.netty.channel.epoll.EpollServerSocketChannel
           io.netty.channel.epoll.EpollSocketChannel
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
           io.netty.handler.codec.http.DefaultFullHttpResponse
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpContent
           io.netty.handler.codec.http.LastHttpContent
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.QueryStringDecoder
           io.netty.bootstrap.AbstractBootstrap
           io.netty.bootstrap.ServerBootstrap
           io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           io.netty.buffer.ByteBufAllocator
           io.netty.buffer.UnpooledByteBufAllocator
           java.io.InputStream
           java.io.File
           java.io.FileInputStream
           java.nio.charset.Charset
           java.nio.ByteBuffer))

(defn epoll?
  "Find out if epoll is available on the underlying platform."
  []
  (Epoll/isAvailable))

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

(def log-levels
  "Keyword to level map used as a helper when
   setting up log handlers."
  {:debug LogLevel/DEBUG
   :info  LogLevel/INFO
   :warn  LogLevel/WARN})

(defn http-content
  [^ByteBuf buf]
  (DefaultHttpContent. buf))

(defn make-boss-group
  "Create an event loop group. Try setting up an epoll event loop group
   unless either instructed not to do so or it is no available."
  [{:keys [loop-thread-count disable-epoll]}]
  (if (and (epoll?) (not disable-epoll))
    (EpollEventLoopGroup. (int (or loop-thread-count 1)))
    (NioEventLoopGroup. (int (or loop-thread-count 1)))))

(defn set-log-handler!
  "Add log hander to a bootstrap"
  [^AbstractBootstrap bootstrap {:keys [logging]}]
  (let [handler (when-let [level (some-> logging keyword (get log-levels))]
                  (LoggingHandler. ^LogLevel level))]
    (cond-> bootstrap  handler (.handler handler))))

(defn set-optimal-server-channel!
  "Add optimal channel to a server bootstrap"
  [^AbstractBootstrap bs disable-epoll?]
  (.channel bs (if (and (not disable-epoll?) (epoll?))
                 EpollServerSocketChannel NioServerSocketChannel))
  bs)

(defn optimal-client-channel
  "Figure out which client channel to use"
  [disable-epoll?]
  (if (and (not disable-epoll?) (epoll?))
    EpollSocketChannel NioSocketChannel))

(def logging-re #"(?i)^(debug|info|warn)$")

(defn int->status
  "Get a Netty HttpResponseStatus from a int"
  [status]
  (HttpResponseStatus/valueOf (int status)))

(defn data->response
  "Create a valid HttpResponse from a response map."
  [{:keys [status headers]} version]
  (let [code (int->status status)
        resp (DefaultHttpResponse. version code)
        hmap (.headers resp)]
    (doseq [[k v] headers]
      (.set hmap (name k) v))
    resp))

(defn ->params
  "Create a param map from a Netty QueryStringDecoder"
  [^QueryStringDecoder dx]
  (into
   {}
   (map (fn [[stringk vlist]]
          (let [vs (seq vlist)
                k  (keyword (str stringk))]
            [k (if (= 1 (count vs)) (first vs) vs)])))
   (.parameters dx)))

(defn qs->params
  "Extract parameters from a x-www-form-urlencoded string"
  [qs]
  (->params (QueryStringDecoder. ^String qs false)))

(defn ->request
  "Create a request map from a Netty Http Request"
  [^HttpRequest msg]
  (try
    (let [dx   (QueryStringDecoder. (.uri msg))
          hdrs (headers/as-map (.headers msg))
          p1   (->params dx)]
      {:uri            (.path dx)
       :raw-uri        (.rawPath dx)
       :get-params     p1
       :params         p1
       :request-method (method->data (.method msg))
       :version        (-> msg .protocolVersion .text)
       :headers        hdrs})
    (catch Exception _
      nil)))

(defn protocol-version
  [^HttpRequest msg]
  (.protocolVersion msg))

(defn continue-response
  [^HttpVersion version]
  (DefaultFullHttpResponse. version HttpResponseStatus/CONTINUE))

(def last-http-content
  "Empty Last Http Content"
  LastHttpContent/EMPTY_LAST_CONTENT)

(defn last-http-content?
  "Are we dealing with an instance of LastHttpContent"
  [msg]
  (instance? LastHttpContent msg))

(s/def ::loop-thread-count pos-int?)
(s/def ::disable-epoll boolean?)
(s/def ::logging (s/or :string (s/and string? #(re-matches logging-re %))
                       :keyword #{:debug :info :warn}))

(s/def ::boss-group-opts map?)
(s/def ::log-opts map?)
