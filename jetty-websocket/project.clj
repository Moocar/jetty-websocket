(defproject me.moocar/jetty-websocket "0.1.0-SNAPSHOT"
  :description "A library for working with jetty websockets using
  core.async"
  :url "https://github.com/Moocar/jetty-websockets"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.eclipse.jetty.websocket/websocket-api "9.3.0.M1"]
                 [me.moocar/comms-async "0.1.0-SNAPSHOT"]])
