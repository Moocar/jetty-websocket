(ns me.moocar.jetty-websocket.websocket-client
  (:require [clojure.core.async :as async :refer [go go-loop <! <!!]]
            [me.moocar.jetty.websocket :as websocket])
  (:import (java.net URI)
           (org.eclipse.jetty.websocket.client WebSocketClient)))

(defn- make-uri
  [{:keys [hostname port websockets] :as this}]
  (let [{:keys [scheme path]} websockets
        uri-string (format "%s://%s:%s%s"
                           (name scheme)
                           hostname
                           port
                           path)]
    (URI. uri-string)))

(defn start
  [{:keys [client handler-xf] :as this}]
  (if client
    client
    (let [client (WebSocketClient.)
          uri (make-uri this)
          conn (websocket/make-connection-map)
          listener (websocket/start-connection conn handler-xf)]
      (.start client)
      (if (deref (.connect client listener uri) 1000 nil)
        (assoc this
          :client client
          :conn conn)
        (throw (ex-info "Failed to connect"
                        this))))))

(defn stop
  [{:keys [client] :as this}]
  (if client
    (do
      (.stop client)
      (assoc this :client nil :conn nil))
    this))

(defn new-websocket-client
  [config handler-xf]
  (merge config
         {:handler-xf handler-xf}))
