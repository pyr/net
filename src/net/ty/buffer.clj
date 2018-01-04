(ns net.ty.buffer
 "Utility functions to build fixed size buffers.
   These functions hold onto data and yield when
   a specific size has been reached."
 (:require [net.ty.coerce         :as co])
  (:import io.netty.buffer.Unpooled
           io.netty.buffer.CompositeByteBuf
           io.netty.buffer.ByteBuf
           io.netty.buffer.ByteBufHolder
           java.nio.ByteBuffer))

(defprotocol Bufferizable
  (as-buffer [this]))

(extend-protocol Bufferizable
  ByteBufHolder
  (as-buffer [this] (.content this))
  ByteBuf
  (as-buffer [this] this))

(defn buffer?
  [x]
  (instance? ByteBuf x))

(defn buffer
  ([]
   (Unpooled/buffer))
  ([init]
   (Unpooled/buffer (int init)))
  ([init max]
   (Unpooled/buffer (int init) (int max))))

(defn refcount
  [^ByteBuf buf]
  (.refCnt buf))

(defn capacity
  [^ByteBuf buf]
  (.capacity buf))

(defn direct-buffer
  ([]
   (Unpooled/directBuffer))
  ([init]
   (Unpooled/directBuffer (int init)))
  ([init max]
   (Unpooled/directBuffer (int init) (int max))))

(defn ^CompositeByteBuf composite
  ([]
   (Unpooled/compositeBuffer))
  ([max]
   (Unpooled/compositeBuffer (int max))))

(defn ^CompositeByteBuf consolidate
  [^CompositeByteBuf cb]
  (.consolidate cb))

(defn unwrap
  [^ByteBuf buf]
  (.unwrap buf))

(defn composite?
  ([buf]
   (instance? CompositeByteBuf buf)))

(defn augment-composite
  ([^CompositeByteBuf cb ^ByteBuf buf]
   (augment-composite (or (unwrap cb) cb) true buf))
  ([^CompositeByteBuf cb inc-index ^ByteBuf buf]
   (.addComponent cb (boolean inc-index) buf)))

(defn composite-of
  [& parts]
  (reduce augment-composite (composite) parts))

(defn component-count
  [^CompositeByteBuf cb]
  (.numComponents cb))

(defn component
  [^CompositeByteBuf cb i]
  (.component cb (int i)))

(defn component-iterator
  [^CompositeByteBuf cb]
  (.iterator cb))

(defn components
  [^CompositeByteBuf cb]
  (iterator-seq (component-iterator cb)))

(defn ^ByteBuf ro-view
  [^ByteBuf buf]
  (.asReadOnly buf))

(defn is-direct?
  [^ByteBuf buf]
  (.isDirect buf))

(defn release
  [^ByteBuf buf]
  (.release buf))

(defn touch
  [^ByteBuf buf]
  (.touch buf))

(defn retain
  ([^ByteBuf buf]
   (.retain buf))
  ([^ByteBuf buf incr]
   (.retain buf (int incr))))

(defn retain-last
  [^ByteBuf buf]
  (if (composite? buf)
    (let [sub (last (components buf))]
      (retain sub))
    (retain buf)))

(defn retain-all
  [buf]
  (let [buf (or (unwrap buf) buf)]
    (retain buf)
    (when (composite? buf)
      (doseq [c (components buf)]
        (retain c))))
  buf)

(defn release-all
  [buf]
  (let [buf (or (unwrap buf) buf)]
    (when (composite? buf)
      (doseq [c (components buf)]
        (release c)))
    (release buf))
  buf)

(defn ^ByteBuf copy
  [^ByteBuf buf]
  (Unpooled/copiedBuffer buf))

(defn ^ByteBuf duplicate
  [^ByteBuf buf]
  (.duplicate buf))

(def empty-buffer "An empty bytebuffer" Unpooled/EMPTY_BUFFER)

(defn readable-bytes
  [^ByteBuf buf]
  (.readableBytes buf))

(defn empty-buffer?
  [buf]
  (or (= buf empty-buffer)
      (zero? (readable-bytes buf))))

(defn wrapped-bytes
  [^"[B" ba]
  (Unpooled/wrappedBuffer ba))

