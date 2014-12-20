(ns me.moocar.jetty.websocket-server-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [<!!]]
            [cognitect.transit :as transit]
            [me.moocar.jetty.websocket :as websocket]
            [me.moocar.jetty.websocket.client :as websocket-client]
            [me.moocar.jetty.websocket.server :as websocket-server])
  (:import (java.util Arrays)))

(defn send-request
  [send-ch request-bytes]
  (let [response-ch (async/chan 1)]
    (async/put! send-ch [request-bytes response-ch])
    response-ch))

(defn local-config []
  {:port 8084
   :hostname "localhost"})

(defn echo-handler []
  (keep (fn [{:keys [request-id body-bytes] :as request}]
          (when request-id
            (assoc request :response-bytes body-bytes)))))

(defn start-server [config handler-xf]
  (websocket-server/start
   (websocket-server/new-websocket-server (assoc config
                                                 :handler-xf handler-xf))))

(defn start-client [config]
  (websocket-client/start
   (websocket-client/new-websocket-client config)))

(defn to-bytes [[bytes offset len]]
  (Arrays/copyOfRange bytes offset (+ offset len)))

(deftest start-stop-test
  (let [config (local-config)
        handler-xf (echo-handler)
        server (start-server config handler-xf)]
    (try
      (let [client (start-client config)
            send-ch (:send-ch (:conn client))
            request (byte-array (map byte [1 2 3 4]))]
        (try
          (let [response (<!! (send-request send-ch request))]
            (is (= (seq request) (seq (to-bytes (:body-bytes response))))))
          (async/put! send-ch [request])
          (finally
            (websocket-client/stop client))))
      (finally
        (websocket-server/stop server)))))

(defn waiting-handler
  [wait-ch]
  (keep (fn [request]
          (<!! wait-ch)
          (assoc request :response-bytes [(byte-array [(byte 1)]) 0 1]))))

(deftest t-shutdown-before-finished
  (let [config (local-config)
        wait-ch (async/chan 1)
        handler-xf (waiting-handler wait-ch)
        server (start-server config handler-xf)]
    (try
      (let [client (start-client config)
            request (byte-array (map byte [1 2 3 4]))]
        (try
          (let [response-ch (send-request (:send-ch (:conn client)) request)
                _ (Thread/sleep 10)
                server-stopped-ch (async/thread (websocket-server/stop server))
                throw-ch (async/chan 1)]
            (try (start-client config)
                 (catch Throwable t
                   (async/put! throw-ch t)))
            (is (instance? Throwable (<!! throw-ch)))
            (Thread/sleep 10)
            (async/close! wait-ch)
            (is (not (.isStopped (:server server))))
            (let [response (<!! response-ch)]
              (is (= [(byte 1)] (seq (to-bytes (:body-bytes response)))))
              (async/thread (websocket-server/stop server))))
          (finally
            (websocket-client/stop client))))
      (finally
        (websocket-server/stop server)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Transit

(defn clj->bytes [clj]
  (let [out (java.io.ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer clj)
    (.toByteArray out)))

(defn bytes->clj [bytes offset len]
  (let [reader (transit/reader (java.io.ByteArrayInputStream. bytes offset len) :json)]
    (transit/read reader)))

(defn new-transit-conn []
  (websocket/make-connection-map (map (websocket/custom-send bytes->clj clj->bytes))))

(defn transit-echo-handler []
  (keep (fn [{:keys [request-id body] :as request}]
          (when request-id
            (assoc request :response body)))))

(deftest t-transit
  (let [config (local-config)
        handler-xf (transit-echo-handler)
        server (start-server config (comp (map (websocket/custom-request bytes->clj))
                                          handler-xf
                                          (keep (websocket/custom-response clj->bytes))))]
    (try
      (let [client (start-client (assoc config
                                        :new-conn-f new-transit-conn))
            send-ch (:send-ch (:conn client))
            request {:this-is-my [:request]}]
        (try
          (let [response (<!! (send-request send-ch request))]
            (is (= request (:body response))))
          (async/put! send-ch [request])
          (finally
            (websocket-client/stop client))))
      (finally
        (websocket-server/stop server)))))
