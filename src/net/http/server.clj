(ns net.http.server
  "Small wrapper around netty for HTTP servers.
   The objective here is to be mostly compatible
   with the vast number of available clojure HTTP
   server implementations.

   In particular, we take inspiration from and try
   to be mostly compatible with [jet](https://github.com/mpenet/jet).

   The idea is that it should be feasible to write handlers that
   behave like synchronous ones.

   The main function in this namespace is `run-server`"
  (:require [net.ty.buffer         :as buf]
            [net.ty.future         :as f]
            [net.ty.bootstrap      :as bs]
            [net.ty.channel        :as chan]
            [net.http              :as http]
            [clojure.core.async    :as a]
            [clojure.spec          :as s]
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
           io.netty.handler.codec.http.HttpUtil
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
            [k (if (pos? (count vs)) (first vs) vs)])))
   (.parameters dx)))

(defn qs->body-params
  "Extract body parameters from a body when application"
  [{:keys [headers body]}]
  (when-let [content-type (:content-type headers)]
    (when (.startsWith content-type "application/x-www-form-urlencoded")
      (->params
       (QueryStringDecoder. (http/bb->string body) false)))))

(defn assoc-body-params
  "Add found query and body parameters (when applicable) to a request map"
  [request]
  (let [bp (qs->body-params request)]
    (cond-> request
      bp (assoc :body-params bp)
      bp (update :params merge bp))))

(defn input-stream-chunk
  "Fill up a ByteBuf with the contents of an input stream"
  [^InputStream is]
  (let [buf (Unpooled/buffer (.available is))]
    (loop [len (.available is)]
      (when (pos? len)
        (.writeBytes buf is len)
        (recur (.available is))))
    buf))

(defn file-chunk
  "Create an input stream chunk from a File"
  [^File f]
  (input-stream-chunk (FileInputStream. f)))

(defprotocol ChunkEncoder
  "A simple encoding protocol for chunks"
  (chunk->http-object [chunk] "Convert Chunk to http-object"))

;;
;; Provide ChunkEncoder implementation for common types,
;; This allows net to be flexible
(extend-protocol ChunkEncoder
  (Class/forName "[B")
  (chunk->http-object [chunk]
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk)))

  ByteBuffer
  (chunk->http-object [chunk]
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk)))

  ByteBuf
  (chunk->http-object [chunk]
    (DefaultHttpContent. chunk))

  InputStream
  (chunk->http-object [chunk]
    (DefaultHttpContent. (input-stream-chunk chunk)))

  File
  (chunk->http-object [chunk]
    (DefaultHttpContent. (file-chunk chunk)))

  String
  (chunk->http-object [chunk]
    (Unpooled/wrappedBuffer (.getBytes chunk "UTF8")))

  HttpContent
  (chunk->http-object [chunk] chunk))

(defn content-chunk?
  "Predicate to check for ChunkEncoder compliance"
  [x]
  (satisfies? ChunkEncoder x))

(defn ->request
  "Create a request map from a Netty Http Request"
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
  "Empty Last Http Content"
  LastHttpContent/EMPTY_LAST_CONTENT)

;; A Netty ChannelFutureListener used when writing in chunks
;; read from a body.
;;
;; The first time, the listener will be called with a nil future
;; meaning that a payload can be read from the body channel and sent-out
;; re-using the same listener for completion. If no chunk could be read
;; from the body channel, close the channel.
;;
;; When called with an actual future, apply the same logic, re-using the listener
;; up to the point where no more chunks have to be sent out or an error occurs.
(f/deflistener write-response-listener
  [this future [^ChannelHandlerContext ctx ^Channel body]]
  (if (or (nil? future) (f/complete? future))
    (let [chunk (a/<!! body)
          msg   (if chunk (chunk->http-object chunk) last-http-content)]
      (-> (chan/write-and-flush! ctx msg)
          (f/add-listener (if chunk this f/close-listener))))
    (a/close! body)))

(defn write-response
  "Write an HTTP response out to a Netty Context"
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
  "Parse a long integer, returning nil on failure"
  [s]
  (try (Long/parseLong s) (catch Exception _)))

(defn request-length
  "Try extracting a request length if available"
  [{:keys [headers] :as req}]
  (some-> (:content-length headers) parse-num))

