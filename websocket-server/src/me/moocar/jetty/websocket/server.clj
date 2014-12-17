(ns me.moocar.jetty.websocket.server
  (:require [clojure.core.async :as async :refer [go go-loop <! >!!]]
            [me.moocar.jetty.websocket :as websocket])
  (:import (java.nio ByteBuffer)
           (org.eclipse.jetty.server Server ServerConnector)
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

(defn response-buf
  "Sends a response for request-id on connection"
  [{:keys [response-bytes request-id] :as request}]
  (when response-bytes
    (let [[bytes offset len] response-bytes
          buf (.. (ByteBuffer/allocate (+ 1 8 len))
                  (put websocket/response-flag)
                  (putLong request-id)
                  (put bytes offset len)
                  (rewind))]
      buf)))

(defn send-to-write-ch
  [request]
  (let [{:keys [conn]} request
        {:keys [write-ch]} conn]
    (when-let [buf (response-buf request)]
      (async/put! write-ch buf))
    nil))

(defn default-conn-f
  [request]
  (println "in make default conn" (.getLocalAddress request))
  (let [send-ch (async/chan 1)]
    (websocket/make-connection-map send-ch)))

(defn create-websocket
  [request-ch new-conn-f handler-xf]
  (fn [this request response]
    (let [conn (new-conn-f request)
          listener (websocket/listener conn)]
      (websocket/start-send-pipeline conn)
      (websocket/connection-lifecycle conn request-ch)
      listener)))

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

(defn listen-for-requests
  [request-ch handler-xf]
  (let [to-ch (async/chan 1 (map (fn [_] (println "out"))))]
    (async/pipeline-blocking 1
                             to-ch
                             (comp handler-xf
                                   (keep send-to-write-ch))
                             request-ch)))

(defn start
  [{:keys [port new-conn-f handler-xf server] :as this}]
  (if server
    this
    (let [server (Server.)
          connector (doto (ServerConnector. server)
                      (.setPort port))
          request-ch (async/chan 1024)
          new-conn-f (or new-conn-f default-conn-f)
          create-websocket-f (create-websocket request-ch new-conn-f handler-xf)
          creator (websocket-creator create-websocket-f)
          ws-handler (websocket-handler creator)]
      (listen-for-requests request-ch handler-xf)
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
