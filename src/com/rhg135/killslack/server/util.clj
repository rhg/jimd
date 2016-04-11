(ns com.rhg135.killslack.server.util)

(defn named-thread*
  [named f]
  (doto (Thread. f)
    (.setName (name named))
    (.start)))

(defmacro named-thread
  [named & body]
  `(named-thread* ~named (fn [] ~@body)))

(defmacro loop-when-let
  [binding & body]
  `(loop []
     (when-let ~binding
       ~@body
       (recur))))
