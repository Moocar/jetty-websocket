(ns me.moocar.jetty.websocket
  (:require [clojure.core.async :as async :refer [go go-loop <! >!!]]
            [me.moocar.transport :as transport])
  (:import (java.nio ByteBuffer)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (org.eclipse.jetty.websocket.api WebSocketListener Session WriteCallback)))

(def ^:const request-flag
  "Byte flag placed at the beginning of a packet to indicate the next
  8 bytes are the request-id and that the sender of the packet expects
  to receive a response (with the response flag)"
  (byte 1))

(def ^:const response-flag
  "Byte flag placed at the begninning of a packet to indicate that
  this is a response packet for the request-id in the next 8 bytes"
  (byte 0))

(def ^:const no-request-flag
  "Byte flag placed at the beginning of a packet to indicate that this
  is a request that does not expect a response and therefore the
  request-id is not present (data begins at position 1)"
  (byte -1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Sending

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
  [remote-endpoint byte-buffer]
  {:pre [remote-endpoint byte-buffer]}
  (let [response-ch (async/chan)]
    (try
      (.sendBytes remote-endpoint 
                  byte-buffer
                  (write-callback response-ch))
      (catch Throwable t
        (println "caught an exc" t)
        (async/put! response-ch t))
      (finally
        (async/close! response-ch)))
    response-ch))

(defn- add-response-ch
  "Adds a response-ch for the request id. After 30 seconds, closes the
  ch and removes it from the set"
  [{:keys [response-chans-atom] :as conn}
   request-id
   response-ch]
  (swap! response-chans-atom assoc request-id response-ch)
  (async/take! (async/timeout 30000)
               (fn [_]
                 (async/close! response-ch)
                 (swap! response-chans-atom dissoc request-id))))

(defn send-response
  "Sends a response for request-id on connection"
  [{:keys [write-ch] :as conn}
   request-id
   [bytes offset len]]
  (let [buf (.. (ByteBuffer/allocate (+ 1 8 len))
                (put response-flag)
                (putLong request-id)
                (put bytes offset len)
                (rewind))]
    (async/put! write-ch buf)))

(defn- make-response-cb
  "Returns a function that takes bytes and sends them as a response
  for request-id on connection"
  [conn request-id]
  (fn [body-bytes]
    (send-response conn request-id body-bytes)))

(defn response-cb
  [{:keys [response-bytes response-cb] :as request}]
  (response-cb response-bytes)
  nil)

(defrecord WebSocketConnection [write-ch request-id-seq-atom]
  transport/Transport
  (-send! [conn bytes response-ch]
    (let [request-id (swap! request-id-seq-atom inc)
          body-size (alength bytes)
          request-id-size (/ (Long/SIZE) 8)
          buffer-size (+ 1 request-id-size body-size)
          buf (.. (ByteBuffer/allocate buffer-size)
                  (put request-flag)
                  (putLong request-id)
                  (put bytes)
                  (rewind))]
      (add-response-ch conn request-id response-ch)
      (async/put! write-ch buf)
      response-ch))
  (-send-off! [this bytes]
    (let [buf (.. (ByteBuffer/allocate (inc (alength bytes)))
                  (put no-request-flag)
                  (put bytes)
                  (rewind))]
      (async/put! write-ch buf))))

(defn send-off!
  "Sends a message on connection and doesn't wait for a response"
  [conn bytes]
  (transport/-send-off! conn bytes))

(defn send!
  "Sends a message on connection. The response will be put onto
  response-ch"
  [conn bytes response-ch]
  (transport/-send! conn bytes response-ch))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket connections

(defn make-connection-map
  []
  (map->WebSocketConnection
   {:connect-ch (async/chan 2)
    :request-ch (async/chan 1024)
    :read-ch (async/chan 1024)
    :write-ch (async/chan 1024)
    :error-ch (async/chan 1024)
    :response-chans-atom (atom {})
    :request-id-seq-atom (atom 0)}))

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
      (async/put! read-ch [bytes offset len]))
    (onWebSocketError [this cause]
      (println "on server error" cause)
      (async/put! error-ch cause))
    (onWebSocketClose [this status-code reason]
      (async/put! connect-ch [status-code reason]))))

(defn handle-read
  "Handles new bytes coming in off connection and puts them onto
  request-ch as a map of [:conn :body-bytes]. If the packet is a
  request, a callback function that accepts bytes is assoc'd on
  as :response-cb. If packet is a response to a request, the request
  map is instead put onto the response-ch that was setup in `send!`"
  [{:keys [read-ch request-ch response-chans-atom] :as conn}
   [bytes offset len]]
  (let [buf (ByteBuffer/wrap bytes offset len)
        packet-type (.get buf)
        request-id (when-not (= no-request-flag packet-type)
                     (.getLong buf))
        body-bytes [bytes (+ offset (.position buf)) (- len (.position buf))]
        to-ch (if (= response-flag packet-type)
                (get @response-chans-atom request-id)
                request-ch)
        request (cond-> {:conn conn
                         :body-bytes body-bytes}
                        (= packet-type request-flag)
                        (assoc :response-cb (make-response-cb conn request-id)))]
    (async/put! to-ch request)))

(defn connection-lifecycle
  "Starts a go loop that first waits for a connection on connect-ch,
  then loops waiting for incoming binary packets. When a packet is
  received, puts [session bytes] onto read-ch. Sends any byte-buffers
  in write-ch to the client. A second message put onto connect-ch is
  assumed to be a close signal, which ends the loop"
  [{:keys [connect-ch read-ch write-ch] :as conn}]
  (go
    (when-let [session (<! connect-ch)]
      (go-loop []
        (async/alt!

          read-ch
          ([v]
             (try
               (handle-read conn v)
               (catch Throwable t
                 (println "error handling read" v)
                 (.printStackTrace t)))
             (recur))

          write-ch
          ([buf]
             (send-bytes! (.getRemote session) buf)
             (recur))
          
          connect-ch
          ([[status-code reason]]
             (println "closing because" status-code reason)
             (async/close! connect-ch)))))))

(defn start-connection
  "Starts listening on connection for connects, reads and writes. If
  called with arity-1, a default connection and listener are created,
  and the listener is returned"
  ([conn handler-xf]
     (let [listener (listener conn)]
       (start-connection conn listener handler-xf)))
  ([conn listener handler-xf]
     (let [to-ch (async/chan 1 (map (fn [x] (println "out:" x))))]
       (connection-lifecycle conn)
       (async/pipeline-blocking 1
                                to-ch
                                handler-xf
                                (:request-ch conn))
       listener)))

