(ns net.ty.bootstrap
  "A clojure facade to build netty bootstraps.
   In netty, bootstraps are helpers to get channels."
  (:require [clojure.spec.alpha    :as s]
            [net.ty.channel        :as chan])
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
           io.netty.channel.WriteBufferWaterMark
           io.netty.channel.nio.NioEventLoopGroup
           io.netty.channel.epoll.EpollEventLoopGroup
           io.netty.channel.socket.nio.NioServerSocketChannel
           io.netty.channel.socket.nio.NioSocketChannel
           io.netty.channel.socket.nio.NioDatagramChannel
           io.netty.channel.epoll.EpollSocketChannel
           io.netty.channel.epoll.EpollServerSocketChannel
           io.netty.channel.epoll.EpollDatagramChannel
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
   :write-buffer-high-water-mark ChannelOption/WRITE_BUFFER_WATER_MARK
   :write-spin-count             ChannelOption/WRITE_SPIN_COUNT})

(defn ^ChannelOption ->channel-option
  [^clojure.lang.Keyword k]
  (or (channel-options k)
      (throw (ex-info (str "invalid channel option: " (name k)) {}))))

(def nio-server-socket-channel NioServerSocketChannel)
(def nio-socket-channel        NioSocketChannel)
(def nio-datagram-channel      NioDatagramChannel)

(def epoll-server-socket-channel EpollServerSocketChannel)
(def epoll-socket-channel        EpollSocketChannel)
(def epoll-datagram-channel      EpollDatagramChannel)

(defn write-buffer-water-mark
  [low high]
  (WriteBufferWaterMark. (int low) (int high)))

(defn ^EventLoopGroup nio-event-loop-group
  "Yield a new NioEventLoopGroup"
  ([nb-threads]
   (NioEventLoopGroup. nb-threads))
  ([]
   (NioEventLoopGroup.)))

(defn ^EventLoopGroup epoll-event-loop-group
  ([nb-threads]
   (EpollEventLoopGroup. nb-threads))
  ([]
   (EpollEventLoopGroup.)))

(defn ^ServerBootstrap server-bootstrap
  "Build a server bootstrap from a configuration map"
  [config]
  (s/assert ::server-bootstrap-schema config)
  (let [bs (ServerBootstrap.)
        group ^EventLoopGroup (:group config)]
    (if-let [c ^EventLoopGroup (:child-group config)]
      (.group bs (or group (epoll-event-loop-group)) c)
      (.group bs (or group (epoll-event-loop-group))))
    (.channel bs (or (:channel config) epoll-server-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-options config) :let [copt (->channel-option k)]]
      (.childOption bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-attrs config)]
      (.childAttr bs (AttributeKey/valueOf (name k)) v))
    (.childHandler bs (:handler config))
    (.validate bs)))

(defn remote-address!
  "Set remote address for a client bootstrap, allows host and port
   to be provided as a SocketAddress"
  ([^Bootstrap bs ^SocketAddress sa]
   (.remoteAddress bs sa))
  ([^Bootstrap bs
    host
    ^Long port]
   (cond
     (string? host)
     (.remoteAddress bs ^String host (int port))

     (instance? InetAddress host)
     (.remoteAddress bs ^InetAddress host (int port))

     :else
     (throw (IllegalArgumentException. "invalid address")))))

(defn ^AbstractBootstrap bootstrap
  "Build a client bootstrap from a configuration map"
  [config]
  (s/assert ::bootstrap-schema config)
  (let [bs (Bootstrap.)]
    (.group bs (or (:group config) (epoll-event-loop-group)))
    (.channel bs (or (:channel config) epoll-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:attrs config)]
      (.attr bs (AttributeKey/valueOf (name k)) v))
    (when-let [[host port] (:remote-address config)]
      (remote-address! bs host port))
    (.handler bs (:handler config))
    (.validate bs)))

(defn bind!
  "Bind bootstrap to a host and port"
  [^ServerBootstrap bs ^String host ^Long port]
  (.bind bs host (int port)))

(defn connect!
  "Attempt connection of a bootstrap. Accepts as pre-configured bootstrap,
   and optionally a SocketAddressor Host and Port."
  ([^Bootstrap bs]
   (.connect bs))
  ([^Bootstrap bs ^SocketAddress sa]
   (.connect bs sa))
  ([^Bootstrap bs x y]
   (cond
     (string? x)
     (.connect bs ^String x (int y))

     (instance? InetAddress x)
     (.connect bs ^InetAddress x (int y))

     (instance? SocketAddress x)
     (.connect bs ^SocketAddress x ^SocketAddress y)

     :else
     (throw (IllegalArgumentException. "Invalid arguments to connect")))))

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
  [chan config]
  (fn []
    (chan/close! chan)
    (when-let [group (:child-group config)]
      (shutdown-gracefully! group))
    (when-let [group (:group config)]
      (shutdown-gracefully! group))))

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
(s/def ::write-buffer-water-mark (partial instance? WriteBufferWaterMark))
(s/def ::write-spin-count pos-int?)

;; We do not specify optional keys since they will be validated anyway
(s/def ::channel-option-schema map?)

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
