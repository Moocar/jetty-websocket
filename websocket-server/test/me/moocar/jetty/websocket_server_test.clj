(ns me.moocar.jetty/websocket-server-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [<!!]]
            [me.moocar.async :as moo-async]
            [me.moocar.transport :as transport]
            [me.moocar.jetty.websocket.server :as websocket-server]
            [me.moocar.jetty.websocket.client :as websocket-client]))

(deftest start-stop-test
  (let [config {:port 8084
                :hostname "localhost" 
                :websockets {:scheme :ws
                             :path "/ws"}}
        handler-xf (keep (fn [{:keys [response-cb body-bytes] :as request}]
                           (when response-cb
                             (response-cb body-bytes))
                           nil))
        server (websocket-server/start
                (websocket-server/new-websocket-server config handler-xf))]
    (try
      (let [client (websocket-client/start
                    (assoc (websocket-client/new-websocket-client config)
                      :send-ch (async/chan 1)))
            request (byte-array (map byte [1 2 3 4]))]
        (try
          (let [response (<!! (moo-async/request (:send-ch client) request))
                [bytes offset len] (:body-bytes response)]
            (is (= (seq request) (take len (drop offset (seq bytes))))))
          (async/put! (:send-ch client) [request])
          (finally
            (websocket-client/stop client))))
      (finally
        (websocket-server/stop server)))))
