(ns me.moocar.jetty.websocket.client
  (:require [me.moocar.jetty.websocket :as websocket])
  (:import (java.net URI)
           (org.eclipse.jetty.websocket.client WebSocketClient)))

(defn- make-uri
  "Creates the full uri using hostname, port and websocket scheme and
  path"
  [{:keys [hostname port websockets] :as this}]
  (let [{:keys [scheme path]} websockets
        uri-string (format "%s://%s:%s%s"
                           (name scheme)
                           hostname
                           port
                           path)]
    (URI. uri-string)))

(defn start
  "Starts a websocket-client. The client will attempt to connect to
  the remote server, and if successul, will return the
  websocket-client with a :conn (websocket connection-map). Blocks
  until connection has been established. Returns immediately if this
  client has already been started"
  [{:keys [client request-ch send-ch] :as this}]
  (if client
    client
    (let [client (WebSocketClient.)
          uri (make-uri this)
          conn (assoc (websocket/make-connection-map send-ch)
                 :request-ch request-ch)
          listener (websocket/listener conn)]
      (websocket/start-send-pipeline conn)
      (websocket/connection-lifecycle conn request-ch)
      (.start client)
      (if (deref (.connect client listener uri) 1000 nil)
        (assoc this
          :client client
          :conn conn)
        (throw (ex-info "Failed to connect"
                        this))))))

(defn stop
  "Immediately stops the client and closes the underlying connection."
  [{:keys [client] :as this}]
  (if client
    (do
      (.stop client)
      (assoc this :client nil :conn nil))
    this))

(defn new-websocket-client
  [config]
  (merge config))
