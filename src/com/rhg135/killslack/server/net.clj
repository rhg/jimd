(ns com.rhg135.killslack.server.net
  (:require [com.rhg135.killslack.server.util :refer :all])
  (:import [java.net ServerSocket Socket]))

(defn- ^Socket accept!
  [^ServerSocket server]
  (try
    (.accept server)
    (catch Exception _)))

(defn start-server!
  "Takes an unary handler and a map of options.
  Clients have their own thread!
  Returns a Closeable that stops the server. Clients stay open!"
  [handler {:keys [port]}]
  (let [server      (ServerSocket. port)
        thread-name (str "tcp-server-" port)]
    (named-thread thread-name
                  (loop-when-let [socket (accept! server)]
                    (named-thread (str port "-" (.getPort socket))
                                  (handler socket))))
    server))
