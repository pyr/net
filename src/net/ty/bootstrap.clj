(ns net.ty.bootstrap
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
           io.netty.channel.socket.nio.NioDatagramChannel))

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
  (s/keys :req-un [::group ::handler]
          :opt-un [::options ::child-options ::child-attrs
                   ::child-group ::channel]))

(s/def ::bootstrap-schema
  (s/keys :req-un [::handler]
          :opt-un [::group ::options ::attrs ::channel ::remote-address]))

(def channel-options
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

(defn nio-event-loop-group
  []
  (NioEventLoopGroup.))

(defn ^ServerBootstrap server-bootstrap
  [config]
  (when-not (s/valid? ::server-bootstrap-schema config)
    (throw (IllegalArgumentException. "invalid server bootstrap configuration")))
  (let [bs (ServerBootstrap.)]
    (.group   bs (or (:group config) (nio-event-loop-group)))
    (.channel bs (or (:channel config) nio-server-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-options config) :let [copt (->channel-option k)]]
      (.childOption bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:child-attrs config)]
      (.childAttr bs (AttributeKey/valueOf (name k)) v))
    (when-let [group (:child-group config)]
      (.childGroup bs group))
    (.childHandler bs (:handler config))
    (.validate bs)))

(defn ^Bootstrap bootstrap
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
    (when-let [[host port] (:remote-address config)]
      (.remoteAddress bs host (int port)))
    (.handler bs (:handler config))
    (.validate bs)))

(defn sync!
  [^AbstractBootstrap bs]
  (.sync bs))

(defn bind!
  [^ServerBootstrap bs ^String host ^Long port]
  (.bind bs host (int port)))

(defn remote-address!
  ([bs ^SocketAddress sa]
   (.remoteAddress bs sa))
  ([bs host ^Long port]
   (.remoteAddress bs host (int port))))

(defn connect!
  ([bs]
   (.connect bs))
  ([bs ^SocketAddress sa]
   (.connect bs sa))
  ([bs x y]
   (.connect bs x y)))

(defn local-address!
  ([bs x]
   (.localAddress bs x))
  ([bs x y]
   (.localAddress bs x y)))

(defn validate!
  ([bs]
   (.validate bs)))

(defn set-group!
  [bs group]
  (.group bs group))

(defn shutdown-gracefully!
  [group]
  (.shutdownGracefully group))

(defn shutdown-fn
  [chan group]
  (fn []
    (chan/close! chan)
    (shutdown-gracefully! group)))

(defn set-child-handler!
  [boostrap handler]
  (.childHandler bootstrap handler))

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
