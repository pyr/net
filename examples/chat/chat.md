# An example chat server

Let's look at building the hello world of asynchronous network
programming frameworks: a chat server.

Chat servers lend themselves well to asynchronous implementations
because they involve both potentially large numbers of concurrent
connections waiting for I/O, and broadcasts between connections.

Let's devise the most simplistic protocol for a chat server fist:

- Connections are made to TCP port 1337.
- Messages are line delimited.
- Each message is broadcast to every online client.
- A message of quit disconnects the client.

To do this with net, we will create what's called a Netty *ChannelAdapter*,
which will sit at the bottom end of our input pipeline. Which will look like this:

```
+--------------------------+
|                          |
|  | line frame decoder    |
|  v                       |
+--------------------------+
|                          |
|  | string decoding       |
|  v                       |
+--------------------------+
|                        ^ |
|    string encoding     | |
|                          |
+--------------------------+
|                        ^ |
|    line frame encoder  | |
|                          |
+--------------------------+
|                          |
| |  read timeout handler  |
| v                        |
+--------------------------+
|                          |
|    our chat handler      |
|                          |
+--------------------------+
```

This onion approach to adapters allows us to create a handler, knowing that payload
will come as a well formed string.

We can start by creating our namespace:

```clojure
(ns chat.server
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]
            [net.ty.pipeline  :as pipeline]
            [net.ssl          :as ssl]))
```

Next we will create our chat adapter:

```clojure
(defn chat-adapter
  [channel-group]
  (reify
    pipeline/HandlerAdapter
    (is-sharable? [this]
      true)
    (capabilities [this]
      #{:channel-active :channel-read})
    (channel-active [this ctx]
      (channel/add-to-group channel-group (channel/channel ctx))
      (channel/write-and-flush! ctx "welcome to the chat"))
    (channel-read [this ctx msg]
      (if (= msg "quit")
        (do (channel/write-and-flush! ctx "bye!")
            (channel/close! ctx))
        (let [src (channel/channel ctx)]
          (doseq [dst channel-group :when (not= dst src)]
            (channel/write-and-flush! dst msg)))))))
```

Let's step through things