(defn wrapped-string
  [^String s]
  (let [ba (.getBytes s)]
    (wrapped-bytes ba)))

(defn reader-index
  [^ByteBuf buf]
  (.readerIndex buf))

(defn writer-index
  [^ByteBuf buf]
  (.writerIndex buf))

(defn index-of
  "Look for the first index of the byte b in a ByteBuf return it or nil"
  ([^ByteBuf buf b]
   (when-let [idx (index-of buf (reader-index buf) (writer-index buf) b)]
     (- idx (reader-index buf))))
  ([^ByteBuf buf from to b]
   (let [idx (.indexOf buf (int from) (int to) (byte b))]
     (when-not (neg? idx)
       idx))))

(defn exhausted?
  [^ByteBuf buf]
  (zero? (readable-bytes buf)))

(defn ^ByteBuf skip-bytes
  ([^ByteBuf buf]
   (skip-bytes buf (readable-bytes buf)))
  ([^ByteBuf buf len]
   (.skipBytes buf (int len))))

(defn ^ByteBuf write-bytes
  ([^ByteBuf dst bytes]
   (cond
     (nil? dst)                bytes
     (bytes? bytes)            (.writeBytes dst ^"[B" bytes)
     (instance? ByteBuf bytes) (.writeBytes dst ^ByteBuf bytes)
     :else (throw (IllegalArgumentException.
                   (str "cannot write bytes from" (class bytes))))))
  ([^ByteBuf dst ^"[B" ba idx len]
   (.writeBytes dst ba (int idx ) (int len))   ))

(defn ^ByteBuf write-string
  [^ByteBuf buf ^String s]
  (.writeBytes buf (.getBytes s)))

(defn ^ByteBuf merge-bufs
  [buffers]
  (Unpooled/wrappedBuffer ^"[Lio.netty.buffer.ByteBuf;"
                          (into-array ByteBuf buffers)))

(defn ^ByteBuf write-and-skip-bytes
  ([^ByteBuf dst ^ByteBuf src]
   (write-and-skip-bytes dst src (readable-bytes src)))
  ([^ByteBuf dst ^ByteBuf src len]
   (skip-bytes src len)
   (write-bytes dst src)))

(defn ^String to-string
  ([^ByteBuf buf ^Boolean release?]
   (let [ba (byte-array (readable-bytes buf))]
     (.readBytes buf ba)
     (when release?
       (release buf))
     (String. ba "UTF-8")))
  ([^ByteBuf buf]
   (to-string buf false)))

(defn ^"[B" to-bytes
  [^ByteBuf buf]
  (let [ba (byte-array (readable-bytes buf))]
    (.readBytes buf ba)
    ba))

(defn ^ByteBuf mark-reader-index
  [^ByteBuf buf]
  (.markReaderIndex buf))

(defn ^ByteBuf reset-reader-index
  [^ByteBuf buf]
  (.resetReaderIndex buf))

(defn ^ByteBuf retained-slice
  ([^ByteBuf src]
   (if (empty-buffer? src)
     empty-buffer
     (.retainedSlice src)))
  ([^ByteBuf src length]
   (if (pos? length)
     (.retainedSlice src (reader-index src) (int length))
     empty-buffer))
  ([^ByteBuf src index length]
   (if (pos? length)
     (.retainedSlice src (int index) (int length))
     empty-buffer)))

(defn ^ByteBuf slice
  ([^ByteBuf src]
   (if (empty-buffer? src)
     empty-buffer
     (.slice src)))
  ([^ByteBuf src length]
   (if (pos? length)
     (.slice src (reader-index src) (int length))
     empty-buffer))
  ([^ByteBuf src index length]
   (if (pos? length)
     (.slice src (int index) (int length))
     empty-buffer)))

(def sliced-string (comp to-string slice))


(defn ^ByteBuf read-retained-slice
  ([^ByteBuf src]
   (if (empty-buffer? src)
     empty-buffer
     (.readRetainedSlice src (readable-bytes src))))
  ([^ByteBuf src length]
   (if (pos? length)
     (.readRetainedSlice src (int length))
     empty-buffer)))

