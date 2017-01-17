# An echo server

Our first and most simple example is an echo server.
Each incoming payload is repeated.

Our protocol will be simple and limited:

- Connections are made to TCP port 1337.
- Messages are line delimited.
- Messages are played back

To do this with net, we will create what's called a Netty *ChannelAdapter*,
which will sit at the bottom end of our input pipeline:


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
|    our chat handler      |
|                          |
+--------------------------+
```

This onion approach to adapters allows us to create a handler, knowing
that payload will come as a well formed string.

We can start by creating our namespace:

```clojure
(ns server.echo
  (:require [net.tcp         :as tcp]
            [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]))
```

Next we will create our echo pipeline:

```clojure
(defn pipeline
  []
  (pipeline/channel-initializer
    [(pipeline/line-based-frame-decoder)
     pipeline/string-decoder
     pipeline/string-encoder
     pipeline/line-frame-encoder
     (pipeline/with-input [ctx msg]
       (channel/write-and-flush! ctx msg))]))
```

As you can see, our pipeline definition closely mimicks what we
described above, and all steps of the pipeline but the last are
provided for us:

- [`pipeline/line-based-frame-decoder`](/net.ty.pipeline.html#var-line-based-frame-decoder)
  splits input by CRLF delimited frames.
- [`pipeline/string-decoder`](/net.ty.pipeline.html#var-string-decoder)
  transforms incoming byte frames into strings.
- [`pipeline/string-encoder`](/net.ty.pipeline.html#var-string-decoder)
  transforms outgoing strings to byte frames.
- [`pipeline/line-frame-encoder`](/net.ty.pipeline.html#var-line-frame-encoder)
  appends CRLF to outgoing strings.
- [`pipeline/with-input`](/net.ty.pipeline.html#var-with-input) is a
  facility to write custom handlers for input.
  
The pipeline creation is wrapped in a call to
[`pipeline/channel-initializer`](/net.ty.pipeline.html#var-channel-initializer),
to create a
[**ChannelInitializer**](http://netty.io/4.1/api/io/netty/channel/ChannelInitializer.html)
out of it. The role of initializers is to create an instance of the
handling *finite state machine* for each incoming connection.

In our custom handler, we are given a
[ChannelHandlerContext](http://netty.io/4.1/api/io/netty/channel/ChannelHandlerContext.html)
and the input message. The context is Netty's representation of the
connection and may be used to write to it (through
[`channel/write!`](/net.ty.channel.html#var-write.21), or
[`channel/write-and-flush!`](/net.ty.channel.html#var-write-and-flush.21)).

Last, we can start our server:

```clojure
(tcp/server {:handler (pipeline)} "localhost" 1337)
```

To test this example server, you may run `lein run -m server.echo` within the net project.

