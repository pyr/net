(ns net.http.chunk
  (:import io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           io.netty.handler.codec.http.DefaultHttpContent
           io.netty.handler.codec.http.HttpContent
           java.io.InputStream
           java.io.File
           java.io.FileInputStream
           java.nio.charset.Charset
           java.nio.ByteBuffer))

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
  (any->http-object [chunk] "Convert arbitrary data to an HttpContent instance"))

(defn chunk->http-object
  [chunk]
  (cond
    (bytes? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer ^"[B" chunk))

    (string? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer
                          (.getBytes ^String chunk "UTF8")))

    (instance? ByteBuf chunk)
    (DefaultHttpContent. chunk)

    (instance? ByteBuffer chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer ^ByteBuffer chunk))

    (instance? InputStream chunk)
    (DefaultHttpContent. (input-stream-chunk chunk))

    (instance? File chunk)
    (DefaultHttpContent. (file-chunk chunk))

    (satisfies? ChunkEncoder chunk)
    (any->http-object chunk)

    :else
    (throw (IllegalArgumentException. "Cannot coerce to HttpContent"))))

(defn content-chunk?
  "Predicate to check for ChunkEncoder compliance"
  [x]
  (or (bytes? x)
      (string? x)
      (instance? ByteBuf x)
      (instance? HttpContent x)
      (instance? ByteBuffer x)
      (instance? InputStream x)
      (instance? File x)
      (satisfies? ChunkEncoder x)))
