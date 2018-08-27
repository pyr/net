# Overview

## The need for asynchronous network programming

Before diving into the details of net, let's look at what it brings
and why it may be needed. Conventional wisdom - especially on the
JVM - mandates that network programming lends itself better to
synchronous programming.

Indeed, it is easier to reason as if each discussion with a client
happened in isolation:

```
(defn handle-client [client]
  (future ;; One thread per client
    (loop []
      (let [command (read-command client)] ;; Wait for I/O
        (when-not (= :quit command)
          (handle-command command)
	      (recur))))))
```

In the code above, an entire thread is stopped, waiting for I/O for
each read-command call. Unless commands come-in constantly, this means
that most of the thread's time will be wasted waiting on I/O.

Handle each client in a separate thread - or processus as was standard
on UNIX platforms - reaches system limits quite easily. The JVM incurs
a penalty for each created thread and the thread scheduler is not
meant to efficiently manage thousand or millions of threads.

Some [platforms](http://erlang.org) have been specifically built to
permit this programming approach for large number of clients, through
the use of low-cost lightweight threads.

Asynchronous programming allows waiting for I/O on several operations
at once, and being signalled of I/O events.  In C, this is what the
`select` and `poll` system calls provide. On the JVM, this is the role
of the `java.nio` subsystem, which allows calling `select` on a
`selector`.

Most programming platforms extend this programming model to provide an
*event loop*: A mechanism to register callbacks associated with I/O
events.

The downside of asynchronous programming is that it is up to the
programmer to keep track of the current state of a connection, since
I/O is decoupled from processing. This can be done through the use of
*finite state machines*, commonly used in asynchronous programming.
When working with event loops, this involves registering callbacks for
events.  Here is a canonical example on top of Javascript:

```
(def xhr goog.net.XhrIo)

(.send xhr "http://spootnik.org" (fn [response] (handle-body response)))
```

As you may imagine, nesting several callbacks quickly leads to
confusing code which is hard to maintain.

Fortunately, Clojure provides an elegant way to deal with pending
results thanks to the
[core.async](https://github.com/clojure/core.async) library.  The
channel abstraction, inspired by [golang](https://golang.org) allows
writing code that looks synchronous but parks lightweight threads when
waiting for results:

```
(def xhr goog.net.XhrIo)

(defn send-xhr [url]
  (let [p (promise-chan)]
     (.send xhr url (fn [response] (put! p response)))
     p))
	 
(go
  (let [response (<! (send-xhr "http://spootnik.org"))]
    (handle-body response)))
```

On the JVM, [netty](http://netty.io) provides a very efficient event loop
implementation with mechanisms to build processing pipelines. The idea
behind pipelines is to break the handling of incoming and outgoing payloads 
into several independent finite state machines which each hand over their
result to the next handler.

This approach is very similar to the composition of ring wrappers
commonly used in clojure. Another way to look at the pipeline idea
is the common onion analogy, each step in the pipeline is a layer
of an onion, down to the actual *business logic* handler.

Let's consider the case of a simple RPC server. The service is
exposed over TLS and the protocol follows the standard telnet
CRLF line-based frames protocol.

Incoming commands take the form <opcode>:

```
"start-engine"
"stop-engine"
"thrust-engine"
```

Responses may be either `OK` or `ERR <message>`.

To build an appropriate pipeline, we will need:

- TLS handling
- line-by-line frame extraction
- coercion to string
- handing-out to the handler


```
+--------------------------+
|                        ^ |
|  | TLS handling        | |
|  v                       |
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
|    our handler           |
|                          |
+--------------------------+
```

Netty provides simple facilities to create this layered approach,
allowing library consumers to plug-in their logic handling code
without worrying about handling low-level payload handling code.

In addition to this simple programming layer, Netty also handles
a thread-pool to execute handler callbacks, separating threads
dedicated to I/O waiting from threads dedicated to processing.

While avoiding the expensive model of reserving one thread
per connection, this allows for scalable handling of a large
number of connections.

As a thin wrapper on top of Netty, net provides Clojure facilities to
build these handling pipelines in - hopefully - idiomatic clojure.
For a more all-encompassing framework on top of Netty, readers are
encouraged to also consider [aleph](http://aleph.io). Net aims to
provide thin wrapping for people familiar with netty and happy to work
mostly with [core.async](https://github.com/clojure/core.async) as
their asynchronous channel interface.

Now that you hopefully have a better idea of each foundation net
builds on top of, you can look at how to build servers and clients
with it. Here are a few additional walk-through guides:

- [An echo server](echo.md)
- [A chat server](chat.md)
- [TLS support](tls.md)
- [HTTP servers](http-server.md)
