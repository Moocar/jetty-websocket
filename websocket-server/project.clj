(defproject me.moocar.ftb500/websocket-server "0.1.0-SNAPSHOT"
  :description "A jetty websocket server using core.async"
  :url "https://github.com/Moocar/jetty-websockets"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha4"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [me.moocar/jetty-websocket "0.1.0-SNAPSHOT"]
                 [org.eclipse.jetty.websocket/websocket-server "9.3.0.M1"]]
  :profiles {:dev {:dependencies
                   [[me.moocar.jetty-websocket/websocket-client
                     "0.1.0-SNAPSHOT"]]}})
