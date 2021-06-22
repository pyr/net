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
            [net.http.chunk        :as chunk]
            [net.core.concurrent   :as nc]
            [net.core.async        :as na]
            [clojure.core.async    :as a]
            [clojure.spec.alpha    :as s])
  (:import io.netty.channel.ChannelHandlerContext
           io.netty.channel.ChannelInboundHandlerAdapter
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelOption
           io.netty.channel.ChannelInitializer
           io.netty.handler.codec.http.DefaultFullHttpResponse
           io.netty.handler.codec.http.HttpServerCodec
           io.netty.handler.codec.http.HttpUtil
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.timeout.IdleStateHandler
           io.netty.handler.timeout.IdleStateEvent
           io.netty.bootstrap.AbstractBootstrap
           clojure.core.async.impl.protocols.Channel))

(def default-chunk-size "" (* 1024 1024))
(def default-inbuf "" 100)

(defn write-raw-response
  "Write an HTTP response out to a Netty Context"
  [^ChannelHandlerContext ctx resp body]
  (f/with-result [ftr (chan/write-and-flush! ctx resp)]
    (cond
      (chunk/content-chunk? body)
      (f/with-result [ftr (chan/write-and-flush! ctx (chunk/chunk->http-object body))]
        (chan/close! (chan/channel ftr)))

      (instance? Channel body)
      (chunk/start-write-listener ctx body)

      :else
      (f/with-result [ftr (chan/write-and-flush! ctx http/last-http-content)]
        (chan/close! (chan/channel ftr))))))

(defn write-response
  [ctx version {:keys [body] :as resp}]
  (when resp
    (write-raw-response ctx
                        (http/data->response resp version)
                        body)))

(defn get-response
  "When an aggregated request is done buffereing,
   Execute the handler on it and publish the response."
  [{:keys [request version]} handler ctx executor]
  (nc/with-executor executor
    (try
      (let [resp     (handler request)
            respond! (partial write-response ctx version)]
        (cond
          (instance? Channel resp)
          (a/take! resp respond!)

          (map? resp)
          (respond! resp)

          :else
          (throw (IllegalArgumentException. "unhandled response type")))))))

