(defproject eReceipts/services "0.1.0-SNAPSHOT"
  :plugins [[lein-sub "0.3.0"]]
  :sub ["transport"
        "jetty-websocket"
        "websocket-client"
        "websocket-server"])
