
(ns app.main
  (:require ["axios" :as axios]
            ["fs" :as fs]
            [cljs.reader :refer [read-string]]
            [clojure.core.async :refer [go chan >! <!]]
            ["chalk" :as chalk]
            [clojure.string :as string]
            [cljs-node-io.fs :refer [areadFile]]
            [chan-utils.core :refer [chan-once all-once]])
  (:require-macros [clojure.core.strint :refer [<<]]))

(defn write [& xs] (.apply js/process.stdout.write js/process.stdout (clj->js xs)))

(defn chan-check-dep [[dep-name version]]
  (chan-once
   got
   (-> axios
       (.get (str "https://clojars.org/api/artifacts/" dep-name) (clj->js {}))
       (.then
        (fn [response]
          (let [latest-version (.-latest_version (.-data response))]
            (write (chalk/gray ">"))
            (got {:ok? true, :params [dep-name version], :data latest-version}))))
       (.catch
        (fn [error]
          (write (chalk/red ">"))
          (got {:ok? false, :params [dep-name version], :error error}))))))

(defn pad-right [x n] (if (>= (count x) n) x (recur (str x " ") n)))

(defn display-results! [results]
  (let [ok-checks (->> results
                       (filter (fn [check] (:ok? check)))
                       (map
                        (fn [check]
                          {:name (first (:params check)),
                           :current (last (:params check)),
                           :latest (:data check)})))
        failed-checks (->> results (filter (fn [check] (not (:ok? check)))) (map :params))
        old-packages (->> ok-checks
                          (filter (fn [info] (not= (:current info) (:latest info)))))
        latest-packages (->> ok-checks
                             (filter (fn [info] (= (:current info) (:latest info))))
                             (map :name)
                             (map str))]
    (when (not-empty latest-packages)
      (println)
      (println
       (.gray chalk "These packages are up to date:")
       (.gray chalk (->> latest-packages (string/join " ")))))
    (when (not-empty old-packages)
      (println)
      (println (.yellow chalk "Outdated packages:"))
      (let [max-name-length (->> old-packages (map :name) (map str) (map count) (apply max))]
        (doseq [info old-packages]
          (println
           (pad-right (str (:name info)) (+ 2 max-name-length))
           (:current info)
           (.gray chalk "->")
           (:latest info)))))
    (when (not-empty failed-checks)
      (println)
      (println "Not able to check:" (->> failed-checks (map first) (string/join " "))))))

(defn task! []
  (println (chalk/gray "Reading shadow-cljs.edn"))
  (when-not (fs/existsSync "shadow-cljs.edn")
    (println (chalk/red "Not found"))
    (.exit js/process 1))
  (go
   (let [start-time (.now js/Date)
         [err content] (<! (areadFile "shadow-cljs.edn" "utf8"))
         data (read-string content)
         deps (:dependencies data)]
     (write (chalk/gray (string/join "" (repeat (count deps) "."))))
     (write "\r")
     (let [<check-results (all-once chan-check-dep deps)
           results (<! <check-results)
           end-time (.now js/Date)
           cost (/ (- end-time start-time) 1000)]
       (println (chalk/gray (<< " cost ~{cost}s to check.")))
       (display-results! results)))))

(defn main! [] (task!))

(defn reload! [] (.clear js/console) (println "Reloaded.") (task!))
