(defproject me.moocar.jetty-websocket/websocket-client "0.1.0-SNAPSHOT"
  :description "A jetty websocket client using core.async"
  :url "https://github.com/Moocar/jetty-websockets"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha4"]
                 [me.moocar/jetty-websocket "0.1.0-SNAPSHOT"]
                 [me.moocar/comms-async "0.1.0-SNAPSHOT"]
                 [org.eclipse.jetty.websocket/websocket-client "9.3.0.M1"]])
