net: A clojure Netty companion
===============================

[![Build Status](https://secure.travis-ci.org/pyr/net.png)](http://travis-ci.org/pyr/net)

Net provides a clojure foundation to implement asynchronous networking
based on Netty.

It is much narrower in scope and features than
[aleph](https://github.com/ztellman/aleph), which you might want to
look into if you want a full-fledged asynchronous programming toolkit
for clojure.

**net** is rather geared towards people with prior Netty knowledge
wanting to keep the same workflow in, *hopefully*, idiomatic Clojure,
and nothing but standard clojure facilities.

- Light facades around Netty concepts such as channels, pipelines,
  channel initializers and bootstraps
- Facilities to create TLS client and server contexts from PEM files
- Ring-like HTTP(S) server facade
- HTTP(S) client
- Simple interface to create TCP server with optional TLS support
- Clojure [core.async](https://github.com/clojure/core.async) support
- Memory-efficient transducers for collections of ByteBufs

## Documentation

Net now has full [API Documentation](http://pyr.github.io/net) and
[Guides](http://pyr.github.io/net/intro.html).

## Installation

```clojure
    [[spootnik/net "0.3.3-beta28"]]
```

## Changelog

### 0.3.3-beta32

- Reliability fixes in HTTP client
- Allow disabling of SSL certificate validation
- Allow more options to be supplied to the HTTP server

### 0.3.3-beta28

- Ensure lost clients do not result in filled core.async channel buffers

### 0.3.3-beta27

- Start introducing bridged channels for tighter integration between Netty and core.async.
- Ensure event-loop-groups can be parameterized
- Allow supplying query args as a map in HTTP requests

### 0.3.3-beta26

- Play nice with non-epoll systems

### 0.3.3-beta23

- Adapt `net.http.client` to match server behavior: responses contain
  a `core.async` **Channel** body
- Handle *transforms* over the body, i.e: help callers supply a
  transducer when requesting
- Better handle error conditions
- Add `net.core.async/timeout-pipe` to help ensure upstream feeds a
  channel fast enough
  

### 0.3.3-beta19

- Handle Netty out-of-band `/bad-request` requests from `HttpObjectDecoder`

### 0.3.3-beta18

- Improve behavior on early downstream channel closing

### 0.3.3-beta17

- Fix a memory leak when closing the body channel from a handler
- Add clojure tools.deps.alpha support

### 0.3.3-beta16

- Fix all reflection warnings
- Fix calls to deprecated signatures in Netty

### 0.3.3-beta15

- No more dependency on `tools.logging`
- Better dispatch for pipelines

### 0.3.3-beta14

- Depend on Netty 4.1.19.Final
- Add ByteBuf collection transducers
- Handle Http server callback on an executor
- Improve Netty facade
- Add ByteBuf manipulation tooling
- Circumvent CLJ-1814 as much as possible

### 0.3.3-beta9

- Depend on Netty 4.1.8.Final

### 0.3.3-beta8

- specs for http server options

### 0.3.3-beta7

- Small fixes

### 0.3.3-beta6

- Allow user-supplied executor for responses (thanks @mpenet).
- Fix HTTP-related regressions introduced by reflection work.

### 0.3.3-beta4

- Ensure all calls do not need reflection.
- Correctly terminate clients in tcp server shutdown fn.

### 0.3.3-beta3

- Break `HandlerAdapter` into several protocols

### 0.3.3-beta2

- Add documentation and guides
- Improved specs

### 0.3.3-beta1

- Rework HTTP support to be aligned with
  [jet](https://github.com/mpenet/jet)
- Provide a single HTTP server interface, which allows aggregating or
  streaming body content.

### 0.2.20

- Allow user-supplied max body size

### 0.2.19

- Bugfix release for 0.2.18
- More restrictive specs

### 0.2.18

- Convenience macros to create encoders and decoders.

### 0.2.17

- `core.spec` schemas instead of prismatic schema
- Rely on Netty 4.1.6
- Additional sugar for futures and channels

## Thanks

- CRHough (https://github.com/CRHough) for a number of small fixes.
- Max Penet (https://github.com/mpenet) for most of the reflection fixes.

## License

Copyright © 2015, 2016, 2017 Pierre-Yves Ritschard, MIT License.
