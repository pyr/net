(ns net.codec.b64
  "Base64 encoding and decoding"
  (:import javax.xml.bind.DatatypeConverter))

(defn ^String b->b64
  "Convert a byte-array to base64"
  [^bytes b]
  (-> b DatatypeConverter/printBase64Binary .trim))

(defn ^String s->b64
  "Convert a string to base64"
  [^String s]
  (-> s .getBytes b->b64))

(defn ^"[B" b64->b
  ""
  [^String s]
  (-> s DatatypeConverter/parseBase64Binary))

(defn ^String b64->s
  ""
  [^String s]
  (-> s b64->b String. .trim))
