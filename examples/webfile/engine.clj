(ns webfile.engine
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async         :as a]
            [clojure.string             :refer [join]]
            [clojure.tools.logging      :refer [info]])
  (:import java.nio.file.StandardOpenOption
           java.nio.file.OpenOption
           java.nio.file.FileSystem
           java.nio.ByteBuffer
           java.nio.channels.Channel
           io.netty.buffer.ByteBufHolder
           io.netty.buffer.ByteBuf
           java.nio.channels.FileChannel))

(def fs (delay (java.nio.file.FileSystems/getDefault)))

(def open-options
  (into-array
   OpenOption
   [StandardOpenOption/WRITE
    StandardOpenOption/CREATE
    StandardOpenOption/TRUNCATE_EXISTING]))

(def empty-response
  {:status  200
   :headers {"Connection"     "close"
             "Content-Length" "0"}
   :body    ""})

(defn content-response
  [status content]
  {:status  status
   :headers {"Connection"     "close"
             "Content-Length" (str (count content))}
   :body    content})

(defn path-for
  [root & elems]
  (.getPath ^FileSystem @fs root (into-array String [(join "-" elems)])))

(defn write-nio-buffer
  [^FileChannel chan ^ByteBuffer buf]
  (.write chan buf))

(defn buffers-from
  [http-content]
  (let [bb ^ByteBuf (.content ^ByteBufHolder http-content)]
    (seq (.nioBuffers bb))))

(defn write-bufs
  [root uri i http-content]
  (let [path (path-for root uri (format "%02d" i))
        chan (FileChannel/open path open-options)]
    (doseq [buf (buffers-from http-content)]
      (write-nio-buffer chan buf))
    (.close chan))
  (inc i))

(defn body-for
  [uri]
  (cond
    (re-find #"(?i)stream" uri)
    {:status  200
     :headers {"Content-Transfer" "chunked"}
     :body    (let [body (a/chan)]
                (a/go
                  (dotimes [i 5]
                    (a/<! (a/timeout 1000))
                    (a/>! body "foobar\n"))
                  (a/close! body))
                body)}

    (re-find #"(?i)delay" uri)
    (a/go
      (a/<! (a/timeout 3000))
      {:status  200
       :headers {"Connection" "close"}
       :body    "sorry, running late!\n"})

    ::else
    {:status 200
     :headers {"Connection" "close"}
     :body    "A standard body\n"}))

(defmulti handle-operation (fn [_ op _ _] op))

(defmethod handle-operation :get
  [_ _ uri _]
  (body-for uri))

(defmethod handle-operation :put
  [{:keys [root]} _ uri body]
  (a/go
    (loop [i 0]
      (let [http-content (a/<! body)]
        (if (nil? http-content)
          empty-response
          (recur (write-bufs root uri i http-content)))))))

(defmethod handle-operation :error
  [& args]
  (content-response 500 (pr-str args)))

(defmethod handle-operation :default
  [& _]
  (content-response 400 "no such operation"))

(defrecord StoreEngine [root]
  component/Lifecycle
  (start [this]
    this)
  (stop [this]
    this))

(defn make-engine
  [root]
  (map->StoreEngine {:root root}))