(defn send-100-continue-fn
  [^ChannelHandlerContext ctx ^HttpRequest msg]
  (let [version (http/protocol-version msg)]
    #(chan/write-and-flush! ctx (http/continue-response version))))

(def request-data-keys
  "Keys to use when matching requests against pure data"
  [:request-method :headers :uri :raw-uri :params :get-params])

(def bad-request
  "Data representation of a bad request as given by
   Netty's ObjectDecoder class"
  {:request-method :get
   :headers        {}
   :uri            "/bad-request"
   :raw-uri        "/bad-request"
   :params         {}
   :get-params     {}})

(defn bad?
  [request]
  (= bad-request (select-keys request request-data-keys)))

(defn close-channel [ctx ch draining]
  (chan/close-future (chan/channel ctx))
  (when (some? ch)
    (if draining
      (na/close-draining ch buf/ensure-released)
      (a/close! ch))))

(defn notify-bad-request!
  [handler msg ctx ch e]
  (when (satisfies? buf/Bufferizable msg)
    (buf/release (buf/as-buffer msg)))
  (close-channel ctx ch false)
  (handler {:type           :error
            :error          (if (string? e)
                              (IllegalArgumentException. ^String e)
                              e)
            :request-method :error
            :ctx            ctx}))

(defn reject-invalid-request [ctx version]
  (let [resp (DefaultFullHttpResponse. version
                                       HttpResponseStatus/REQUEST_URI_TOO_LONG)]
    (f/with-result [ftr (chan/write-and-flush! ctx resp)]
      (chan/close! (chan/channel ftr)))))

(defn ^ChannelHandler netty-handler
  "This is a stateful, per HTTP session adapter which wraps the user
   supplied function.
   We can use volatiles for keeping track of state due to the thread-safe
   nature of handler adapters."
  [handler {:keys [inbuf executor channel]
            :or   {inbuf default-inbuf}}]
  (let [state (volatile! {})]
    (proxy [ChannelInboundHandlerAdapter] []
      (userEventTriggered [^ChannelHandlerContext ctx ev]
        (if (instance? IdleStateEvent ev)
          (close-channel ctx (:chan @state) true)
          (.fireUserEventTriggered ctx ev)))
      (exceptionCaught [^ChannelHandlerContext ctx e]
        (let [ch (:chan @state)]
          (notify-bad-request! handler nil ctx ch e)
          (close-channel ctx ch true)))
      (channelRegistered [^ChannelHandlerContext ctx]
        (let [chan-config (.config (.channel ctx))]
          (doseq [[k v] channel :let [copt (net.ty.bootstrap/->channel-option k)]]
            (.setOption chan-config copt (if (number? v) (int v) v)))))
      (channelInactive [^ChannelHandlerContext ctx]
        (close-channel ctx (:chan @state) true))
      (channelRead [^ChannelHandlerContext ctx msg]
        (let [chan (:chan @state)
              close-resources (fn []
                                (when (satisfies? buf/Bufferizable msg)
                                  (buf/release (buf/as-buffer msg)))
                                (chan/close! ctx)
                                (when chan
                                  (a/close! chan)))]
          (cond
            ;; When it's a new HTTP request, we are creating a core/async and we are passing it to the handler.
            (instance? HttpRequest msg)
            (let [request (http/->request msg)
                  version (http/protocol-version msg)]

              (cond
                (= :net.http/unparsable-request request)
                (reject-invalid-request ctx version)

                (bad? request)
                (notify-bad-request! handler msg ctx nil "Trailing content on request")

                :else
                (let [in      (a/chan inbuf)
                      bodyreq (assoc request
                                     :body in
                                     :is-100-continue-expected? (HttpUtil/is100ContinueExpected msg)
                                     :send-100-continue! (send-100-continue-fn ctx msg))]
                  (get-response (vswap! state assoc
                                        :version version
                                        :chan in
                                        :request bodyreq)
                                handler ctx executor))))

            ;; When it's data, we are sending the content to the previously opened core/async channel.
            (and chan (chunk/content-chunk? msg))
            (chunk/enqueue chan ctx msg)

            ;; When it's data and the core/async channel is not present, we close the resources
            ;; without throwing
            (chunk/content-chunk? msg)
            (close-resources)

            :else
            (do
              (close-resources)
              (throw (IllegalArgumentException. "unhandled message chunk on body channel")))))))))

(defn initializer
  "An initializer is a per-connection context creator.
   For each incoming connections, the HTTP server codec is used."
  [{:keys [chunk-size ring-handler idle-timeout]
    :or   {chunk-size default-chunk-size}
    :as   opts}]
  (proxy [ChannelInitializer] []
    (initChannel [channel]
      (let [handler-opts (select-keys opts [:inbuf :executor :channel])
            codec        (HttpServerCodec. 4096 8192 (int chunk-size))
            handler      (netty-handler ring-handler handler-opts)
            pipeline     (.pipeline ^io.netty.channel.Channel channel)]
        (.addLast pipeline "codec"      codec)
        (when idle-timeout
          (.addFirst pipeline "idle-state" (IdleStateHandler. 0 0 idle-timeout)))
        (.addLast pipeline "handler"    handler)))))

(defn set-so-backlog!
  "Adjust Bootstrap socket backlog"
  [^AbstractBootstrap bootstrap {:keys [so-backlog]}]
  (.option bootstrap ChannelOption/SO_BACKLOG (int (or so-backlog 1024))))

(defn get-host-port
  "Extract host and port from a server options map, providing defaults"
  [{:keys [host port]}]
  [(or host "127.0.0.1") (or port 8080)])

(defn default-executor
  []
  (nc/executor :fixed {:num-threads 10}))

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
   {:loop-thread-count       <threadcount>
    :disable-epoll           <boolean>
    :host                    <host>
    :port                    <port>
    :chunk-size              <chunk-size>
    :inbuf                   <input-channel-buffer>
    :bootstrap               <config as understood by net.ty.boostrap/server-bootstrap>
    :executor                <ExecutorService used to run/generate sync responses>}
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
       (let [bootstrap (bs/server-bootstrap {:options (merge {:so-backlog 256 :so-reuseaddr true}
                                                             (:bootstrap options))
                                             :group   boss-group
                                             :channel (if (or (:disable-epoll? options)
                                                              (not (http/epoll?)))
                                                        bs/nio-server-socket-channel
                                                        bs/epoll-server-socket-channel)
                                             :handler (initializer options)})
             channel   (-> bootstrap
                           (http/set-log-handler! options)
                           (bs/bind! host port)
                           (f/sync!)
                           (chan/channel))]
         (nc/with-executor (or (:executor options) (default-executor))
           (-> channel (chan/close-future) (f/sync!)))
         (bs/shutdown-fn channel boss-group))))))

(def executor? #(instance? java.util.concurrent.ExecutorService %))

(s/def ::loop-thread-count pos-int?)
(s/def ::disable-epoll boolean?)
(s/def ::host string?)
(s/def ::port (s/int-in 1 65536))
(s/def ::chunk-size pos-int?)
(s/def ::input-channel-buffer pos-int?)
(s/def ::executor executor?)
(s/def ::bootstrap ::bs/server-bootstrap-schema)

(s/def ::options map?)
