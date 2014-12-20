(ns me.moocar.jetty.websocket
  (:require [clojure.core.async :as async :refer [go <!]])
  (:import (java.nio ByteBuffer)
           (org.eclipse.jetty.websocket.api WebSocketListener Session
                                            WriteCallback RemoteEndpoint)))

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

(def ^:const packet-type-bytes-length
  "Number of bytes taken up by the packet-type flag"
  1)

(def ^:const request-id-bytes-length
  "Number of bytes taken up by the request ID"
  8)

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

(defn- add-response-ch
  "Adds a response-ch for the request id. After 10 seconds, closes the
  ch and removes it from the set"
  [{:keys [response-chans-atom] :as conn}
   request-id
   response-ch]
  (swap! response-chans-atom assoc request-id response-ch)
  (async/take! (async/timeout 10000)
               (fn [_]
                 (async/close! response-ch)
                 (swap! response-chans-atom dissoc request-id))))

(defn- request-buf
  "Returns a function that takes a vector of bytes and response-ch,
  and returns a byte buffer that contains the packet-type, request-id
  and body bytes"
  [{:keys [request-id-seq-atom] :as conn}]
  (fn [[^bytes bytes response-ch]]
    (if response-ch
      (let [request-id (swap! request-id-seq-atom inc)
            body-size (alength bytes)
            buffer-size (+ packet-type-bytes-length
                           request-id-bytes-length
                           body-size)
            buf (.. (ByteBuffer/allocate buffer-size)
                    (put request-flag)
                    (putLong request-id)
                    (put bytes)
                    (rewind))]
        (add-response-ch conn request-id response-ch)
        buf)
      (let [body-size (alength bytes)
            buffer-size (+ packet-type-bytes-length body-size)
            buf (.. (ByteBuffer/allocate buffer-size)
                    (put no-request-flag)
                    (put bytes)
                    (rewind))]
        buf))))

(defn start-send-pipeline
  "Starts a pipeline that listens for new requests on a connection's
  send-ch, turns them into request bufs and outputs them to the
  connection's write-ch"
  [conn]
  (async/pipeline 1
                  (:write-ch conn)
                  (map (request-buf conn))
                  (:send-ch conn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket connections

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
  sent to the other side of the connection

  response-chans-atom - a map of request-id to response-ch. When a
  response comes back for a request, it will be put into this channel

  request-id-seq-atom - Every new request has an incremented sequence
  ID. This is the atomic store"
  [send-ch]
  {:connect-ch (async/chan 2)
   :send-ch send-ch
   :read-ch (async/chan 1024)
   :write-ch (async/chan 1024)
   :error-ch (async/chan 1024)
   :response-chans-atom (atom {})
   :request-id-seq-atom (atom 0)})

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
      (async/put! error-ch cause))
    (onWebSocketClose [this status-code reason]
      (async/put! connect-ch [status-code reason]))))

(defn- handle-read
  "Handles new bytes coming in off connection. An incoming packet can
  be one of 3 types (denoted by first byte):

  - request: [request-id(8 byte long) body-bytes(rest of bytes)]. A
  new request coming into this connection. Therefore the next 8 bytes
  are the request-id. New requests are put onto request-ch

  - response: [request-id(8 byte long) body-bytes(rest of bytes)]. A
  response to a previous request that was sent. The next 8 bytes are
  the request-id for the original outgoing request. Responses are put
  onto the response-ch for the request-id

  - no-response: [body-bytes(rest of bytes)]. A request coming into
  this connection that does NOT expect a response. Therefore there is
  no request-id, and the body takes up the rest of the bytes. New
  requests are put onto request-ch

  In all the above scenarios, the item put onto the channel is a map
  of :conn :body-bytes ([bytes offset len]) and a request-id for
  request or response packets"
  [request-ch
   {:keys [read-ch response-chans-atom] :as conn}
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
                        (assoc :request-id request-id))]
    (async/put! to-ch request)))

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

          read-ch
          ([v]
             (try
               (handle-read request-ch conn v)
               (catch Throwable t
                 (async/put! error-ch t)))
             (recur))

          write-ch
          ([buf]
             (send-bytes! (.getRemote ^Session session) buf)
             (recur))
          
          connect-ch
          ([[status-code reason]]
             (async/close! connect-ch)))))))



