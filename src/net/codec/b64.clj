(ns net.codec.b64
  "Base64 encoding and decoding"
  (:import java.util.Base64))

(defn ^String b->b64
  "Convert a byte-array to base64"
  [^bytes b]
  (String. (.encode (Base64/getEncoder) b)))

(defn ^String s->b64
  "Convert a string to base64"
  [^String s]
  (b->b64 (.getBytes s "UTF-8")))

(defn ^"[B" b64->b
  ""
  [^String s]
  (.decode (Base64/getDecoder) (.getBytes s)))

(defn ^String b64->s
  ""
  [^String s]
  (String. (b64->b s)))
