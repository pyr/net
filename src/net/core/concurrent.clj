(ns net.core.concurrent
  "Simple wrapper for basic j.u.concurrent tasks (or future alternatives)"
  (:import
   (java.util.concurrent
    Executors
    ExecutorService
    Future)))

(defn executor
  "Returns ExecutorService.
`type` can be :single, :cached, :fixed or :scheduled, this matches the
corresponding Java instances"
  ^ExecutorService
  ([type] (executor type nil))
  ([type {:keys [thread-factory num-threads]
          :or {num-threads 1
               thread-factory (Executors/defaultThreadFactory)}}]
   (case type
     :single (Executors/newSingleThreadExecutor thread-factory)
     :cached  (Executors/newCachedThreadPool thread-factory)
     :fixed  (Executors/newFixedThreadPool (int num-threads) thread-factory)
     :scheduled (Executors/newScheduledThreadPool (int num-threads) thread-factory))))

(defprotocol Executable
  (submit! [executor-service f]
    "Submits the fn to specified executor, returns a Future"))

(extend-type ExecutorService
  Executable
  (submit! [executor f]
    (.submit executor ^Callable f)))
