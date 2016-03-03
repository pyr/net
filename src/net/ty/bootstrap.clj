(ns net.ty.bootstrap
  (:require [schema.core :as s])
  (:import java.net.InetAddress
           java.net.NetworkInterface
           java.net.SocketAddress
           io.netty.util.AttributeKey
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
           ))

(def channel-option-schema
  {(s/optional-key :allocator)   ByteBufAllocator
   (s/optional-key :allow-half-closure) s/Bool
   (s/optional-key :auto-read) s/Bool
   (s/optional-key :connect-timeout-millis) s/Num
   (s/optional-key :ip-multicast-addr) InetAddress
   (s/optional-key :ip-multicast-if)   NetworkInterface
   (s/optional-key :ip-multicast-loop-disabled) s/Bool
   (s/optional-key :ip-multicast-ttl)  s/Num
   (s/optional-key :ip-tos) s/Num
   (s/optional-key :max-messages-per-read) s/Num
   (s/optional-key :message-size-estimator) MessageSizeEstimator
   (s/optional-key :rcvbuf-allocator) RecvByteBufAllocator
   (s/optional-key :so-backlog) s/Num
   (s/optional-key :so-broadcast) s/Bool
   (s/optional-key :so-keepalive) s/Bool
   (s/optional-key :so-linger) s/Num
   (s/optional-key :so-rcvbuf) s/Num
   (s/optional-key :so-reuseaddr) s/Bool
   (s/optional-key :so-sndbuf) s/Num
   (s/optional-key :so-timeout) s/Num
   (s/optional-key :tcp-nodelay) s/Bool
   (s/optional-key :write-buffer-high-water-mark) s/Num
   (s/optional-key :write-buffer-low-water-mark) s/Num
   (s/optional-key :write-spin-count) s/Num})

(def channel-options
  {:allocator ChannelOption/ALLOCATOR
   :allow-half-closure ChannelOption/ALLOW_HALF_CLOSURE
   :auto-read ChannelOption/AUTO_READ
   :connect-timeout-millis ChannelOption/CONNECT_TIMEOUT_MILLIS
   :ip-multicast-addr ChannelOption/IP_MULTICAST_ADDR
   :ip-multicast-if ChannelOption/IP_MULTICAST_IF
   :ip-multicast-loop-disabled ChannelOption/IP_MULTICAST_LOOP_DISABLED
   :ip-multicast-ttl ChannelOption/IP_MULTICAST_TTL
   :ip-tos ChannelOption/IP_TOS
   :max-messages-per-read ChannelOption/MAX_MESSAGES_PER_READ
   :message-size-estimator ChannelOption/MESSAGE_SIZE_ESTIMATOR
   :rcvbuf-allocator ChannelOption/RCVBUF_ALLOCATOR
   :so-backlog ChannelOption/SO_BACKLOG
   :so-broadcast ChannelOption/SO_BROADCAST
   :so-keepalive ChannelOption/SO_KEEPALIVE
   :so-linger ChannelOption/SO_LINGER
   :so-rcvbuf ChannelOption/SO_RCVBUF
   :so-reuseaddr ChannelOption/SO_REUSEADDR
   :so-sndbuf ChannelOption/SO_SNDBUF
   :so-timeout ChannelOption/SO_TIMEOUT
   :tcp-nodelay ChannelOption/TCP_NODELAY
   :write-buffer-high-water-mark ChannelOption/WRITE_BUFFER_HIGH_WATER_MARK
   :write-buffer-low-water-mark ChannelOption/WRITE_BUFFER_LOW_WATER_MARK
   :write-spin-count ChannelOption/WRITE_SPIN_COUNT})

(defn ^ChannelOption ->channel-option
  [^clojure.lang.Keyword k]
  (or (channel-options k)
      (throw (ex-info (str "invalid channel option: " (name k)) {}))))

(def server-bootstrap-schema
  {(s/optional-key :options)       channel-option-schema
   (s/optional-key :child-options) channel-option-schema
   (s/optional-key :child-attrs)   {s/Keyword s/Any}
   (s/optional-key :child-group)   EventLoopGroup
   (s/optional-key :channel)       java.lang.Class
   :group                          EventLoopGroup
   :handler                        ChannelHandler})

(def bootstrap-schema
  {(s/optional-key :options)  channel-option-schema
   (s/optional-key :attrs)    {s/Keyword s/Any}
   (s/optional-key :channel)  java.lang.Class
   :group                     EventLoopGroup
   :handler                   ChannelHandler})

(def nio-server-socket-channel NioServerSocketChannel)
(def nio-socket-channel        NioSocketChannel)
(def nio-datagram-channel      NioDatagramChannel)

(defn nio-event-loop-group
  []
  (NioEventLoopGroup.))

(s/defn ^ServerBoostrap server-bootstrap :- ServerBootstrap
  [config :- server-bootstrap-schema]
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
    (.handler bs (:handler config))
    (.validate bs)))

(s/defn ^Bootstrap bootstrap :- Bootstrap
  [config :- bootstrap-schema]
  (let [bs (Bootstrap.)]
    (.group bs (or (:group config) (nio-event-loop-group)))
    (.channel bs (or (:channel config) nio-socket-channel))
    (doseq [[k v] (:options config) :let [copt (->channel-option k)]]
      (.option bs copt (if (number? v) (int v) v)))
    (doseq [[k v] (:attrs config)]
      (.attr bs (AttributeKey/valueOf (name k)) v))
    (.handler bs (:handler config))
    (.validate bs)))

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
