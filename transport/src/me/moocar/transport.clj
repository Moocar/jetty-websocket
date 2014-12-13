(ns me.moocar.transport)

(defprotocol Transport
  (-send! [this bytes]
          [this bytes response-ch])
  (-send-off! [this bytes]))

