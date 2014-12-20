(ns me.moocar.jetty.websocket.server
  (:require [clojure.core.async :as async :refer [<!!]]
            [me.moocar.jetty.websocket :as websocket])
  (:import (java.nio ByteBuffer)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.websocket.server WebSocketHandler)
           (org.eclipse.jetty.websocket.servlet WebSocketCreator
                                                WebSocketServletFactory)))

(defn- websocket-handler 
  "WebSocketHandler that creates creator. Boilerplate"
  [creator]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (.setCreator factory creator))))

(defn- send-to-write-ch
  "When request contains a :response-bytes, converts them into a
  ByteBuffer and puts onto the requets's connection's write-ch"
  [request]
  (let [{:keys [conn]} request
        {:keys [write-ch]} conn]
    (when-let [buf (response-buf request)]
      (async/put! write-ch buf))
    nil))

(defn default-conn-f
  "Creates a default connection map"
  [request]
  (websocket/make-connection-map))

(defn- create-websocket
  "Returns a function that is invoked when a new websocket connection
  is accepted. The function takes the websocket creator, the HTTP
  request and response, and starts up the send pipeline, the
  connection lifecycle and returns the jetty listener object, required
  by the websocket handler"
  [request-ch new-conn-f handler-xf]
  (fn [this request response]
    (let [conn (new-conn-f request)
          listener (websocket/listener conn)]
      (websocket/start-send-pipeline conn)
      (websocket/connection-lifecycle conn request-ch)
      listener)))

(defn- websocket-creator 
  "Returns a new WebSocketCreator that uses create-websocket-f when a
  new websocket connection is accepted"
  [create-websocket-f]
  (reify WebSocketCreator
    (createWebSocket [this request response]
      (create-websocket-f this request response))))

(defn listen-for-requests
  "Starts a pipeilne that listens for requests on request-ch and uses
  handler-xf to handle the request. handler-xf should be a transducer
  that returns performs any required operations and then returns the
  request object, possible with :response-bytes if a response should
  be sent back to the other side of the connection"
  [request-ch handler-xf]
  ;; to-ch is a /dev/null
  (let [to-ch (async/chan 1 (keep (constantly nil)))]
    (async/pipeline-blocking 1
                             to-ch
                             (comp handler-xf
                                   (keep send-to-write-ch))
                             request-ch)
    to-ch))

(defn start
  "Starts up a Jetty Server that does nothing but handle websockets.
  Takes the following options:

  port: The port the server should bind to

  handler-xf: A transducer for handling requests. Input is a request
  map of :conn (connection map), :body-bytes ([bytes offest len])
  and :request-id (long). If a response should be sent back to the
  other side of the connection, the transducer should put request
  object onto result with :response-bytes ([bytes offset len]) assoc'd
  on.

  new-conn-f: a function that takes the original HTTP upgrading
  request and returns a connection map. Optional

  If server has already been started, immediately returns"
  [{:keys [port new-conn-f handler-xf server] :as websocket-server}]
  (if server
    websocket-server
    (let [server (Server.)
          connector (doto (ServerConnector. server)
                      (.setPort port))
          request-ch (async/chan 1024)
          new-conn-f (or new-conn-f default-conn-f)
          create-websocket-f (create-websocket request-ch new-conn-f handler-xf)
          creator (websocket-creator create-websocket-f)
          ws-handler (websocket-handler creator)
          request-listener (listen-for-requests request-ch handler-xf)]
      (.addConnector server connector)
      (.setHandler server ws-handler)
      (.start server)
      (assoc websocket-server
        :server server
        :request-ch request-ch
        :connector connector
        :request-listener request-listener))))

(defn stop
  "Blocks while Gracefully shutting down the server instance. First,
  the connector is closed to ensure no new connections are accepted.
  Then waits for all in flight requests to finish and finally closes
  the underlying jetty server. Returns immediately if server has
  already been stopped."
  [{:keys [^Server server
           ^ServerConnector connector
           request-listener
           request-ch] :as this}]
  (if server
    (do
      (.close connector)
      (async/close! request-ch)
      (<!! request-listener)
      (.stop server)
      (assoc this :server nil :connector nil))
    this))

(defn new-websocket-server
  "Creates a new websocket-server (but doesn't start it). config can
  include the following:

  port: The port the server should bind to. Required

  handler-xf: A transducer for handling requests. Input is a request
  map of :conn (connection map), :body-bytes ([bytes offest len])
  and :request-id (long). If a response should be sent back to the
  other side of the connection, the transducer should put request
  object onto result with :response-bytes ([bytes offset len]) assoc'd
  on. Required.

  new-conn-f: a function that takes the original HTTP upgrading
  request and returns a connection map. Defaults to default-conn-f

  If server has already been started, immediately returns"
  [config]
  {:pre [(number? (:port config))
         (:handler-xf config)
         (or (not (contains? config :new-conn-f))
             (fn? (:new-conn-f config)))]}
  config)
