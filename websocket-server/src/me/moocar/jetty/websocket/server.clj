(ns me.moocar.jetty.websocket.server
  (:require [clojure.core.async :as async :refer [go go-loop <! >!!]]
            [me.moocar.jetty.websocket :as websocket])
  (:import (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.websocket.api WebSocketListener WriteCallback)
           (org.eclipse.jetty.websocket.server WebSocketHandler)
           (org.eclipse.jetty.websocket.servlet WebSocketCreator)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket connections

(defn- websocket-handler 
  "WebSocketHandler that creates creator. Boilerplate"
  [creator]
  (proxy [WebSocketHandler] []
    (configure [factory]
      (.setCreator factory creator))))

(defn listen-for-connections
  "Starts listening on connection for connects, reads and writes. If
  called with arity-1, a default connection and listener are created,
  and the listener is returned"
  ([conn handler-xf]
     (let [listener (websocket/listener conn)]
       (start-connection conn listener handler-xf)))
  ([conn listener handler-xf]
     (let [to-ch (async/chan 1 (map (fn [x] (println "out:" x))))]
       (websocket/connection-lifecycle conn)
       (async/pipeline-blocking 1
                                to-ch
                                handler-xf
                                (:request-ch conn))
       listener)))

(defn default-conn-f
  [request]
  (println "in make default conn" (.getLocalAddress request))
  (let [send-ch (async/chan 1)]
    (websocket/make-connection-map send-ch)))

(defn create-websocket
  [new-conn-f handler-xf]
  (fn [this request response]
    (let [conn (new-conn-f request)]
      (websocket/start-send-pipeline conn)
      (listen-for-connections conn handler-xf))))

(defn- websocket-creator 
  "Creates a WebSocketCreator that when a websocket is opened, waits
  for a connection, and then passes all requests to `af`. af should be
  an async function of 2 arguments, the first a vector of [session
  bytes] and the second a channel to put to (ignored). Returns a
  WebSocketListener"
  [create-websocket-f]
  (reify WebSocketCreator
    (createWebSocket [this request response]
      (println "request" (.getLocalAddress request))
      (create-websocket-f this request response))))

(defn start
  [{:keys [port new-conn-f handler-xf server] :as this}]
  (if server
    this
    (let [server (Server.)
          connector (doto (ServerConnector. server)
                      (.setPort port))
          new-conn-f (or new-conn-f default-conn-f)
          create-websocket-f (create-websocket new-conn-f handler-xf)
          creator (websocket-creator create-websocket-f)
          ws-handler (websocket-handler creator)]
      (.addConnector server connector)
      (.setHandler server ws-handler)
      (.start server)
      (assoc this
        :server server))))

(defn stop
  [{:keys [server] :as this}]
  (if server
    (do 
      (.stop server)
      (assoc this :server nil))
    this))

(defn new-websocket-server
  [config]
  (merge {:port (:port config)}))
