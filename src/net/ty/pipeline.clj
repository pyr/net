(ns net.ty.pipeline
  "Utilities to build and work with Netty pipelines"
  (:import java.util.concurrent.TimeUnit
           java.nio.ByteOrder
           io.netty.util.CharsetUtil
           io.netty.channel.Channel
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
  "This is the minimum an adapter must implement to be proxied
   to a valid ChannelHandlerAdapter"
  (channel-read [this ctx input]
    "Called for each new payload on a channel"))

(defprotocol IsSharable
  "Implement this protocol for adapters when you want to report
   whether the adapter can be shared or not. When not implemented
   adapters will be deemed unsharable.

   While it may look more natural to write sharable as shareable,
   it is apparently valid english and is the way netty spells it out."
  (is-sharable? [this]
    "Returns whether the handler adapter is sharable"))

(defprotocol ChannelActive
  "Implement this protocol when you want your adapter to be
   notified for channel activation."
  (channel-active [this ctx]
    "Called when the channel becomes active"))

(defprotocol ChannelInactive
  "Implement this protocol when you want your adapter to be
   notified for channel deactivation."
  (channel-inactive [this ctx]
    "Called when the channel becomes inactive"))

(defprotocol ChannelRegistered
  "Implement this protocol when you want your adapter to be
   notified for channel registration."
  (channel-registered [this ctx]
    "Called upon channel registration"))

(defprotocol ChannelUnregistered
  "Implement this protocol when you want your adapter to be
   notified for channel deregistration."
  (channel-unregistered [this ctx]
    "Called when channel is deregistered"))

(defprotocol ChannelReadComplete
  "Implement this protocol when you want your adapter to be
   notified of a read complete status."
  (channel-read-complete [this ctx]
    "Called when read has completed"))

(defprotocol ExceptionCaught
  "Implement this protocol when you want your adapter to be
   notified of channel exception."
  (exception-caught [this ctx e]
    "Called for channel exceptions"))

(defprotocol ChannelWritabilityChanged
  "Implement this protocol when you want your adapter to be
   notified of changes in channel writability."
  (channel-writability-changed [this ctx]
    "Called when writability has changed on a channel"))

(defprotocol UserEventTriggered
  "Implement this protocol when you want your adapter to be
   notified of user events."
  (user-event-triggered [this ctx event]
    "Called when a user event has been triggered on a channel"))

(def ^:dynamic ^Channel *channel*
  "Thread-local binding for a channel"
  nil)

(defn build-pipeline
  "Build a pipeline from a list of handlers. Binds `*channel*` to
   the given `chan` argument and runs through handlers.

   When handlers are functions, call them with no arguments, otherwise
   add them directly to the **ChannelHandler** list.

   Yields an array of **ChannelHandler** instances"
  [handlers chan]
  (binding [*channel* chan]
    (into-array ChannelHandler (for [h handlers] (if (fn? h) (h) h)))))

(defn channel-initializer
  "Build a channel initializer from a pipeline, expressed as a
   sequence of **ChannelHandler**, see `build-pipeline` for how
   to express this."
  [pipeline]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel socket-channel]
      (.addLast (.pipeline socket-channel)
                (build-pipeline pipeline socket-channel)))))

(defmulti ^TimeUnit unit->time-unit
  "Converts to a java util concurrent TimeUnit"
  class)

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

(defmulti ^CharsetUtil charset->charset-util "converts to a CharsetUtil" class)

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
  "Build a ChannelHandler which times-out when no payload is read"
  ([^Long timeout unit]
   (let [tu (unit->time-unit unit)]
     (fn [] (ReadTimeoutHandler. timeout tu))))
  ([^Long timeout]
   (fn [] (ReadTimeoutHandler. timeout TimeUnit/SECONDS))))

(defn ^ChannelHandler line-based-frame-decoder
  "Builds a ChannelHandler which parses lines."
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
  "Encode outbound payloads as lines, appending telnet-style carriage returns"
  ([]
   (proxy [MessageToMessageEncoder] []
     (isSharable []
       true)
     (encode [ctx msg ^java.util.List out]
       (.add out (str msg "\r\n"))))))

