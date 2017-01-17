# TLS support

Build network services often entails dealing with transport
authentication, authorization and security.

TLS provides the latter although it is notoriously complex
to set-up on the JVM. Thankfully, Netty abstracts most of the
complexity away. Net adds a bit of syntactic sugar on top
of it and provides facilities to ease certificate serialization.

We will look at how to add a TLS step to our [echo server](echo.md)
pipeline.

We only need an additional dependency when creating our namespace:

```clojure
(ns server.tls-echo
  (:require [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]
            [net.ssl         :as ssl]
            [net.tcp         :as tcp]))
```

We now need to build an **SSL Context** for Netty to use:

```clojure
(defn build-ssl-context
  []
  (let [root-dir (System/getenv "ECHO_CERT_DIR")]
    (ssl/server-context
      {:pkey (str root-dir "/echo.pkey.p8")
       :cert (str root-dir "/echo.cert.pem")})))
```

In the above, we create an **SSL Context** for a given
private key and certificate. Certificates may be given
inline or point to files. By default, this is inferred
from the size of inputs, but can be controlled with the
`:storage` key of the argument map.

As is standard on the JVM, private keys need to be
provide in the **PKCS8** format, not in **PEM** format as
is more commonly the case.

Additional behavior may be forced on the **SSL Context** such as
forced client certificate authentication. For a full description see
the documentation for
[`ssl/server-context`](/net.ssl.html#var-server-context).

The rest of the pipeline code is almost unchanged:

```clojure
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
```

We can now bridge the two functions when starting up our server:

```clojure
(defn -main [& _]
  (tcp/server {:handler (pipeline (build-ssl-context))} "localhost" 1337))
```

You may run this TLS server with `env ECHO_CERT_DIR=/path/to/certs
lein run -m server.tls-echo` within the net project. To try it out,
you can use `openssl s_client -connect localhost:1337`
