(ns net.core.concurrent
  "Simple wrapper for basic j.u.concurrent tasks (or future alternatives)"
  (:import java.util.concurrent.Executors
           java.util.concurrent.ExecutorService
           java.util.concurrent.Future
           java.util.concurrent.ThreadFactory))

(defn ^Thread$UncaughtExceptionHandler exception-catcher
  [x]
  (cond
    (instance? Thread$UncaughtExceptionHandler x)
    x

    (fn? x)
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread e]
        (x {:thread thread :exception e})))

    ::else
    (throw (IllegalArgumentException. "Invalid exception-catcher parameter"))))

(defn uncaught-catching-thread-factory
  [catcher-fn]
  (let [factory     (Executors/defaultThreadFactory)
        adjust-name (fn [^Thread t] (.setName t (str "net-core-" (.getName t))))]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (.newThread factory runnable)
          (adjust-name)
          (.setUncaughtExceptionHandler (exception-catcher catcher-fn)))))))

(defn executor
  "Returns ExecutorService.
`type` can be :single, :cached, :fixed or :scheduled, this matches the
corresponding Java instances"
  ^ExecutorService
  ([type] (executor type nil))
  ([type {:keys [thread-factory num-threads]
          :or   {num-threads    1
                 thread-factory (Executors/defaultThreadFactory)}}]
   (case type
     :single    (Executors/newSingleThreadExecutor thread-factory)
     :cached    (Executors/newCachedThreadPool thread-factory)
     :fixed     (Executors/newFixedThreadPool (int num-threads) thread-factory)
     :scheduled (Executors/newScheduledThreadPool (int num-threads) thread-factory))))

(defprotocol Executable
  (submit! [executor-service f]
    "Submits the fn to specified executor, returns a Future"))

(extend-type ExecutorService
  Executable
  (submit! [executor f]
    (.submit executor ^Callable f)))

(extend-protocol Executable
  nil
  (submit! [this f]
    (f)))

(defmacro with-executor
  [executor & body]
  `(submit! ~executor (fn [] ~@body)))
