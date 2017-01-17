# A chat server

Chat servers are the hello world of asynchronous
programming frameworks, because they are perfect
async programming candidates. Indeed chat servers
combine:

- Waiting on I/O on potentially large numbers of connections
- Maintained TCP sessions
- Cross connection interaction

Our chat protocol will be simple and limited:

- Connections are made to TCP port 1337.
- Messages are line delimited.
- Messages are played back.
- A message of quit will terminate a chat session.

To implement our chat server, we can reuse the same pipeline we
built for our [echo server](echo.md). The last step of the
pipeline will introduce new concepts however.

In the [echo server](echo.md), we were only concerned with
incoming payloads and no other connection activity.

Our chat server also needs to know when new connections come
in to register them into a group.

To do this, we will diverge from our previous server implementation
in two ways:

- We will construct a Netty
  [**ChannelGroup**](http://netty.io/4.1/api/io/netty/channel/group/ChannelGroup.html)
  to hold our list of connections.
- Instead of using
  [`with-input`](/net.ty.pipeline.html#var-with-input), we will build
  a full-fledged adapter thanks to the
  [`HandlerAdapter`](/net.ty.pipeline.html#var-HandlerAdapter)
  protocol.

We can start with a similar namespace definition than previously:

```clojure
(ns server.chat
  (:require [net.tcp         :as tcp]
            [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]))
```

Next we can write our adapter, assuming it is given a channel-group
as input.

```clojure
(defn chat-adapter
  [channel-group]
  (reify
    pipeline/HandlerAdapter
    (channel-read [this ctx msg]
      (if (= msg "quit")
        (do (channel/write-and-flush! ctx "bye!")
            (channel/close! ctx))
        (let [src (channel/channel ctx)]
          (doseq [dst channel-group :when (not= dst src)]
            (channel/write-and-flush! dst msg)))))

    pipeline/ChannelActive
    (channel-active [this ctx]
      (channel/add-to-group channel-group (channel/channel ctx))
      (channel/write-and-flush! ctx "welcome to the chat"))))
```

Let's walk through the above. We start by producing
an anonymous realization of the  [`HandlerAdapter`](/net.ty.pipeline.html#var-HandlerAdapter)
protocol with `reify`, adding the `ChannelActive` protocol as well.

We implement the following signatures:

- `channel-active`: called when a new connection is registered.
- `channel-read`: called for each new payload.

In `channel-active`, we add the new channel to our channel group and then
write a welcome message.

In `channel-read`, if we are signalled to quit, we close the
channel. Closed channels are automatically removed from channel
groups, so no need to take care of this step. Otherwise, we loop
through all channels in the channel group and write out the message,
except to the one which produced it.

If we want to compare with the previous [echo server](echo.md)
implementation, we could rewrite our `with-input` step as a handler
adapter as well:

```clojure
(defn echo-adapter
  []
  (reify
    pipeline/HandlerAdapter
	(channel-read [this ctx msg] (channel/write-and-flush! ctx msg))))
```

Now that we have our adapter implementation, we can write our pipeline:

```
(defn pipeline
  []
  (let [group (channel/channel-group "clients")]
    (pipeline/channel-initializer
     [(pipeline/line-based-frame-decoder)
      pipeline/string-decoder
      pipeline/string-encoder
      pipeline/line-frame-encoder
      (pipeline/read-timeout-handler 60)
      (pipeline/make-handler-adapter (chat-adapter group))])))
```

Notice the addition of
[`read-timeout-handler`](/net.ty.pipeline.html#var-read-timeout-handler)
which will close channels with no activity for more than 60 seconds.

Our last step is unchanged:

```clojure
(tcp/server {:handler (pipeline)} "localhost" 1337)
```

To test this example server, you may run `lein run -m server.chat` within the net project.

