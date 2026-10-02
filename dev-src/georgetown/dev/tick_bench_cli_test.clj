(ns georgetown.dev.tick-bench-cli-test
  "Supplementary subprocess checks. Children do not load the application entry point."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperfiddle.rcf :as rcf])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(rcf/enable! false)

(def forbidden-namespaces
  '[georgetown.server.email georgetown.server.api
    georgetown.server.scheduler georgetown.server.omni-config
    georgetown.core georgetown.dev.core georgetown.dev.seed postal.core])

(defn- temp-directory []
  (.toFile (Files/createTempDirectory "tick-bench-cli-test-"
                                    (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- absolute-classpath []
  (str/join java.io.File/pathSeparator
            (map #(.getCanonicalPath (io/file %))
                 (str/split (System/getProperty "java.class.path")
                            (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))))

(defn- subprocess! [directory code]
  (let [output (io/file directory "child-output.txt")
        java (.getPath (io/file (System/getProperty "java.home") "bin" "java"))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [java "--add-opens=java.base/java.nio=ALL-UNNAMED"
                                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                                "-cp" (absolute-classpath) "clojure.main" "-e" code])
                         (.directory directory)
                         (.redirectErrorStream true)
                         (.redirectOutput output)))
        finished? (.waitFor process 120 TimeUnit/SECONDS)]
    (when-not finished?
      (.destroyForcibly process)
      (.waitFor process 10 TimeUnit/SECONDS))
    {:finished? finished?
     :exit (when finished? (.exitValue process))
     :output (slurp output)}))

(defn- benchmark-child-code [mode probe]
  ;; The load guard is installed BEFORE benchmark/application sources are required.
  ;; A shutdown hook survives the CLI's System/exit, including its catch path.
  (pr-str
   `(do
      (require '~'[hyperfiddle.rcf :as rcf])
      (hyperfiddle.rcf/enable! false)
      (require '~'georgetown.server.db '~'georgetown.server.config)
      (let [forbidden# '~forbidden-namespaces
            paths# (set (map #(-> (str %) (clojure.string/replace "." "/")
                                 (clojure.string/replace "-" "_")) forbidden#))
            attempted# (atom []) opened# (atom 0) closed# (atom 0)
            original-load# clojure.core/load]
        (.addShutdownHook
         (Runtime/getRuntime)
         (Thread.
          (fn []
            (spit ~probe
                  (pr-str {:attempted @attempted# :opened @opened# :closed @closed#
                           :loaded (vec (filter find-ns forbidden#))
                           :singleton-nil? (nil? @(deref (resolve '~'georgetown.server.db/db-atom)))
                           :config-unrealized? (not (realized? (deref (resolve '~'georgetown.server.config/config))))})))))
        (with-redefs-fn
          (into {(resolve '~'clojure.core/load)
                      (fn [& paths-to-load#]
                        (doseq [path# paths-to-load#]
                          (when (contains? paths# (clojure.string/replace path# #"^/" ""))
                            (swap! attempted# conj path#)
                            (throw (ex-info "Forbidden application namespace load" {}))))
                        (apply original-load# paths-to-load#))}
                (map (fn [sym#]
                       [(resolve sym#)
                        (fn [& _#]
                          (swap! attempted# conj sym#)
                          (throw (ex-info "Forbidden singleton/config access" {})))])
                     '~'[georgetown.server.db/db georgetown.server.db/connect!
                         georgetown.server.db/clear! georgetown.server.config/get]))
          (fn []
          (require '~'georgetown.dev.tick-bench)
          (let [open# (deref (resolve '~'georgetown.dev.tick-bench-fixture/open-db!))
                close# (deref (resolve '~'georgetown.dev.tick-bench-fixture/close!))
                restore# (deref (resolve '~'georgetown.dev.tick-bench-fixture/restore!))
                fail# (fn [] (throw (ex-info "injected-cli-failure" {})))
                replacements#
                {(resolve '~'georgetown.dev.tick-bench-fixture/open-db!)
                 (fn [path#] (let [db# (open# path#)] (swap! opened# inc) db#))
                 (resolve '~'georgetown.dev.tick-bench-fixture/close!)
                 (fn [db#] (close# db#) (swap! closed# inc))
                 (resolve '~'georgetown.dev.tick-bench-fixture/restore!)
                 (fn [db# fixture#] (if (= ~mode :restore) (fail#) (restore# db# fixture#)))
                 (resolve '~'georgetown.sim.tick/tick!)
                 (fn [& _#] (when (= ~mode :tick) (fail#)))
                 (resolve '~'georgetown.dev.tick-bench-fixture/epoch) (fn [& _#] 1)
                 (resolve '~'georgetown.dev.tick-bench/measure!)
                 (fn [_# operations#]
                   ((deref (resolve '~'georgetown.dev.tick-bench/timed-call!)) operations#)
                   {:warmup {:calls 4 :tick-ns 10000000000 :wall-ns 1}
                    :samples (vec (repeat 10 100))})
                 (resolve '~'georgetown.dev.tick-bench-report/statistics) (fn [& _#] {})
                 (resolve '~'georgetown.dev.tick-bench-report/save-result!) (fn [& _#])
                 (resolve '~'georgetown.dev.tick-bench-report/generate!)
                 (fn [& _#] (when (= ~mode :report) (fail#)))}]
            (with-redefs-fn replacements#
              (fn []
                (apply (deref (resolve '~'georgetown.dev.tick-bench/-main))
                       (if (= ~mode :report)
                         ["--population" "50" "--shift" "morning" "--samples" "10"]
                         ["--smoke" "--population" "50" "--shift" "morning"])))))))
        (shutdown-agents)))))

(defn- child-run! [mode]
  (let [directory (temp-directory)
        probe (io/file directory "lifecycle.edn")
        child (subprocess! directory (benchmark-child-code mode (.getPath probe)))
        status-files (filter #(= "status.edn" (.getName %)) (file-seq directory))]
    (assoc child :directory directory
           :probe (when (.exists probe) (edn/read-string (slurp probe)))
           :statuses (mapv #(edn/read-string (slurp %)) status-files))))

(deftest cli-isolation-test
  ;; A real unrelated Datalevin DB is closed before each subprocess and reopened
  ;; afterwards: compare logical data, not LMDB bytes or connection bookkeeping.
  (require 'datalevin.core)
  (let [get-conn (requiring-resolve 'datalevin.core/get-conn)
        transact! (requiring-resolve 'datalevin.core/transact!)
        q (requiring-resolve 'datalevin.core/q)
        db (requiring-resolve 'datalevin.core/db)
        close (requiring-resolve 'datalevin.core/close)
        path (.getPath (io/file (temp-directory) "unrelated-developer-db"))
        schema {:sentinel/id {:db/unique :db.unique/identity}}
        conn (get-conn path schema)
        snapshot (fn [conn] (q '[:find ?e ?a ?v :where [?e ?a ?v]] (db conn)))]
    (transact! conn [{:sentinel/id "do-not-touch" :sentinel/value "retained"}])
    (let [before (try (snapshot conn) (finally (close conn)))]
      (doseq [mode [:success :tick]]
        (let [child (child-run! mode)]
          (is (:finished? child) "child must finish within its process budget")
          (is (= (if (= mode :success) 0 1) (:exit child)) (:output child))
          (is (= [] (get-in child [:probe :attempted])) "forbidden namespace load or singleton/config access")
          (is (= [] (get-in child [:probe :loaded])) "startup/email/auth namespace present")
          (is (true? (get-in child [:probe :singleton-nil?])) "singleton stays unopened")
          (is (true? (get-in child [:probe :config-unrealized?])) "configuration delay stays unrealized")
          (is (= 1 (get-in child [:probe :opened])) "benchmark opened exactly one owned DB")
          (is (= 1 (get-in child [:probe :closed])) "owned DB closed before CLI exit")
          (let [conn (get-conn path schema)]
            (try (is (= before (snapshot conn)) "unrelated database remains intact")
                 (finally (close conn)))))))))

(deftest cli-failure-exit-test
  (doseq [mode [:restore :tick :report]]
    (testing (name mode)
      (let [child (child-run! mode)
            status (first (:statuses child))
            phase (if (= mode :report) :report :measurement)]
        (is (:finished? child))
        (is (= 1 (:exit child)) (:output child))
        (is (= 1 (count (:statuses child))) "one retained run status")
        (is (= :failed (:status status)))
        (is (= phase (get-in status [:failure :phase])))
        (is (= {:scenario :baseline :shift :morning :population 50}
               (get-in status [:failure :case])))
        (is (= 1 (get-in child [:probe :opened])))
        (is (= 1 (get-in child [:probe :closed])))
        (is (str/includes? (:output child) "tick-bench failed"))
        (is (str/includes? (:output child) (str ":phase " phase)))
        (is (not (str/includes? (:output child) "injected-cli-failure")))
        (if (= mode :report)
          (is (= [:complete] (mapv :status (:cases status)))
              "measurement can complete but failed report must leave run failed")
          (do (is (empty? (:cases status)))
              (is (empty? (filter #(= "raw.edn" (.getName %))
                                  (file-seq (:directory child))))
                  "failed tick/restore must not persist successful samples")))))))

(deftest test-runner-exit-status-test
  ;; Do not use --verify-failure-exit: that bypasses normal run-tests dispatch.
  ;; Keep the normal selected namespaces, but run only our injected assertion.
  (let [child
        (subprocess!
         (temp-directory)
         (pr-str
          '(do
             (require 'hyperfiddle.rcf)
             (hyperfiddle.rcf/enable! false)
             (require 'georgetown.dev.tick-bench-test-runner)
             (doseq [ns (all-ns) v (vals (ns-interns ns))]
               (when (:test (meta v)) (alter-meta! v dissoc :test)))
             (let [v (intern 'georgetown.dev.tick-bench-test 'intentional-cli-failure
                             (fn []))]
               (alter-meta! v assoc :test
                            (fn [] (clojure.test/is false "intentional-normal-runner-assertion")))
               (georgetown.dev.tick-bench-test-runner/-main)))))]
    (is (:finished? child))
    (is (str/includes? (:output child) "intentional-normal-runner-assertion")
        "an actual clojure.test assertion must execute")
    (is (str/includes? (:output child) "1 failures, 0 errors")
        "normal runner must aggregate the injected failure")
    (is (= 1 (:exit child)) (:output child))))
