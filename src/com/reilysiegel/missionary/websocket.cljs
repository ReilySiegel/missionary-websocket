(ns com.reilysiegel.missionary.websocket
  (:require [missionary.core :as m]))

(defn- remove-listeners [ws]
  (set! (.-onopen ws) nil)
  (set! (.-onclose ws) nil))

(defn- open-connection [url protocols]
  (fn [s f]
    (try
      (let [ws (new js/WebSocket url protocols)]
        (set! (.-onopen ws)
              (fn [_]
                (let [queue (m/mbx)
                      close (m/dfv)]
                  (remove-listeners ws)
                  (set! (.-onmessage ws) #(queue (.-data %)))
                  (set! (.-onclose ws) #(close %))
                  (s [ws queue close]))))
        (set! (.-onclose ws)
              (fn [e]
                (remove-listeners ws)
                (f e)))
        #(when (= (.-CONNECTING js/WebSocket) (.-readyState ws))
           (.close ws)))
      (catch :default e
        (f e) #()))))

(defn- handle-close [x]
  (if (instance? js/CloseEvent x)
    (throw x)
    x))

(defn connect
  "Returns task completing with a missionary-wrapped websocket.
  
  Websocket supports put on 1-arity returning a task, take on 2-arity (as task),
  and returns the raw websocket on 0-arity (e.g. for closing, or setting the
  binary type). If the websocket is closed, all operations will fail with the
  CloseEvent. All puts wait for the buffer to be flushed."
  ([url] (connect url #js []))
  ([url protocols]
   (m/sp
    (let [[ws queue close] (m/? (open-connection url protocols))]
      (fn
        ([] ws)
        ([data]
         (m/sp
          (handle-close
           (m/? (m/race close
                        (m/sp (while (< 4096 (.-bufferedAmount ws))
                                (m/? (m/sleep 50)))
                              (.send ws data)))))))
        ([s f] ((m/sp (handle-close (m/? (m/race queue close)))) s f)))))))

(defn drain
  "Returns a flow draining messages from a missionary websocket."
  [ws]
  (let [end (fn [])]
    (m/eduction
     (take-while (complement #{end}))
     (m/ap
      (try (m/? (m/?> (m/seed (repeat ws))))
           (catch js/CloseEvent _ end))))))

(defn seed
  "Seeds a missionary websocket with a flow. Returns a task."
  [ws flow]
  (m/reduce {} nil
            (m/ap (m/? (ws (m/?> flow))))))

(defn close
  "Returns a task closing the websocket."
  [ws]
  (m/sp
   (.close ^js (ws))))

