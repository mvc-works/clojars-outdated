
(ns app.main
  (:require ["axios" :as axios]
            ["fs" :as fs]
            [cljs.reader :refer [read-string]]
            [clojure.core.async :refer [go chan >! <!]]
            ["chalk" :as chalk]
            [clojure.string :as string])
  (:require-macros [clojure.core.strint :refer [<<]]))

(defn chan-check-dep [[dep-name version]]
  (let [<result (chan)]
    (-> axios
        (.get (str "https://clojars.org/api/artifacts/" dep-name) (clj->js {}))
        (.then
         (fn [response]
           (let [latest-version (.-latest_version (.-data response))]
             (go (>! <result {:ok? true, :params [dep-name version], :data latest-version})))))
        (.catch
         (fn [error]
           (go (>! <result {:ok? false, :params [dep-name version], :error error})))))
    <result))

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

(defn write [& xs] (.apply js/process.stdout.write js/process.stdout (clj->js xs)))

(defn map-chans [chan-f xs]
  (let [all-tasks (doall (map chan-f xs)), <result (chan), *counter (atom 0)]
    (go
     (write (.gray chalk (<< "Processing ~(count all-tasks) tasks: ")))
     (loop [acc [], tasks all-tasks]
       (if (empty? tasks)
         (do (println) (>! <result acc))
         (do
          (swap! *counter inc)
          (write (.gray chalk (<< "~{@*counter} ")))
          (recur (conj acc (<! (first tasks))) (rest tasks))))))
    <result))

(defn task! []
  (println (.gray chalk "Reading shadow-cljs.edn"))
  (when-not (fs/existsSync "shadow-cljs.edn")
    (println (.red chalk "Not found"))
    (.exit js/process 1))
  (go
   (let [content (fs/readFileSync "shadow-cljs.edn" "utf8")
         data (read-string content)
         <check-results (map-chans chan-check-dep (:dependencies data))
         results (<! <check-results)]
     (display-results! results))))

(defn main! [] (task!))

(defn reload! [] (.clear js/console) (println "Reloaded.") (task!))
