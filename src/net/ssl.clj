(ns net.ssl
  "Clojure glue code to interact with the horrible JVM SSL code"
  (:require [clojure.java.io    :refer [input-stream]])
  (:import java.security.KeyStore
           java.security.KeyFactory
           java.security.PrivateKey
           java.security.cert.X509Certificate
           java.security.cert.CertificateFactory
           java.security.spec.PKCS8EncodedKeySpec
           java.io.ByteArrayInputStream
           javax.xml.bind.DatatypeConverter
           io.netty.handler.ssl.SslContextBuilder
           io.netty.handler.ssl.ClientAuth))

(defn ^X509Certificate s->cert
  [factory input]
  (.generateCertificate factory (ByteArrayInputStream. (.getBytes input))))

(defn ^PrivateKey s->pkey
  "When reading private keys, we unfortunately have to
  read PKCS8 encoded keys, short of pulling-in bouncy castle :-(
  Since these keys are usually DER encoded, they're unconvienent to
  have laying around in strings. We resort to base64 encoded DER here."
  [factory input]
  (let [bytes (DatatypeConverter/parseBase64Binary input)
        kspec (PKCS8EncodedKeySpec. bytes)]
    (.generatePrivate factory kspec)))

(defn ->chain
  [cert-spec]
  (if (sequential? cert-spec)
    (into-array X509Certificate (map s->cert cert-spec))
    (into-array X509Certificate [(s->cert cert-spec)])))

(defn client-context
  "Build an SSL client context for netty"
  [{:keys [bundle password cert pkey authority]}]
  (let [builder (SslContextBuilder/forClient)]
    (when (and cert pkey authority)
      (let [cert-fact (CertificateFactory/getInstance "X.509")
            key-fact  (KeyFactory/getInstance "RSA")]
        (let [cert      (s->cert cert-fact cert)
              authority (s->cert cert-fact authority)
              pkey      (s->pkey key-fact pkey)
              chain     (into-array X509Certificate [cert authority])]
          (.keyManager builder ^PrivateKey pkey chain)
          (.trustManager builder chain))))
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

(defn server-context
  "Build an SSL client context for netty"
  [{:keys [pkey password cert auth-mode ca-cert ciphers
           cache-size session-timeout]}]
  (let [certs    (->chain cert)
        password (char-array password)
        key-fact (KeyFactory/getInstance "RSA")
        pkey     (s->pkey key-fact pkey)
        builder  (SslContextBuilder/forServer pkey password certs)]
    (when ciphers
      (.ciphers ciphers))
    (when ca-cert
      (.trustManager builder (into-array X509Certificate (->chain ca-cert))))
    (when cache-size
      (.cacheSize (long cache-size)))
    (when session-timeout
      (.sessionTimeout (long session-timeout)))
    (when auth-mode
      (.clientAuth builder (case auth-mode
                             :auth-mode-optional ClientAuth/OPTIONAL
                             :auth-mode-require  ClientAuth/REQUIRE
                             :auth-mode-none     ClientAuth/NONE
                             (throw (ex-info "invalid client auth mode" {})))))
    (.build builder)))
