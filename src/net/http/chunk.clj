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
  (chunk->http-object [chunk] "Convert Chunk to http-object"))

;;
;; Provide ChunkEncoder implementation for common types,
;; This allows net to be flexible
(extend-protocol ChunkEncoder
  (Class/forName "[B")
  (chunk->http-object [chunk]
    (DefaultHttpContent. (Unpooled/wrappedBuffer ^"[B" chunk)))

  ByteBuffer
  (chunk->http-object [chunk]
    (DefaultHttpContent. (Unpooled/wrappedBuffer chunk)))

  ByteBuf
  (chunk->http-object [chunk]
    (DefaultHttpContent. chunk))

  InputStream
  (chunk->http-object [chunk]
    (DefaultHttpContent. (input-stream-chunk chunk)))

  File
  (chunk->http-object [chunk]
    (DefaultHttpContent. (file-chunk chunk)))

  String
  (chunk->http-object [chunk]
    (DefaultHttpContent. (Unpooled/wrappedBuffer (.getBytes chunk "UTF8"))))

  HttpContent
  (chunk->http-object [chunk] chunk))

(defn content-chunk?
  "Predicate to check for ChunkEncoder compliance"
  [x]
  (satisfies? ChunkEncoder x))
