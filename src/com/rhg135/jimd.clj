(ns com.rhg135.jimd
  (:gen-class)
  (:require [com.rhg135.jimd.impl :as impl]))

(defn -main
  [& _]
  impl/server)
