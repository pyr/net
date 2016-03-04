(ns net.ty.pipeline
  (:import java.util.concurrent.TimeUnit
           java.nio.ByteOrder
           io.netty.util.CharsetUtil
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelInboundHandlerAdapter
           io.netty.channel.ChannelHandlerContext
           io.netty.channel.ChannelHandler
           io.netty.channel.ChannelInitializer
           io.netty.handler.timeout.ReadTimeoutException
           io.netty.handler.timeout.ReadTimeoutHandler
           io.netty.handler.codec.string.StringDecoder
           io.netty.handler.codec.string.StringEncoder
           io.netty.handler.codec.LineBasedFrameDecoder
           io.netty.handler.codec.LengthFieldBasedFrameDecoder
           io.netty.handler.codec.LengthFieldPrepender
           io.netty.handler.codec.MessageToMessageEncoder
           io.netty.channel.socket.SocketChannel))

(defprotocol HandlerAdapter
  (capabilities [this])
  (channel-active [this ctx])
  (channel-read [this ctx input])
  (channel-read-complete [this ctx])
  (exception-caught [this ctx e])
  (is-sharable? [this]))

(defn build-pipeline
  [handlers]
  (into-array ChannelHandler (for [h handlers] (if (fn? h) (h) h))))

(defn channel-initializer
  [pipeline]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel socket-channel]
      (.addLast (.pipeline socket-channel) (build-pipeline pipeline)))))

(defmulti ^TimeUnit unit->time-unit class)

(defmethod unit->time-unit clojure.lang.Keyword
  [^clojure.lang.Keyword kw]
  (let [units {:seconds      TimeUnit/SECONDS
               :minutes      TimeUnit/MINUTES
               :hours        TimeUnit/HOURS
               :days         TimeUnit/DAYS
               :miliseconds  TimeUnit/MILLISECONDS
               :microseconds TimeUnit/MICROSECONDS
               :nanoseconds  TimeUnit/NANOSECONDS}]
    (or (get units kw)
        (throw (ex-info (str "Invalid time unit" (name kw)) {})))))

(defmethod unit->time-unit String
  [^String s]
  (unit->time-unit (keyword s)))

(defmethod unit->time-unit TimeUnit
  [^TimeUnit u]
  u)

(defmethod unit->time-unit :default
  [x]
  (throw (ex-info (format "Cannot convert from %s to TimeUnit" (class x)) {})))

(defmulti ^CharsetUtil charset->charset-util class)

(defmethod charset->charset-util clojure.lang.Keyword
  [^clojure.lang.Keyword kw]
  (let [charsets {:utf-8       CharsetUtil/UTF_8
                  :utf-16      CharsetUtil/UTF_16
                  :utf-16-be   CharsetUtil/UTF_16BE
                  :utf-16-le   CharsetUtil/UTF_16LE
                  :iso-latin-1 CharsetUtil/ISO_8859_1
                  :us-ascii    CharsetUtil/US_ASCII}]
    (or (get charsets kw)
        (throw (ex-info (str "Invalid charset" (name kw)) {})))))

(defmethod charset->charset-util String
  [^String s]
  (charset->charset-util (keyword s)))

(defmethod charset->charset-util CharsetUtil
  [^CharsetUtil cs]
  cs)

(defmethod charset->charset-util :default
  [x]
  (throw (ex-info (format "Cannot convert from %s to CharsetUtil" (class x)) {})))

(defn ^ChannelHandler read-timeout-handler
  ([^Long timeout unit]
   (let [tu (unit->time-unit unit)]
     (fn [] (ReadTimeoutHandler. timeout tu))))
  ([^Long timeout]
   (fn [] (ReadTimeoutHandler. timeout TimeUnit/SECONDS))))