(defmacro defencoder
  "Define encoder"
  [sym [msg ctx shareable?] & body]
  `(defn ^ChannelHandler ~sym
     []
     (proxy [io.netty.handler.codec.MessageToMessageEncoder] []
       (isSharable []
         (or (nil? ~shareable?)
             (boolean ~shareable?)))
       (encode [^ChannelHandlerContext ~ctx ~msg ^java.util.List out#]
         (.add out# (do ~@body))))))

(defmacro defdecoder
  "Define decoders"
  [sym [msg ctx shareable?] & body]
  `(defn ^ChannelHandler ~sym
     []
     (proxy [io.netty.handler.codec.MessageToMessageDecoder] []
       (isSharable []
         (or (nil? ~shareable?)
             (boolean ~shareable?)))
       (decode [^ChannelHandlerContext ~ctx ~msg ^java.util.List out#]
         (.add out# (do ~@body))))))

(defmulti ->byte-order "Concerts to a ByteOrder" class)

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
  "Create a length field based frame decoder."
  ([]
   (length-field-based-frame-decoder {}))
  ([{:keys [byte-order max offset length adjust strip fail-fast?]}]
   (let [bo  (->byte-order (or byte-order :big-endian))
         ff? (or (nil? fail-fast?) (boolean fail-fast?))]
     (fn []
       (LengthFieldBasedFrameDecoder. bo
                                      (int (or max 16384))
                                      (int (or offset 0))
                                      (int (or length 4))
                                      (int (or adjust 0))
                                      (int (or strip 4))
                                      ff?)))))

(defn ^ChannelHandler length-field-prepender
  "Creates an encoder that adds length-fields"
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
  "A decoder that coerces to string"
  ([]
   (StringDecoder. CharsetUtil/UTF_8))
  ([charset]
   (StringDecoder. (charset->charset-util charset))))

(defn ^ChannelHandler string-encoder
  "A encoder that coerces from strings"
  ([]
   (StringEncoder. CharsetUtil/UTF_8))
  ([charset]
   (StringEncoder. (charset->charset-util charset))))

(defn supported-signatures
  [adapter]
  (cond-> #{}
    (satisfies? IsSharable)                (conj :is-sharable?)
    (satisfies? ChannelActive)             (conj :channel-active)
    (satisfies? ChannelInactive)           (conj :channel-inactive)
    (satisfies? ChannelReadComplete)       (conj :channel-read-complete)
    (satisfies? ChannelRegistered)         (conj :channel-registered)
    (satisfies? ChannelUnregistered)       (conj :channel-unregistered)
    (satisfies? ChannelWritabilityChanged) (conj :channel-writability-changed)
    (satisfies? UserEventTriggered)        (conj :user-event-triggered)
    (satisfies? ExceptionCaught)           (conj :exception-caught)))

(defn make-handler-adapter
  "From an implemenation of net.ty.pipeline.HandlerAdapater,
   yield a proxied ChannelInboundHandlerAdapter.

   Adapters may optionally implement any or all of the following
   protocols: `IsSharable`, `ChannelActive`, `ChannelInactive`,
   `ChannelReadComplete`, `ChannelRegistered`, `ChannelUnregistered`,
   `ExceptionCaught`, `ChannelWritabilityChanged`, and
   `UserEventTriggered`.

   To circumvent CLJ-1814 we cover the most common cases and otherwise
   build a set of known operations to avoid calling `satisfies?` at runtime."
  [adapter]
  (when-not (satisfies? HandlerAdapter adapter)
    (throw (IllegalArgumentException.
            "adapters must implement HandlerAdapter")))
  (let [supported? (supported-signatures adapter)]
    (cond
      (= supported? #{})
      (proxy [ChannelInboundHandlerAdapter] []
        (channelRead [^ChannelHandlerContext ctx input]
          (channel-read adapter ctx input))
        (isSharable []
          true))

      (= supported? #{:exception-caught})
      (proxy [ChannelInboundHandlerAdapter] []
        (channelRead [^ChannelHandlerContext ctx input]
          (channel-read adapter ctx input))
        (exceptionCaught [^ChannelHandlerContext ctx ^Throwable e]
          (exception-caught adapter ctx e))
        (isSharable []
          true))

      (= supported? #{:exception-caught :is-sharable?})
      (proxy [ChannelInboundHandlerAdapter] []
        (channelRead [^ChannelHandlerContext ctx input]
          (channel-read adapter ctx input))
        (exceptionCaught [^ChannelHandlerContext ctx ^Throwable e]
          (exception-caught adapter ctx e))
        (isSharable []
          (is-sharable? adapter)))

      :else
      (proxy [ChannelInboundHandlerAdapter] []
        (channelRead [^ChannelHandlerContext ctx input]
          (channel-read adapter ctx input))
        (isSharable []
          (boolean
           (or (not (supported? :is-sharable?))
               (is-sharable? adapter))))
        (channelActive [^ChannelHandlerContext ctx]
          (when (supported? :channel-active)
            (channel-active adapter ctx)))
        (channelInactive [^ChannelHandlerContext ctx]
          (when (supported? :channel-inactive)
            (channel-inactive adapter ctx)))
        (channelReadComplete [^ChannelHandlerContext ctx]
          (when (supported? :channel-read-complete)
            (channel-read-complete adapter ctx)))
        (channelRegistered [^ChannelHandlerContext ctx]
          (when (supported? :channel-registered)
            (channel-registered adapter ctx)))
        (channelUnregistered [^ChannelHandlerContext ctx]
          (when (supported? :channel-unregistered)
            (channel-unregistered adapter ctx)))
        (exceptionCaught [^ChannelHandlerContext ctx ^Throwable e]
          (when (supported? :exception-caught)
            (exception-caught adapter ctx e)))
        (channelWritabilityChanged [^ChannelHandlerContext ctx]
          (when (supported? :channel-writability-changed)
            (channel-writability-changed adapter ctx)))
        (userEventTriggered [^ChannelHandlerContext ctx event]
          (when (supported? :user-event-triggered)
            (user-event-triggered adapter ctx event)))))))

(defn flush!
  "Flush context"
  [^ChannelHandlerContext ctx]
  (.flush ctx))

(defn write!
  "Write message to context"
  [^ChannelHandlerContext ctx msg]
  (.write ctx msg))

(defn write-and-flush!
  "Write message to context, then flush context"
  [^ChannelHandlerContext ctx msg]
  (.writeAndFlush ctx msg))

(defmacro ^ChannelInboundHandlerAdapter with-input
  "Inline definition of a **ChannelInboundHandlerAdapter** which
   captures context and input and executes body."
  [[ctx input] & body]
  `(proxy [ChannelInboundHandlerAdapter] []
     (channelRead [^ChannelHandlerContext ~ctx ~input]
       (do ~@body))
     (channelReadComplete [^ChannelHandlerContext ~ctx]
       (.flush ^ChannelHandlerContext ~ctx))
     (exceptionCaught [^ChannelHandlerContext ~ctx ^Exception e#]
       (proxy-super exceptionCaught
                    ^ChannelHandlerContext ~ctx
                    ^Exception e#))
     (isSharable []
       true)))
