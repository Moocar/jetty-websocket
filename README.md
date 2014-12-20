# Clojure Jetty Websockets

## Example

Create a handler transducer. Here's an echo example

```clojure
(defn echo-handler []
  (map (fn [request]
         (assoc request :response-bytes (:body-bytes request)))))
```

Now create a server and start it

```clojure
(require '[me.moocar.jetty.websocket.server :as server])
(def config
  {:port 8080
   :handler-xf (echo-handler)})
(def server (server/new-websocket-server config))
(def server (server/start server))

;; When ready, to close:
(def server (server/stop server))
```

And the jetty client:

```clojure
(require '[me.moocar.jetty.websocket.client :as client])
(def config
  {:port 8080
   :hostname "localhost"})
(def client (client/new-websocket-client config))
(def client (client/start client))

;; When ready to stop
(def client (client/stop client))
```

And to send requests:

```clojure
(require '[clojure.core.async :as async :refer [<!!]])
(def request-bytes (byte-array (map byte [1 2 3 4])))
(let [response-ch (async/chan 1)]
  (async/put! (:send-ch (:conn client)) [request-bytes response-ch])
  (println "response" (<!! response-ch)))
```
