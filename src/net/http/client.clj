(ns net.http.client
  "Small wrapper around netty for HTTP clients."
  (:require [net.codec.b64      :as b64]
            [net.ssl            :as ssl]
            [net.http           :as http]
            [net.http.uri       :as uri]
            [net.http.chunk     :as chunk]
            [net.http.headers   :as headers]
            [net.http.request   :as req]
            [net.ty.buffer      :as buf]
            [net.ty.channel     :as chan]
            [net.ty.future      :as f]
            [net.ty.bootstrap   :as bs]
            [net.ty.pipeline    :as p]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as a]
            [net.core.async     :refer [put!]])
  (:import io.netty.bootstrap.Bootstrap
           io.netty.buffer.ByteBuf
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
           io.netty.handler.codec.http.DefaultFullHttpRequest
           io.netty.handler.codec.http.HttpMethod
           io.netty.handler.codec.http.HttpHeaders
           io.netty.handler.codec.http.HttpResponse
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.HttpVersion
           io.netty.handler.codec.http.HttpObjectAggregator
           io.netty.handler.codec.http.FullHttpResponse
           io.netty.handler.codec.http.DefaultFullHttpRequest
           io.netty.handler.ssl.SslContext
           io.netty.handler.ssl.SslHandler
           java.net.URI
           java.nio.charset.Charset
           javax.xml.bind.DatatypeConverter
           clojure.core.async.impl.protocols.Channel))

(def default-inbuf 10)

(defn response-handler
  [f ^ChannelHandlerContext ctx ^HttpResponse msg body]
  (try
    (f {:status  (some-> msg .status .code)
        :headers (headers/as-map (.headers msg))
        :version (-> msg .protocolVersion .text)
        :body    body})
    (finally
      ;; This actually releases the content
      (buf/release (buf/as-buffer msg)))))

(defn ^ChannelInboundHandlerAdapter netty-handler
  "Simple netty-handler, everything may happen in
   channel read, since we're expecting a full http request."
  [f]
  (let [body (a/chan default-inbuf)]
    (proxy [ChannelInboundHandlerAdapter] []
      (exceptionCaught [^ChannelHandlerContext ctx e]
        (f {:status 5555 :error e}))
      (channelRead [^ChannelHandlerContext ctx ^FullHttpResponse msg]
        (if (instance? msg HttpResponse)
          (response-handler f ctx msg)
          (chunk/enqueue body ctx msg))))))

(defn request-initializer
  "Our channel initializer."
  ([ssl-ctx handler]
   (proxy [ChannelInitializer] []
     (initChannel [^Channel channel]
       (-> (chan/pipeline channel)
           (cond-> (some? ssl-ctx)
             (p/add-last "ssl"   (ssl/new-handler ssl-ctx channel)))
           (p/add-last "codec"   (HttpClientCodec.))
           (p/add-last "handler" (netty-handler handler)))))))

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

(f/deflistener write-listener
  [this ftr [^ChannelHandlerContext ctx ^Channel body]]
  (if (or (nil? ftr) (f/complete? ftr))
    (a/take!
     body
     #(let [msg (if % (chunk/chunk->http-object %) http/last-http-content)]
        (-> (chan/write-and-flush! ctx msg)
            (f/add-listener (if % this f/close-listener)))))
    (a/close! body)))

(defn async-request
  "Execute an asynchronous HTTP request, produce the response
   asynchronously on the provided `handler` function.

   If no client is provided, create one."
  ([request-map handler]
   (async-request (build-client {}) request-map handler))
  ([{:keys [group channel ssl-ctx]} request-map handler]
   (when-not (:uri request-map)
     (throw (ex-info "malformed request-map, needs :uri key" {})))
   (let [uri         (uri/parse (:uri request-map))
         ssl?        (:ssl? uri)
         port        (:port uri)
         host        (:host uri)
         initializer (request-initializer (when ssl? ssl-ctx) handler)
         bs          (bs/bootstrap {:group   group
                                    :channel channel
                                    :handler initializer})
         chan        (some-> bs (bs/connect! host port) chan/sync! chan/channel)
         body        (chunk/prepare-body (:body request-map))
         req         (req/data->request uri request-map)]
     (f/with-result [ftr (chan/write-and-flush! chan req)]
       (if (instance? Channel body)
         (f/operation-complete (write-listener chan body))
         (f/with-result [ftr (chan/write-and-flush! chan body)]
           (chan/close! (chan/channel ftr))))))))

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
  ([client request-map ch]
   (try
     (async-request request-map #(a/put! ch (or % ::no-output)))
     (catch Throwable t
       (a/put! ch t))))
  ([client request-map]
   (request-chan client request-map (a/promise-chan)))
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
(s/def ::build-client-opts map?)

(s/def ::client
  (s/keys :req-un [::channel ::group ::ssl-ctx]))