(defn ^ChannelHandler line-based-frame-decoder
  ([]
   (fn [] (LineBasedFrameDecoder. (int 512))))
  ([^Long max-length]
   (let [max-length (int max-length)]
     (fn [] (LineBasedFrameDecoder. max-length))))
  ([^Long max-length ^Boolean  strip-delimiter?]
   (let [max-length (int max-length)]
     (fn [] (LineBasedFrameDecoder. max-length strip-delimiter? true))))
  ([^Long max-length ^Boolean  strip-delimiter? fail-fast?]
   (let [max-length (int max-length)]
     (fn [] (LineBasedFrameDecoder. max-length strip-delimiter? fail-fast?)))))

(defn ^ChannelHandler line-frame-encoder
  ([]
   (proxy [MessageToMessageEncoder] []
     (encode [ctx msg out]
       (.add out (str msg "\r\n"))))))

(defmulti ->byte-order class)

(defmethod ->byte-order clojure.lang.Keyword
  [^clojure.lang.Keyword kw]
  (let [orders {:big-endian ByteOrder/BIG_ENDIAN
                :little-endian ByteOrder/LITTLE_ENDIAN}]
    (or (get orders kw)
        (throw (ex-info (str "Invalid byte-order: " (name kw)) {})))))

(defmethod ->byte-order String
  [^String s]
  (->byte-order (keyword s)))

(defmethod ->byte-order ByteOrder
  [^ByteOrder bo]
  bo)

(defn ^ChannelHandler length-field-based-frame-decoder
  ([]
   (length-field-based-frame-decoder {}))
  ([{:keys [byte-order offset length adjust strip fail-fast?]}]
   (let [bo  (->byte-order (or byte-order :big-endian))
         ff? (or (nil? fail-fast?) (boolean fail-fast?))]
     (fn []
       (LengthFieldBasedFrameDecoder. bo
                                      (int (or offset 0))
                                      (int (or length 4))
                                      (int (or adjust -4))
                                      (int (or strip 4))
                                      ff?)))))

(defn ^ChannelHandler length-field-prepender
  ([]
   (length-field-prepender {}))
  ([{:keys [length byte-order adjust includes-length?]}]
   (let [bo  (->byte-order (or byte-order :big-endian))
         il? (or (nil? includes-length?) (boolean includes-length?))]
     (fn []
       (LengthFieldPrepender. bo
                              (int (or length 4))
                              (int (or adjust 0))
                              il?)))))

(defn ^ChannelHandler string-decoder
  ([]
   (StringDecoder. CharsetUtil/UTF_8))
  ([charset]
   (StringDecoder. (charset->charset-util charset))))

(defn ^ChannelHandler string-encoder
  ([]
   (StringEncoder. CharsetUtil/UTF_8))
  ([charset]
   (StringEncoder. (charset->charset-util charset))))

(defn make-handler-adapter
  [adapter]
  (let [support? (set (capabilities adapter))]
    (proxy [ChannelInboundHandlerAdapter] []
      (channelActive [^ChannelHandlerContext ctx]
        (when (support? :channel-active)
          (channel-active adapter ctx)))
      (channelRead [^ChannelHandlerContext ctx input]
        (channel-read adapter ctx input))
      (channelReadComplete [^ChannelHandlerContext ctx]
        (when (support? :channel-read-complete)
          (channel-read-complete adapter ctx)))
      (exceptionCaught [^ChannelHandlerContext ctx ^Throwable e]
        (when (support? :exception-caught)
          (exception-caught adapter ctx e)))
      (isSharable []
        (is-sharable? adapter)))))

(defn flush!
  [^ChannelHandlerContext ctx]
  (.flush ctx))

(defn write!
  [^ChannelHandlerContext ctx msg]
  (.write ctx msg))

(defn write-and-flush!
  [^ChannelHandlerContext ctx msg]
  (.writeAndFlush ctx msg))

(defmacro with-input
  [[ctx input] & body]
  `(reify HandlerAdapter
     (channel-read [this# ~ctx ~input]
       (do ~@body))
     (channel-read-complete [this# ~ctx]
       (.flush ~ctx))
     (exception-caught [this# ~ctx e#]
       (proxy-super exceptionCaught ~ctx e#))
     (is-sharable? [this#]
       true)))
