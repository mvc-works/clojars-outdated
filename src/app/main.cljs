
(ns app.main
  (:require ["axios" :as axios]
            ["fs" :as fs]
            ["path" :as path]
            [cljs.reader :refer [read-string]]
            [clojure.core.async :refer [go chan >! <!]]
            ["chalk" :as chalk]
            [clojure.string :as string]
            [cljs-node-io.fs :refer [areadFile awriteFile]]
            ["latest-version" :as latest-version]
            [applied-science.js-interop :as j]
            [cljs.reader :refer [read-string]]
            [favored-edn.core :refer [write-edn]]
            [cljs.core.async.interop :refer [<p!]]
            [app.util :refer [pad-right all-once]])
  (:require-macros [clojure.core.strint :refer [<<]]))

(def envs
  {:replace? (= "true" js/process.env.replace),
   :china? (= "true" js/process.env.china),
   :npm-check? (= "true" js/process.env.npm),
   :wait? (= "true" js/process.env.wait)})

(defn write [& xs] (.apply js/process.stdout.write js/process.stdout (clj->js xs)))

(defn chan-check-dep [[dep-name version]]
  (go
   (try
    (let [response (<p!
                    (-> axios
                        (.get
                         (str "https://clojars.org/api/artifacts/" dep-name)
                         (j/obj :timeout (if (:wait? envs) 100000 12000)))))
          latest-version (j/get-in response ["data" "latest_version"])]
      (write (chalk/gray ">"))
      {:ok? true, :params [dep-name version], :data latest-version})
    (catch
     js/Error
     error
     (write (chalk/red ">"))
     {:ok? false, :params [dep-name version], :error error}))))

(defn check-version! []
  (let [pkg (.parse js/JSON (fs/readFileSync (path/join js/__dirname "../package.json")))
        version (.-version pkg)
        pkg-name (.-name pkg)]
    (go
     (let [npm-version (<p! (latest-version pkg-name))]
       (if (= npm-version version)
         (comment println "Running latest version" version)
         (println
          (chalk/yellow
           (<<
            "New version ~{npm-version} available, current one is ~{version} . Please upgrade!\n\nyarn global add ~{pkg-name}\n"))))))))

(defn display-results! [file-name results skipped-deps]
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
    (when (not-empty skipped-deps)
      (println)
      (println
       (chalk/gray "Skipped checking:" (->> skipped-deps (map first) (string/join " ")))))
    (when (not-empty latest-packages)
      (println)
      (println
       (.gray chalk "Up to date:")
       (.gray chalk (->> latest-packages (string/join " ")))))
    (when (not-empty old-packages)
      (println)
      (println (.yellow chalk "Outdated packages in" file-name ":"))
      (let [max-name-length (->> old-packages (map :name) (map str) (map count) (apply max))]
        (doseq [info old-packages]
          (println
           (pad-right (str (:name info)) (+ 2 max-name-length))
           (:current info)
           (.gray chalk "->")
           (:latest info)))))
    (when (not-empty failed-checks)
      (println)
      (println "Failed to check:" (->> failed-checks (map first) (string/join " "))))))

(defn replace-dep [dep new-versions]
  (if (empty? new-versions)
    dep
    (let [cursor (first new-versions)]
      (if (= (first dep) (:pkg cursor))
        [(:pkg cursor) (:to cursor)]
        (recur dep (rest new-versions))))))

(defn replace-deps-edn-dep [dep new-versions]
  (if (empty? new-versions)
    dep
    (let [cursor (first new-versions)]
      (if (= (first dep) (:pkg cursor))
        [(:pkg cursor) {:mvn/version (:to cursor)}]
        (recur dep (rest new-versions))))))

(defn replace-deps-numbers [content new-versions]
  (let [new-config (-> (read-string content)
                       (update
                        :deps
                        (fn [deps]
                          (->> deps
                               (map (fn [dep] (replace-deps-edn-dep dep new-versions)))
                               (into {})))))]
    (write-edn new-config {:indent 2})))

(defn replace-numbers [content new-versions]
  (let [new-config (-> (read-string content)
                       (update
                        :dependencies
                        (fn [deps]
                          (->> deps (map (fn [dep] (replace-dep dep new-versions))) (vec)))))]
    (write-edn new-config {:indent 2})))

(defn replace-versions! [filename results]
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
       (let [[err content] (<! (areadFile filename "utf8"))
             new-content (case filename
                           "shadow-cljs.edn" (replace-numbers content new-versions)
                           "deps.edn" (replace-deps-numbers content new-versions)
                           (do (println "Unknown file:" filename) content))]
         (<! (awriteFile filename new-content nil))
         (println)
         (println (chalk/yellow filename "is modified under replace mode!")))))))

(defn clojars-task! [filename]
  (println (chalk/gray "Reading" filename))
  (go
   (if-not (fs/existsSync filename)
     (println (chalk/red filename "not found"))
     (let [start-time (.now js/Date)
           [err content] (<! (areadFile filename "utf8"))
           data (case filename
                  "shadow-cljs.edn" (:dependencies (read-string content))
                  "deps.edn"
                    (->> (:deps (read-string content))
                         (map (fn [[k v]] [k (:mvn/version v)])))
                  (do (println "Unknown filename:" filename)))
           deps (->> data
                     (filter
                      (fn [pair] (not (string/includes? (str (first pair)) "org.clojure")))))
           skipped-deps (->> data
                             (filter
                              (fn [pair]
                                (string/includes? (str (first pair)) "org.clojure"))))]
       (write (chalk/gray (string/join "" (repeat (count deps) "."))))
       (write "\r")
       (let [<check-results (all-once chan-check-dep deps)
             results (<! <check-results)
             end-time (.now js/Date)
             cost (/ (- end-time start-time) 1000)]
         (println (chalk/gray (<< " cost ~{cost}s to check.")))
         (display-results! filename results skipped-deps)
         (when (:replace? envs) (replace-versions! filename results)))))))

(defn main! []
  (go (<! (clojars-task! "shadow-cljs.edn")) (<! (clojars-task! "deps.edn")))
  (when (:npm-check? envs) (check-version!)))

(defn reload! []
  (.clear js/console)
  (println "Reloaded.")
  (comment clojars-task! "shadow-cljs.edn"))
