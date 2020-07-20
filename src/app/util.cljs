
(ns app.util (:require [clojure.core.async :refer [go chan >! <!]]))

(defn all-once [f xs]
  (go
   (loop [acc [], tasks (doall (map f xs))]
     (if (empty? tasks) acc (recur (conj acc (<! (first tasks))) (rest tasks))))))

(defn pad-right [x n] (if (>= (count x) n) x (recur (str x " ") n)))
