(ns net.ty.bootstrap
  "A clojure facade to build netty bootstraps.
   In netty, bootstraps are helpers to get channels."
  (:require [clojure.spec   :as s]
            [net.ty.channel :as chan])
  (:import java.net.InetAddress
           java.net.NetworkInterface
           java.net.SocketAddress
           io.netty.util.AttributeKey
           io.netty.bootstrap.AbstractBootstrap
           io.netty.bootstrap.ServerBootstrap
           io.netty.bootstrap.Bootstrap
           io.netty.buffer.ByteBufAllocator
           io.netty.channel.RecvByteBufAllocator
           io.netty.channel.MessageSizeEstimator
           io.netty.channel.ChannelOption
           io.netty.channel.ChannelHandler
           io.netty.channel.EventLoopGroup
           io.netty.channel.nio.NioEventLoopGroup
           io.netty.channel.socket.nio.NioServerSocketChannel
           io.netty.channel.socket.nio.NioSocketChannel
           io.netty.channel.socket.nio.NioDatagramChannel
           java.net.InetAddress))

(def channel-options
  "Valid options for bootstraps"
  {:allocator                    ChannelOption/ALLOCATOR
   :allow-half-closure           ChannelOption/ALLOW_HALF_CLOSURE
   :auto-read                    ChannelOption/AUTO_READ
   :connect-timeout-millis       ChannelOption/CONNECT_TIMEOUT_MILLIS
   :ip-multicast-addr            ChannelOption/IP_MULTICAST_ADDR
   :ip-multicast-if              ChannelOption/IP_MULTICAST_IF
   :ip-multicast-loop-disabled   ChannelOption/IP_MULTICAST_LOOP_DISABLED
   :ip-multicast-ttl             ChannelOption/IP_MULTICAST_TTL
   :ip-tos                       ChannelOption/IP_TOS
   :max-messages-per-read        ChannelOption/MAX_MESSAGES_PER_READ
   :message-size-estimator       ChannelOption/MESSAGE_SIZE_ESTIMATOR
   :rcvbuf-allocator             ChannelOption/RCVBUF_ALLOCATOR
   :so-backlog                   ChannelOption/SO_BACKLOG
   :so-broadcast                 ChannelOption/SO_BROADCAST
   :so-keepalive                 ChannelOption/SO_KEEPALIVE
   :so-linger                    ChannelOption/SO_LINGER
   :so-rcvbuf                    ChannelOption/SO_RCVBUF
   :so-reuseaddr                 ChannelOption/SO_REUSEADDR
   :so-sndbuf                    ChannelOption/SO_SNDBUF
   :so-timeout                   ChannelOption/SO_TIMEOUT
   :tcp-nodelay                  ChannelOption/TCP_NODELAY
   :write-buffer-high-water-mark ChannelOption/WRITE_BUFFER_HIGH_WATER_MARK
   :write-buffer-low-water-mark  ChannelOption/WRITE_BUFFER_LOW_WATER_MARK
   :write-spin-count             ChannelOption/WRITE_SPIN_COUNT})

(defn ^ChannelOption ->channel-option
  [^clojure.lang.Keyword k]
  (or (channel-options k)
      (throw (ex-info (str "invalid channel option: " (name k)) {}))))

(def nio-server-socket-channel NioServerSocketChannel)
(def nio-socket-channel        NioSocketChannel)
(def nio-datagram-channel      NioDatagramChannel)

(defn ^EventLoopGroup nio-event-loop-group
  "Yield a new NioEventLoopGroup"
  []
  (NioEventLoopGroup.))

(defn ^ServerBootstrap server-bootstrap
  "Build a server bootstrap from a configuration map"
  [config]
  (when-not (s/valid? ::server-bootstrap-schema config)
    (throw (IllegalArgumentException. "invalid server bootstrap configuration")))
  (let [bs (ServerBootstrap.)
        group ^EventLoopGroup (:child-group config)]
    (if-let [c ^EventLoopGroup (:child-group config)]
      (.group bs (or group (nio-event-loop-group)) c)
      (.group bs (or group (nio-event-loop-group))))
    (.channel bs (or (:channel config) nio-server-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-options config) :let [copt (->channel-option k)]]
      (.childOption bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-attrs config)]
      (.childAttr bs (AttributeKey/valueOf (name k)) v))
    (.childHandler bs (:handler config))
    (.validate bs)))

(defn ^AbstractBootstrap bootstrap
  "Build a client bootstrap from a configuration map"
  [config]
  (when-not (s/valid? ::bootstrap-schema config)
    (println (s/explain-out (s/explain-data ::bootstrap-schema config)))
    (throw (IllegalArgumentException. "invalid bootstrap configuration")))
  (let [bs (Bootstrap.)]
    (.group bs (or (:group config) (nio-event-loop-group)))
    (.channel bs (or (:channel config) nio-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:attrs config)]
      (.attr bs (AttributeKey/valueOf (name k)) v))
    (when-let [[^InetAddress host port] (:remote-address config)]
      (.remoteAddress bs host (int port)))
    (.handler bs (:handler config))
    (.validate bs)))

(defn bind!
  "Bind bootstrap to a host and port"
  [^ServerBootstrap bs ^String host ^Long port]
  (.bind bs host (int port)))

