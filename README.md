net: the clojure netty companion
================================

Net provides a clojure foundation to implement asynchronous
networking based on netty.

It is much narrower in scope and features than [aleph](https://github.com/ztellman/aleph), which you might
want to look into.

Pending documentation, here is what you can find in net today:

- Light facades around netty concepts such as channels, pipelines, channel initializers and bootstraps
- Facilities to create SSL client and server contexts from PEM files
- Ring-like HTTP(S) server facade
- HTTP(S) client
- Simple interface to create TCP server with optional SSL support

## License

Copyright © 2015, 2016 Pierre-Yves Ritschard, MIT License.
