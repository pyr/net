(ns net.http.server
  "Small wrapper around netty for HTTP servers."
  (:require [net.ty.buffer         :as buf]
            [net.ty.future         :as f]
            [net.ty.bootstrap      :as bs]
            [net.ty.channel        :as chan]
            [net.http              :as http]
            [clojure.core.async    :as a]
            [net.core.async        :refer [put!]]
            [clojure.tools.logging :refer [debug info warn error]])
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

(def default-chunk-size "" (* 16 1024 1024))

(def default-inbuf "" 10)

(def default-aggregated-length "" (* 1024 1024))

(def ^:dynamic *request-ctx* nil)

(defn int->status
  [status]
  (HttpResponseStatus/valueOf (int status)))

(defn data->response
  [{:keys [status headers]} version]
  (let [code (int->status status)
        resp (DefaultHttpResponse. version code)
        hmap (.headers resp)]
    (doseq [[k v] headers]
      (.set hmap (name k) v))
    resp))

(defn ->params
  [^QueryStringDecoder dx]
  (into
   {}
   (map (fn [[stringk vlist]]
          (let [vs (seq vlist)
                k  (keyword (str stringk))]
            [k (if (pos? (count vs)) (first vs) vs)])))
   (.parameters dx)))

(defn qs->body-params
  [{:keys [headers body]}]
  (when-let [content-type (:content-type headers)]
    (when (.startsWith content-type "application/x-www-form-urlencoded")
      (->params
       (QueryStringDecoder. (http/bb->string body) false)))))

(defn assoc-body-params
  [request]
  (let [bp (qs->body-params request)]
    (cond-> request
      bp (assoc :body-params bp)
      bp (update :params merge bp))))

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
      (instance? InputStream x)))

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
    (throw (IllegalArgumentException. "cannot convert chunk to HttpContent"))))



(defn ->request
  [^HttpRequest msg]
  (let [dx      (QueryStringDecoder. (.getUri msg))
        headers (http/headers (.headers msg))
        p1      (->params dx)]
    {:uri            (.path dx)
     :get-params     p1
     :params         p1
     :request-method (http/method->data (.getMethod msg))
     :version        (-> msg .getProtocolVersion .text)
     :headers        headers}))

(def last-http-content
  ""
  LastHttpContent/EMPTY_LAST_CONTENT)

(f/deflistener write-response-listener
  [this future [^ChannelHandlerContext ctx ^Channel body]]
  (if (or (nil? future) (f/complete? future))
    (let [chunk (a/<!! body)
          msg   (if chunk (chunk->http-object chunk) last-http-content)]
      (-> (chan/write-and-flush! ctx msg)
          (f/add-listener (if chunk this f/close-listener))))
    (a/close! body)))

(defn write-response
  [^ChannelHandlerContext ctx ^HttpVersion version {:keys [body] :as resp}]
  (.writeAndFlush ctx (data->response resp version))
  (let [listener (write-response-listener ctx body)]
    (cond
      (content-chunk? body)
      (-> (chan/write-and-flush! ctx (chunk->http-object body))
          (f/add-close-listener))

      (instance? Channel body)
      (f/operation-complete listener))))

(defn parse-num
  [s]
  (try (Long/parseLong s) (catch Exception _)))

(defn request-length
  [{:keys [headers] :as req}]
  (some-> (:content-length headers) parse-num))

(defn get-response
  [{:keys [request version]} handler ctx]
  (let [resp (handler request)]
    (cond
      (instance? Channel resp)
      (a/take! resp (partial write-response ctx version))

      (map? resp)
      (future
        (write-response ctx version resp))

      :else
      (do
        (error "unhandled response body type" (pr-str resp))
        (throw (IllegalArgumentException. "unhandled response body type"))))))

(defn backpressure-fn
  [ctx]
  (fn [enable?]
    (warn "switching backpressure mode to:" enable?)
    (-> ctx chan/channel .config (.isAutoRead (not enable?)))))

(defn close-fn
  [ctx]
  (fn []
    (-> ctx chan/channel chan/close-future)))

(defmulti write-chunk
  (fn [{:keys [aggregate?]} _ _]
    (if aggregate? ::aggregated ::stream)))

(defmethod write-chunk ::aggregated
  [{:keys [request] :as state} handler ctx msg close?]
  (buf/augment-buffer (:body request) (.content msg))
  (when close?
    (-> state
        (update :request assoc-body-params)
        (get-response handler ctx))))