(defn remote-address!
  "Set remote address for a client bootstrap, allows host and port
   to be provided as a SocketAddress"
  ([^Bootstrap bs ^SocketAddress sa]
   (.remoteAddress bs sa))
  ([^Bootstrap bs
    ^InetAddress host
    ^Long port]
   (.remoteAddress bs host (int port))))

(defn connect!
  "Attempt connection of a bootstrap. Accepts as pre-configured bootstrap,
   and optionally a SocketAddressor Host and Port."
  ([^Bootstrap bs]
   (.connect bs))
  ([^Bootstrap bs ^SocketAddress sa]
   (.connect bs sa))
  ([^Bootstrap bs ^InetAddress x y]
   (.connect bs x (int y))))

(defn local-address!
  "Sets the bootstrap's local address. Accepts either a SocketAddress or
   Host and Port."
  ([^AbstractBootstrap bs x]
   (.localAddress bs (int x)))
  ([^AbstractBootstrap bs ^InetAddress x y]
   (.localAddress bs x (int y))))

(defn validate!
  "Validate that a bootstrap has correct parameters."
  ([^AbstractBootstrap bs]
   (.validate bs)))

(defn set-group!
  "Set the group on top of which channels will be created and then handled."
  [^AbstractBootstrap bs group]
  (.group bs group))

(defn shutdown-gracefully!
  "Gracefully shut down a group"
  [^EventLoopGroup group]
  (.shutdownGracefully group))

(defn shutdown-fn
  "Closure to shutdown a channel and associated group"
  [chan group]
  (fn []
    (chan/close! chan)
    (shutdown-gracefully! group)))

(defn set-child-handler!
  "A server bootstrap has a child handler, this methods helps set it"
  [^ServerBootstrap bootstrap
   ^ChannelHandler handler]
  (.childHandler bootstrap handler))

;; Specs
;; =====

(s/def ::allocator (partial instance? ByteBufAllocator))
(s/def ::allow-half-closure boolean?)
(s/def ::auto-read boolean?)
(s/def ::connect-timeout-millis pos-int?)
(s/def ::ip-multicast-addr (partial instance? InetAddress))
(s/def ::ip-multicast-if (partial instance? NetworkInterface))
(s/def ::ip-multicast-loop-disabled boolean?)
(s/def ::ip-multicast-ttl pos-int?)
(s/def ::ip-tos pos-int?)
(s/def ::max-messages-per-read pos-int?)
(s/def ::message-size-estimator (partial instance? MessageSizeEstimator))
(s/def ::rcvbuf-allocator (partial instance? RecvByteBufAllocator))
(s/def ::so-backlog pos-int?)
(s/def ::so-broadcast boolean?)
(s/def ::so-keepalive boolean?)
(s/def ::so-linger pos-int?)
(s/def ::so-rcvbuf pos-int?)
(s/def ::so-reuseaddr boolean?)
(s/def ::so-sndbuf pos-int?)
(s/def ::so-timeout pos-int?)
(s/def ::tcp-nodelay boolean?)
(s/def ::write-buffer-high-water-mark pos-int?)
(s/def ::write-buffer-low-water-mark pos-int?)
(s/def ::write-spin-count pos-int?)

(s/def ::channel-option-schema
  (s/keys :opt-un [::allocator
                   ::allow-half-closure
                   ::auto-read
                   ::connect-timeout-millis
                   ::ip-multicast-addr
                   ::ip-multicast-if
                   ::ip-multicast-loop-disabled
                   ::ip-multicast-ttl
                   ::ip-tos
                   ::max-messages-per-read
                   ::message-size-estimator
                   ::rcvbuf-allocator
                   ::so-backlog
                   ::so-broadcast
                   ::so-keepalive
                   ::so-linger
                   ::so-rcvbuf
                   ::so-reuseaddr
                   ::so-sndbuf
                   ::so-timeout
                   ::tcp-nodelay
                   ::write-buffer-high-water-mark
                   ::write-buffer-low-water-mark
                   ::write-spin-count]))

(s/def ::group (partial instance? EventLoopGroup))
(s/def ::handler any?)
(s/def ::options ::channel-option-schema)
(s/def ::child-options ::channel-option-schema)
(s/def ::child-attrs (s/map-of keyword? any?))
(s/def ::child-group (partial instance? EventLoopGroup))
(s/def ::channel any?)
(s/def ::remote-address (s/cat ::host string? ::port pos-int?))
(s/def ::server-bootstrap (partial instance? ServerBootstrap))
(s/def ::bootstrap (partial instance? Bootstrap))

(s/def ::server-bootstrap-schema
  (s/keys :req-un [::handler]
          :opt-un [::options ::child-options ::child-attrs ::group
                   ::child-group ::channel]))

(s/def ::bootstrap-schema
  (s/keys :req-un [::handler]
          :opt-un [::group ::options ::attrs ::channel ::remote-address]))

(s/fdef ->channel-option
        :args (s/cat :k keyword?)
        :ret  (partial instance? ChannelOption))

(s/fdef nio-event-loop-group
        :args (s/cat)
        :ret  (partial instance? NioEventLoopGroup))

(s/fdef server-bootstrap
        :args (s/cat :config ::server-bootstrap-schema)
        :ret  ::server-bootstrap)

(s/fdef bootstrap
        :args (s/cat :config ::server-bootstrap-schema)
        :ret  ::bootstrap)
