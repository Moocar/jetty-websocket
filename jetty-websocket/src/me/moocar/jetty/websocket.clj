(ns me.moocar.jetty.websocket
  (:require [clojure.core.async :as async :refer [go <!]]
            [me.moocar.comms-async :as comms])
  (:import (java.nio ByteBuffer)
           (org.eclipse.jetty.websocket.api WebSocketListener Session
                                            WriteCallback RemoteEndpoint)))

(defn make-connection-map
  "Returns a connection map that contains the following
  channels/atoms:

  send-ch - Any requests put onto send-ch will be sent to the other
  side of connection

  error-ch - Any websocket level errors are put here

  As well as the following, which are opaque to the library user

  connect-ch - Upon a new websocket connection, the session is put
  onto this channel. Then, when the websocket connection closes, the
  status-code and reason are put onto the channel.

  read-ch - Any incoming bytes on the connection are immediately put
  onto this channel.

  write-ch - Any ByteBuffers put onto this channel will immediately be
  sent to the other side of the connection"
  ([] (make-connection-map (map identity)))
  ([send-xf]
   (assoc (comms/basic-connection-map send-xf)
          :connect-ch (async/chan 2))))

(defn listener
  "Returns a websocket listener that does nothing but put connections,
  reads or errors into the respective channels"
  [{:keys [connect-ch read-ch error-ch] :as conn}]
  (reify WebSocketListener
    (onWebSocketConnect [this session]
      (async/put! connect-ch session))
    (onWebSocketText [this message]
      (throw (UnsupportedOperationException. "Text not supported")))
    (onWebSocketBinary [this bytes offset len]
      (async/put! read-ch (ByteBuffer/wrap bytes offset len)))
    (onWebSocketError [this cause]
      (when-not error-ch
        (println "not error ch!!!"))
      (when cause
        (async/put! error-ch cause)))
    (onWebSocketClose [this status-code reason]
      (async/put! connect-ch [status-code reason]))))

(defn- write-callback
  "Returns a WriteCallback that closes response-ch upon success, or
  puts cause if failed"
  [response-ch]
  (reify WriteCallback
    (writeSuccess [this]
      (async/close! response-ch))
    (writeFailed [this cause]
      (async/put! response-ch cause))))

(defn- send-bytes!
  "Sends bytes to remote-endpoint asynchronously and returns a channel
  that will close once successful or have an exception put onto it in
  the case of an error"
  [^RemoteEndpoint remote-endpoint byte-buffer]
  {:pre [remote-endpoint byte-buffer]}
  (let [response-ch (async/chan)]
    (try
      (.sendBytes remote-endpoint
                  byte-buffer
                  (write-callback response-ch))
      (catch Throwable t
        (async/put! response-ch t))
      (finally
        (async/close! response-ch)))
    response-ch))

(defn connection-lifecycle
  "Starts a go loop that first waits for a connection on connect-ch,
  then loops waiting for incoming binary packets, converting them into
  request objects of :conn, :body-bytes ([bytes offset len]) and
  optional :request-id. Or if the incoming bytes are for a response,
  sends to the appropriate response-ch. Also writes any buffers going
  into write-ch to a the connection. Returns a channel that will close
  once the underlying websocket connection closes"
  [{:keys [error-ch connect-ch read-ch write-ch] :as conn}
   request-ch]
  (go
    (when-let [session (<! connect-ch)]
      (loop []
        (async/alt!

          write-ch
          ([buf]
           (send-bytes! (.getRemote ^Session session) buf)
           (recur))

          connect-ch
          ([[status-code reason]]
           (async/close! connect-ch)))))))
