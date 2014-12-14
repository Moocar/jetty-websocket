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

(defn create-websocket
  [handler-xf]
  (fn [this request response]
    (let [send-ch (async/chan 1)
          conn (websocket/make-connection-map send-ch)]
      (websocket/start-send-pipeline conn)
      (websocket/start-connection conn handler-xf))))

(defn- websocket-creator 
  "Creates a WebSocketCreator that when a websocket is opened, waits
  for a connection, and then passes all requests to `af`. af should be
  an async function of 2 arguments, the first a vector of [session
  bytes] and the second a channel to put to (ignored). Returns a
  WebSocketListener"
  [create-websocket-f]
  (reify WebSocketCreator
    (createWebSocket [this request response]
      (create-websocket-f this request response))))

(defn start
  [{:keys [port handler-xf server] :as this}]
  (if server
    this
    (let [server (Server.)
          connector (doto (ServerConnector. server)
                      (.setPort port))
          create-websocket-f (create-websocket handler-xf)
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
  [config handler-xf]
  (merge {:port (:port config)}
         {:handler-xf handler-xf}))
