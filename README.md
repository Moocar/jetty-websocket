# jetty-websocket

jetty-websocket is a clojure wrapper around [Jetty Websockets](http://www.eclipse.org/jetty/documentation/9.1.5.v20140505/jetty-websocket-server-api.html) that uses [clojure core.async](https://github.com/clojure/core.async) channels as the primary abstraction for both connections and requests.

It is **very experimental**

### The Good

- A request/response layer (RPC) built in
- request handlers are transducers
- gracefull shutdown (wait for all requests to finish processing)
- Sending requests is modelled purely on channels. Very open.

### The Bad

- No clojurescript. Currently this is a jetty wrapper only
- No text support. Binary only (currently)
- no ring support
- no multi-tenenacy. E.g both HTTP and websocket server (but this can be added manually)

### The Ugly

- Since RPC is built on top, the server and client are closely coupled. Unavoidable

## Why create another library?

I decided to go down this route because none of the other libraries
I've found have ticked all the boxes I need, plus I wanted to explore
the concept of a websocket server __as a transducer__.

This library is heavily influenced by [jetty9-websockets-async](https://github.com/ToBeReplaced/jetty9-websockets-async) especially by the concept of connection maps.

If you want a clojurescript/http-kit solution that just works and has some great features, check out [sente](https://github.com/ptaoussanis/sente).

## Usage

### Server

The main server concept is a handler-xf, a transducer responsible for handling requests from clients. Its input is the raw request. Should you want to return a response, you must return the same request map with `:response-bytes` on as well.

A request map has the following keys:

- `:conn` - The underlying connection map for this client. See below
- `:body-bytes` - A vector containing the request bytes payload. In the form `[bytes offset len]`
- `:request-id` - optional. Present if the requester is expecting a response

A connection map has the following important keys. Others such as `:read-ch` `:write-ch` etc are implementation details:

- `:send-ch` - A channel for sending requests to the other side of the connection. See client details below
- `:error-ch` - Websocket-level errors are put here

Here's an example of a handler transducer that echoes back any bytes it receives:

```clojure
(defn echo-handler []
  (map (fn [request]
         (assoc request :response-bytes (:body-bytes request)))))
```

If `:response-bytes` is present (`[bytes offset len]`), jetty-websocket will ensure that they are sent as a response back to the client.

### Requests

As a client, the core abstraction is the connection map's `:send-ch`. It takes requests and sends them to the other side of the connection. a request is a vector of two values, request-bytes and an optional response-ch. If the response-ch is included, the other side of the connection is notified that the requester is expecting a response.

### Full example

```clojure

;;; Create Server

(require '[me.moocar.jetty.websocket.server :as server])

(def server-config
  {:port 8080
   :handler-xf (echo-handler)})
(def server (server/start (server/new-websocket-server server-config)))


;;; Create client:

(require '[me.moocar.jetty.websocket.client :as client])

(def client-config
  {:port 8080
   :hostname "localhost"})
(def client (client/start (client/new-websocket-client client-config)))


;;; Send a request that doesn't expect a response

(require '[clojure.core.async :as async :refer [<!!]])
(def send-ch (:send-ch (:conn client)))

(def request-bytes (byte-array (map byte [1 2 3 4])))
(async/put! send-ch [request-bytes])

;;; Send a request expecting a response

(let [response-ch (async/chan 1)]
  (async/put! send-ch [request-bytes response-ch])
  (println "response" (<!! response-ch)))


;;; Shut it all down (will block until all requests have finished processing)

(def client (client/stop client))
(def server (server/stop server))
```

## Recommendations

### Component

I highly recommend wrappig the server/client with [component](https://github.com/stuartsierra/component)

### Channels and Bytes

jetty-websocket only supports binary messages, not text, and all APIs boild down to connections. I did this because it is the most open. For example, if you wanted to send and receive clojure data structures via transit, it's trivial to add readers/writers to the handler transducer and request channels.



