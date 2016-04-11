(ns com.rhg135.jimd.impl
  (:require
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [com.rhg135.killslack.server.net :as net]
    [com.rhg135.killslack.server.util :refer (loop-when-let)]
    [taoensso.timbre :as t])
  (:import
    [java.util UUID]))

(def conj-set
  "Adds to a collection (which defaults to a set)"
  (fnil conj #{}))

(defn say
  "Queues a write of the json representation of `obj`. Returns the agent."
  [out obj]
  (doto out
    (send-off #(doto %
                 (.write (cheshire/generate-string obj))
                 (.flush)))))

(defn clean-channel
  "Removes a channel if not marked as permanent or has nicks in it"
  [m channel]
  (let [{:keys [permanent? nicks]} (get-in m [:channels channel])]
    (if-not (or permanent? (seq nicks))
      (update m :channels dissoc channel)
      m)))

(defn- clean-channels
  "Cleans up the `channels` using `clean-channel`"
  [m channels]
  (reduce clean-channel m channels))

(defn- remove-nick-from-channels
  [m' nick channels]
  (reduce (fn [a v]
            (update-in a [v :nicks] disj nick))
          m'
          channels))

(defn clean-nick
  "Removes a nick if it doesn' have any connections
  also cleans all channels nick was in if so"
  [m nick]
  (let [{:keys [channels connections]} (get-in m [:nicks nick])]
    (if (empty? connections)
      (-> m
          (update :channels remove-nick-from-channels nick channels)
          (clean-channels channels)
          (update :nicks dissoc nick))
      m)))

(defn clean-id
  "Makes sure the current nick for `id` is cleaned up as per `clean-nick`
  Then it's removed"
  [m id]
  (let [{:keys [nick]} (get-in m [:ids id])]
    (-> m
        (update-in [:nicks nick :connections] disj id)
        (clean-nick nick)
        (update :ids dissoc id))))

(defn add-id
  [m id out nick]
  (-> m
      (assoc-in [:ids id] {:out out :nick nick})
      (update-in [:nicks nick :connections] conj-set id)))

(defn- change-nick
  "Switches the current nick for `id` from `o` to `n`"
  [m id o n]
  (-> m
      (update-in [:nicks o :connections] disj id)
      (update-in [:nicks n :connections] conj-set id)
      (assoc-in [:ids id :nick] n)))

(defn set-nick
  "Sets the nick for `id` to `nick`.
  This clears out the old nick if needed"
  [m id nick]
  (let [old (get-in m [:ids id :nick])] ; TODO: add authentication
    (-> m
        (change-nick id old nick)
        (clean-nick old))))

(defn- update-channel
  [m f id channel]
  (let [nick (get-in m [:ids id :nick])]
    (-> m
        (update-in [:nicks nick :channels] f channel)
        (update-in [:channels channel :nicks] f nick))))

(defn join-channel
  "Joins the nick for `id` to `channel`"
  [m id channel]
  (update-channel m conj-set id channel))

(defn part-channel
  "Parts the nick for `id` from `channel`.
  Clears the channel if needed"
  [m id channel]
  (-> m
      (update-channel disj id channel)
      (clean-channel channel)))

(defn get-id
  [m id]
  (get-in m [:ids id]))

(def ^:dynamic *await*)
(def ^:dynamic *confirm*)

(declare -handle)

(defmulti -handle (fn [{{:strs [tag]} :message}] tag))

(defmethod -handle "nick!"
  [{:keys [id out state] {:strs [nick]} :message}]
  (send state set-nick id nick)
  (*confirm* out {:tag :nick :nick nick})
  (*await* state))

(defn channel?
  [x]
  (and (string? x)
       (.startsWith x "#")))

(defmethod -handle "join"
  [{:keys [id out state] {:strs [channel]} :message}]
  (if (channel? channel)
    (do
      (send state join-channel id channel)
      (*await* state)
      (*confirm* out {:tag :join :channel channel}))
    (*confirm* out {:tag :error :code :bad-channel})))

(defmethod -handle "part"
  [{:keys [id out state] {:strs [channel]} :message}]
  (if (channel? channel)
    (do
      (send state part-channel id channel)
      (*confirm* out {:tag :part :channel channel})
      (*await* state))
    (*confirm* out {:tag :error :code :bad-channel})))

(defn resolve-address
  "Returns a sequence of ids for something"
  [m address]
  (cond
    (contains? (:nicks m) address)
    (seq (get-in m [:nicks address :connections]))
    (contains? (:channels m) address)
    (mapcat (partial resolve-address m) (get-in m [:channels address :nicks]))
    :else nil))

(def ^:dynamic *reply*)

(defmethod -handle "msg"
  [{:keys [from state] {:strs [to message]} :message}]
  (let [state' @state
        ids (resolve-address state' to)
        from' (:nick (get-id state' from))
        outgoing {:tag :msg :from from' :to to :message message}]
    (run! (fn [id]
            (say (:out (get-id state' id)) outgoing))
          ids))
  (*reply* {:tag :msg}))

(def state
  (agent {}))

(defn handler ; TODO: allow verifying connections
  [^java.net.Socket socket]
  (with-open [_ socket]
    (.setSoTimeout socket 12000)
    (let [id (UUID/randomUUID)
          in (io/reader socket)
          out (agent (io/writer socket))
          nick (gensym)
          ctx ^{:type ::TCPContext} {:id id :out out :nick nick}]
      (send state add-id id out nick) ; TODO; use ctx here
      (await state)
      (try
        (binding [*await* await
                  *confirm* (comp await say) ; TODO: deprecated
                  *reply* (comp await (partial say out))]
          (*reply* {:tag :nick :nick nick})
          (loop-when-let [msg (cheshire/parse-stream in)]
                         (-handle (assoc ctx :message msg :state state))))
        (catch java.net.SocketTimeoutException _)
        (catch Exception e
          (t/error e "Client at socket" socket "caused a boo-boo"))
        (finally
          (send state clean-id id))))))

(defonce server
  (net/start-server! #'handler {:port 4004}))
