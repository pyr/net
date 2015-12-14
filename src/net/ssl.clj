(ns net.ssl
  "Clojure glue code to interact with the horrible JVM SSL code"
  (:require [clojure.java.io    :refer [input-stream]])
  (:import java.security.KeyStore
           java.security.PrivateKey
           java.security.cert.X509Certificate
           io.netty.handler.ssl.SslContextBuilder))

(defn netty-client-context
  "Build an SSL client context for netty"
  [{:keys [bundle password]}]
  (let [builder (SslContextBuilder/forClient)]
    (when (and bundle password)
      (let [keystore (KeyStore/getInstance "pkcs12")]
        (with-open [stream (input-stream bundle)]
          (.load keystore stream (char-array password)))
        (let [alias (first (enumeration-seq (.aliases keystore)))
              k     (.getKey keystore alias (char-array password))
              chain (.getCertificateChain keystore alias)]
          (.keyManager builder ^PrivateKey k (into-array X509Certificate (seq chain)))
          (.trustManager builder (into-array X509Certificate (seq chain))))))
    (.build builder)))
