(ns net.ssl
  "Clojure glue code to interact with the horrible JVM SSL code"
  (:require [net.ty.pipeline :refer [*channel*]]
            [clojure.java.io :refer [input-stream]])
  (:import java.security.KeyStore
           java.security.KeyFactory
           java.security.PrivateKey
           java.security.cert.X509Certificate
           java.security.cert.CertificateFactory
           java.security.spec.PKCS8EncodedKeySpec
           java.io.ByteArrayInputStream
           javax.xml.bind.DatatypeConverter
           io.netty.handler.ssl.SslContext
           io.netty.handler.ssl.SslContextBuilder
           io.netty.handler.ssl.ClientAuth))

(def ^:dynamic *storage* :guess)

(defn cert-string
  [input]
  (cond
    (and (map? input) (:path input))
    (some-> input :path slurp)

    (map? input)
    (:data input)

    (not (string? input))
    (throw (ex-info "cannot convert input to cert string" {}))

    (= :file *storage*)
    (slurp input)

    (= :data *storage*)
    input

    (< (count input) 256)
    (slurp input)

    :else
    input))

(defn cert-bytes
  [input]
  (if (instance? (Class/forName "[B") input)
    input
    (.getBytes (cert-string input))))

(defn ^X509Certificate s->cert
  [factory input]
  (.generateCertificate factory (ByteArrayInputStream. (cert-bytes input))))

(defn ^PrivateKey s->pkey
  "When reading private keys, we unfortunately have to
  read PKCS8 encoded keys, short of pulling-in bouncy castle :-(
  Since these keys are usually DER encoded, they're unconvienent to
  have laying around in strings. We resort to base64 encoded DER here."
  [^KeyFactory factory input]
  (let [bytes (DatatypeConverter/parseBase64Binary (cert-string input))
        kspec (PKCS8EncodedKeySpec. bytes)]
    (.generatePrivate factory kspec)))

(defn ->chain
  [^CertificateFactory fact cert-spec]
  (if (sequential? cert-spec)
    (into-array X509Certificate (map (partial s->cert fact) cert-spec))
    (into-array X509Certificate [(s->cert fact cert-spec)])))

(defn client-context
  "Build an SSL client context for netty"
  [{:keys [bundle password cert pkey authority storage]}]
  (let [storage (or storage :guess)
        builder (SslContextBuilder/forClient)]
    (binding [*storage* storage]
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
      (.build builder))))

(defn server-context
  "Build an SSL client context for netty"
  [{:keys [pkey password cert auth-mode ca-cert ciphers
           cache-size session-timeout storage]}]
  (binding [*storage* (or storage :guess)]
    (let [fact     (CertificateFactory/getInstance "X.509")
          certs    (->chain fact cert)
          password (char-array password)
          key-fact (KeyFactory/getInstance "RSA")
          pk       (s->pkey key-fact pkey)
          builder  (if (string? password)
                     (SslContextBuilder/forServer pk (char-array password) certs)
                     (SslContextBuilder/forServer pk certs))]
      (when ciphers
        (.ciphers ciphers))
      (when ca-cert
        (.trustManager builder
                       (into-array X509Certificate (->chain fact ca-cert))))
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
      (.build builder))))

(defn handler-fn
  ([^SslContext ctx host port]
   (fn []
     (.newHandler ctx (.alloc *channel*) host port)))
  ([^SslContext ctx]
   (fn []
     (.newHandler ctx (.alloc *channel*)))))