(defmethod write-chunk ::stream
  [{:keys [request] :as state} handler ctx msg close?]
  (put! (:body request) chunk (backpressure-fn ctx) (close-fn ctx))
  (when close?
    (a/close! (:body request))))


(defn netty-handler
  "This is a stateful, per HTTP session adapter which wraps the user
   supplied function.
   We can use volatiles for keeping track of state due to the thread-safe
   nature of handler adapters."
  ([handler]
   (netty-handler handler {}))
  ([handler {:keys [inbuf agg-length]}]
   (let [inbuf      (or inbuf default-inbuf)
         agg-length (or agg-length default-aggregated-length)
         state      (volatile! {:aggregate? false})]
     (proxy [ChannelInboundHandlerAdapter] []
       (exceptionCaught [^ChannelHandlerContext ctx e]
         (error e "exception caught!")
         (handler {:type           :error
                   :request-method :error
                   :error          e
                   :ctx            ctx}))
       (channelRead [^ChannelHandlerContext ctx msg]
         (cond
           (instance? HttpRequest msg)
           (do
             (vswap! state assoc
                     :version (.getProtocolVersion msg)
                     :request (->request msg))
             (let [length (request-length (:request @state))]
               (if (or (nil? length) (> length agg-length))
                 (do
                   (vswap! state assoc-in [:request :body] (a/chan inbuf))
                   (get-response @state handler ctx))
                 (do
                   (vswap! state
                           #(-> (assoc % :aggregate? true)
                                (assoc-in [:request :body]
                                          (buf/new-buffer length length))))))))

           (buf/last-http-content? msg)
           (write-chunk @state handler ctx msg true)

           (content-chunk? msg)
           (write-chunk @state handler ctx msg false)

           :else
           (do
             (error "unhandled message chunk on body channel")
             (throw (IllegalArgumentException. "unhandled message chunk on body channel")))))))))

(defn body-decoder
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
              (.add out chunk))))))))

(defn initializer
  [{:keys [chunk-size inbuf ring-handler]
    :or   {chunk-size default-chunk-size
           inbuf      default-inbuf}}]
  (let [codec      (HttpServerCodec. 4096 8192 (int chunk-size))
        aggregator (body-decoder chunk-size)
        handler    (netty-handler ring-handler inbuf)]
    (proxy [ChannelInitializer] []
      (initChannel [channel]
        (let [pipeline (.pipeline channel)]
          (.addLast pipeline "codec"      codec)
          (.addLast pipeline "aggregator" aggregator)
          (.addLast pipeline "handler"    handler))))))

(defn set-so-backlog!
  [bootstrap {:keys [so-backlog]}]
  (.option bootstrap ChannelOption/SO_BACKLOG (int (or so-backlog 1024))))

(defn get-host-port
  [{:keys [host port]}]
  [(or host "127.0.0.1") (or port 8080)])

(defn run-server
  "A server handler which for which the body consists of a list of
  chunks"
  ([options handler]
   (run-server (assoc options :ring-handler handler)))
  ([options]
   (let [boss-group  (http/make-boss-group options)
         [host port] (get-host-port options)]
     (try
       (let [bootstrap (doto (ServerBootstrap.)
                         (set-so-backlog! options)
                         (bs/set-group! boss-group)
                         (http/set-optimal-server-channel!)
                         (bs/set-child-handler! (initializer options)))
             channel   (-> bootstrap
                           (http/set-log-handler! options)
                           (bs/bind! host port)
                           (f/sync!)
                           (chan/channel))]
         (future (-> channel (chan/close-future) (f/sync!)))
         (bs/shutdown-fn channel boss-group))))))

(defn chunked?
  [request]
  (instance? Channel (:body request)))

(defn async-handler
  [{:keys [headers body] :as request}]
  (if (chunked? request)
    (let [rbody (a/chan 100)
          type  (:content-type headers "text/plain")]
      (info "handler found chunked request")
      (a/pipe body rbody)
      {:status  200
       :headers {"Content-Type"         type
                 "X-Content-Aggregated" "false"
                 "Transfer-Encoding"    "chunked"}
       :body    rbody})
    (let [params (:body-params request)]
      (info "handler found aggregated request")
      {:status  200
       :headers {"Content-Type"         "text/plain"
                 "X-Content-Aggregated" "true"
                 "Transfer-Encoding"    "chunked"}
       :body    body})))
