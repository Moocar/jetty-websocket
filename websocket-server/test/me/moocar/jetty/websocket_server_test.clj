(ns me.moocar.jetty/websocket-server-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [<!!]]
            [me.moocar.transport :as transport]
            [me.moocar.jetty.websocket-server :as websocket-server]
            [me.moocar.jetty-websocket.websocket-client :as websocket-client]))

(defn echo-handler
  []
  (keep (fn [{:keys [response-cb body]} request]
          (response-cb body)
          nil)))

(deftest start-stop-test
  (let [config {:port 8083
                :hostname "localhost" 
                :websockets {:scheme :ws
                             :path "/ws"}}
        handler-xf (keep (fn [{:keys [response-cb body-bytes] :as request}]
                           (response-cb body-bytes)
                           nil))
        server (websocket-server/start
                (websocket-server/new-websocket-server config handler-xf))]
    (try
      (let [client (websocket-client/start
                    (websocket-client/new-websocket-client config handler-xf))
            request (byte-array (map byte [1 2 3 4]))
            response-ch (async/chan)]
        (try
          (transport/-send! (:conn client) request response-ch)
          (let [{response-bytes :body-bytes} (<!! response-ch)
                [bytes offset len] response-bytes]
            (is (= (seq request) (take len (drop offset (seq bytes))))))
          (finally
            (websocket-client/stop client))))
      (finally
        (websocket-server/stop server)))))