(defn get-response
  "When an aggregated request is done buffereing,
   Execute the handler on it and publish the response."
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
  "Stop automatically reading from the body channel when we are signalled
   for backpressure."
  [ctx]
  (fn [enable?]
    (warn "switching backpressure mode to:" enable?)
    (-> ctx chan/channel .config (.isAutoRead (not enable?)))))

(defn close-fn
  "A closure over a context that will close it when called."
  [ctx]
  (fn []
    (-> ctx chan/channel chan/close-future)))

(defmulti write-chunk
  "Multimethod used to write into the request's body."
  (fn [{:keys [aggregate?]} _ _ _ _]
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
  (put! (:body request) msg (backpressure-fn ctx) (close-fn ctx))
  (when close?
    (a/close! (:body request))))


(defn netty-handler
  "This is a stateful, per HTTP session adapter which wraps the user
   supplied function.
   We can use volatiles for keeping track of state due to the thread-safe
   nature of handler adapters."
  ([handler]
   (netty-handler handler {}))
  ([handler {:keys [inbuf aggregate-length]}]
   (let [inbuf      (or inbuf default-inbuf)
         agg-length (or aggregate-length default-aggregated-length)
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
             ;; 100-Continue
             (when (HttpUtil/is100ContinueExpected msg)
               (.write ctx (DefaultFullHttpResponse. HttpVersion/HTTP_1_1
                                                     HttpResponseStatus/CONTINUE)))
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
  "To simplify the life of downstream consumers, we
   create fixed-size chunks. For example, for an input
   payload of 42M and a chunk size of 16M, the handler
   function will be fed a channel containing 3 elements:
   2 16M chunks and a 10M one."
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
  "An initializer is a per-connection context creator.
   For each incoming connections, the HTTP server codec is used,
   bodies are aggregated up to a certain size and then handed over
   to the provided handler"
  [{:keys [chunk-size ring-handler]
    :or   {chunk-size default-chunk-size}
    :as   opts}]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [handler-opts (select-keys opts [:inbuf :aggregate-length])
            codec        (HttpServerCodec. 4096 8192 (int chunk-size))
            aggregator   (body-decoder chunk-size)
            handler      (netty-handler ring-handler handler-opts)
            pipeline     (.pipeline channel)]
        (.addLast pipeline "codec"      codec)
        (.addLast pipeline "aggregator" aggregator)
        (.addLast pipeline "handler"    handler)))))

(defn set-so-backlog!
  "Adjust Bootstrap socket backlog"
  [bootstrap {:keys [so-backlog]}]
  (.option bootstrap ChannelOption/SO_BACKLOG (int (or so-backlog 1024))))

(defn get-host-port
  "Extract host and port from a server options map, providing defaults"
  [{:keys [host port]}]
  [(or host "127.0.0.1") (or port 8080)])

(defn run-server
  "Create and run an HTTP server handler.
   HTTP server handlers rely on a handler function which must be provided
   separately in the 2-arity version or as the `:ring-handler` key in
   the options map in the 1-arity version.

   Ring handler is a function of one argument, a correctly formed HTTP
   request of the following form (see `::request` spec for full form):

   ```
   {:request-method <method>
    :uri            <uri>
    :version        <version>
    :headers        <headers>
    :get-params     <map>
    :params         <map>
    :body           <buf-or-channel>}
   ```

   When body is a channel, it will produce `ByteBuf` instances of up to
   the options `chunk-size` value.

   The function should produce either a channel or map as a response.
   When the response is a channel, a single value will be consumed from
   it: the response map.

   The response map should be of the form (see `::response` spec for
   full form):

   ```
   {:status         <http-status>
    :headers        <headers>
    :body           <buf-or-channel>}
   ```

   When body is a channel, values will be consumed from it and sent out
   until it is closed. Otherwise, the contents will be sent out directly.

   The options map is of the following form:

   ```
   {:loop-thread-count <threadcount>
    :disable-epoll     <boolean>
    :host              <host>
    :port              <port>
    :chunk-size        <chunk-size>
    :inbuf             <input-channel-buffer>
    :so-backlog        <backlog>
    :aggregate-length  <body-aggregate-max-size>}
   ```

   `run-server` returns a function of no args which when called will shut
   down the server gracefully.
   "
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
