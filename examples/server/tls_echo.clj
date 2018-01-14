(ns server.tls-echo
  (:require [net.tcp         :as tcp]
            [net.ssl         :as ssl]
            [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]))

(defn build-ssl-context
  []
  (let [base-path (System/getenv "ECHO_CERT_DIR")]
    (ssl/server-context
     {:pkey (str base-path "/echo.pkey.p8")
      :cert (str base-path "/echo.cert.pem")})))

(defn pipeline
  [ssl-ctx]
  (pipeline/channel-initializer
   [(ssl/handler-fn ssl-ctx)
    (pipeline/line-based-frame-decoder)
    pipeline/string-decoder
    pipeline/string-encoder
    pipeline/line-frame-encoder
    (pipeline/with-input [ctx msg]
      (channel/write-and-flush! ctx msg))]))

(defn -main
  [& _]
  (tcp/server {:handler (pipeline (build-ssl-context))} "localhost" 1337))
