# HTTP servers

Net's HTTP server facade delivers something familiar to users with
prior exposure to [ring](https://github.com/ring-clojure/ring1) and [jet](https://github.com/mpenet/jet).

It differs in the following important ways:

- The body of the ring request passed to handlers may be a
  [core.async](https://github.com/clojure/core.async) channel, in
  which case it will produce a list of chunks for the body.
- Handlers may return a
  [core.async](https://github.com/clojure/core.async) channel, in
  which case it is expected to produce a single value: the response
  map.
- Whether it was obtained directly or through a channel, the `:body`
  key within a response map may either be a payload to be sent out or
  a [core.async](https://github.com/clojure/core.async) channel, in
  which case chunks will be read from it and sent out.

Here is the simplest possible use of the interface:

```clojure
(ns server.http
  (:require [net.http.server :as http]))

(defn handler
  [request]
  {:status  200
   :headers {"Content-Type"   "text/plain"
             "Content-Length" "6"}
   :body    "hello\n"})

(defn -main
  [& _]
  (http/run-server {:ring-handler handler}))
```