(defn ^ByteBuf read-slice
  ([^ByteBuf src]
   (if (empty-buffer? src)
     empty-buffer
     (.readSlice src (readable-bytes src))))
  ([^ByteBuf src length]
   (if (pos? length)
     (.readSlice src (int length))
     empty-buffer)))

(defn ^ByteBuffer nio-buffer
  [^ByteBuf buf]
  (.nioBuffer buf))

(defn retain-augment
  ([^CompositeByteBuf cb ^ByteBuf buf]
   (let [prevcount (refcount buf)]
     (augment-composite cb (retained-slice buf))
     (when (> (refcount buf) prevcount)
       (release buf))
     cb)))

(defn refcounts
  [buf]
  (let [buf (or (unwrap buf) buf)]
    (if (composite? buf)
      [(refcount buf)
       (let [len (component-count buf)]
         (vec (for [i (range len)]
                (try
                  (refcount (component buf i))
                  (catch Exception _)))))]
      (try (refcount buf) (catch Exception _)))))

(defn showbuf
  [b]
  (let [b (or (unwrap b) b)]
    (cond
      (and (composite? b) (pos? (refcount b)))
      (vec (conj (for [s (components b)] (showbuf s))
                 :>
                 (refcount b)))

      (composite? b)
      :dead-composite

      (pos? (refcount b))
      (refcount b)

      :else
      :dead)))

(defn ^Exception wrong-byte-arg
  "Build an illegal argument exception"
  [b]
  (IllegalArgumentException.
   (str "wrong argument to byte-length: " (pr-str b))))

(defn ^Long byte-length
  "Figure out how "
  [bytes]
  (cond
    (nil? bytes)              0
    (bytes? bytes)            (count bytes)
    (instance? ByteBuf bytes) (- (.writerIndex ^ByteBuf bytes)
                                 (.readerIndex ^ByteBuf bytes))
    :else                     (throw (wrong-byte-arg bytes))))

(defn ^ByteBuf write-ushort
  "Push a short to a bytebuf, we keep host endianness over
   the network for optimization reasons"
  [^ByteBuf buf ^Long i]
  (.writeShortLE buf (co/ushort->short i)))

(defn ^ByteBuf write-uint
  "Push an int to a bytebuf, we keep host endianness over
   the network for optimization reasons"
  [^ByteBuf buf ^Long i]
  (.writeIntLE buf (co/uint->int i)))

(defn ^ByteBuf write-ulong
  "Push a long to a bytebuf, we keep host endianness over
   the network for optimization reasons"
  [^ByteBuf buf ^Long l]
  (.writeLongLE buf (co/ulong->long l)))

(defn ^ByteBuf write-byte-range
  "Push the specified range of contents of a byte array to a ByteBuf"
  [^ByteBuf buf bytes ^long index ^long length]
  (cond
    (bytes? bytes)            (.writeBytes buf ^"[B" bytes index length)
    (instance? ByteBuf bytes) (.writeBytes buf ^ByteBuf bytes index length)
    :else (throw (IllegalArgumentException.
                  (str "cannot write bytes from" (class bytes)))))
  buf)

(defn ^Long read-short
  "Fetch a short from a ByteBuf. Host endianness."
  [^ByteBuf buf]
  (.readShortLE buf))

(defn ^Long read-ushort
  [^ByteBuf buf]
  (co/short->ushort (read-short buf)))

(defn ^Long read-int
  "Fetch an integer from a ByteBuf. Host endianness."
  [^ByteBuf buf]
  (.readIntLE buf))

(defn ^Long read-uint
  [^ByteBuf buf]
  (co/int->uint (read-int buf)))

(defn ^Long read-long
  "Fetch a long from a ByteBuf. Host endianness."
  [^ByteBuf buf]
  (.readLongLE buf))

(defn ^Long read-ulong
  [^ByteBuf buf]
  (co/long->ulong (read-long buf)))

(defn read-byte
  [^ByteBuf buf]
  (.readByte buf))

(defn read-ubyte
  [^ByteBuf buf]
  (co/byte->ubyte (read-byte buf)))

(defn ^"[B" read-bytes
  [^ByteBuf buf ^"[B" ba]
  (.readBytes buf ba)
  ba)

(defn read-char
  [buf]
  (char (read-byte buf)))

(defn read-digest
  [buf]
  (read-bytes buf (byte-array 16)))
