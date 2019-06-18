
(ns app.main
  (:require ["axios" :as axios]
            ["fs" :as fs]
            ["path" :as path]
            [cljs.reader :refer [read-string]]
            [clojure.core.async :refer [go chan >! <!]]
            ["chalk" :as chalk]
            [clojure.string :as string]
            [cljs-node-io.fs :refer [areadFile awriteFile]]
            [chan-utils.core :refer [chan-once all-once]]
            ["latest-version" :as latest-version])
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

(defn check-version! []
  (let [pkg (.parse js/JSON (fs/readFileSync (path/join js/__dirname "../package.json")))
        version (.-version pkg)
        pkg-name (.-name pkg)]
    (-> (latest-version pkg-name)
        (.then
         (fn [npm-version]
           (if (= npm-version version)
             (comment println "Running latest version" version)
             (println
              (chalk/yellow
               (<<
                "New version ~{npm-version} available, current one is ~{version} . Please upgrade!\n\nyarn global add ~{pkg-name}\n")))))))))

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

(defn replace-numbers [content new-versions]
  (if (empty? new-versions)
    content
    (let [rule (first new-versions)
          new-content (let [pattern (re-pattern
                                     (str (:pkg rule) "\\s+" "\"" (:from rule) "\""))]
                        (string/replace
                         content
                         pattern
                         (fn [piece] (string/replace piece (:from rule) (:to rule)))))]
      (recur new-content (rest new-versions)))))

(defn replace-versions! [results]
  (let [new-versions (->> results
                          (filter
                           (fn [x]
                             (and (:ok? x)
                                  (not= (:data x) (last (:params x)))
                                  (re-matches #"\d+\.\d+\.\d+" (:data x)))))
                          (map
                           (fn [x]
                             {:pkg (first (:params x)),
                              :from (last (:params x)),
                              :to (:data x)})))]
    (if-not (empty? new-versions)
      (go
       (let [[err content] (<! (areadFile "shadow-cljs.edn" "utf8"))
             new-content (replace-numbers content new-versions)]
         (<! (awriteFile "shadow-cljs.edn" new-content nil))
         (println)
         (println (chalk/yellow "File is modified under replace mode!")))))))

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
       (display-results! results)
       (when (= "true" js/process.env.replace) (replace-versions! results))))))

(defn main! [] (task!) (check-version!))

(defn reload! [] (.clear js/console) (println "Reloaded.") (task!))
