(ns georgetown.dev.island-perf-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [georgetown.dev.island-perf :as perf])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn child! [settings]
  (let [{:keys [case-dir test-mode]} settings
        pid (.pid (ProcessHandle/current))]
    (when (= :pre-marker-stall test-mode) (Thread/sleep 60000))
    (spit (io/file case-dir "records.edn")
          (str (pr-str {:kind :identity :pid (if (= :wrong-pid test-mode) 0 pid)}) "\n"))
    (case test-mode
      :error (do (spit (io/file case-dir "records.edn")
                      (str (pr-str {:kind :phase :phase :warmup}) "\n") :append true)
                 (spit (io/file case-dir "report.edn") (pr-str {:status :error}))
                 (System/exit 2))
      :stall (Thread/sleep 60000)
      :resist (do (.addShutdownHook (Runtime/getRuntime)
                                    (Thread. ^Runnable #(Thread/sleep 60000)))
                  (Thread/sleep 60000))
      (spit (io/file case-dir "report.edn") (pr-str {:status :ok})))
    (System/exit 0)))

(defn command [settings]
  [(str (System/getProperty "java.home") "/bin/java")
   "-cp" (System/getProperty "java.class.path") "clojure.main" "-e"
   (str "(require 'georgetown.dev.island-perf-test)"
        "(georgetown.dev.island-perf-test/child! " (pr-str settings) ")")])

(defn root []
  (str (Files/createTempDirectory "island-perf-test-" (make-array FileAttribute 0))))

(defn options [dir]
  {:report-dir dir :population 2 :warmup 0 :measured 1
   :timeout-seconds 30 :termination-grace-ms 100 :force-reap-ms 2000
   :command-fn command})

(deftest options-test
  (is (= [50 500] (:populations (perf/parse-options []))))
  (is (= [7 8] (:populations (perf/parse-options ["--populations" "7,8"]))))
  (doseq [o [{:populations []} {:populations [0]} {:warmup -1}
             {:measured 0} {:timeout-seconds 0} {:timeout-seconds 1.5}]]
    (is (thrown? Exception (perf/validate-options o))))
  (is (thrown? Exception (perf/parse-options ["--test-mode" "stall"]))))

(deftest isolated-child-db-scenario
  (let [dir (root) sentinel (io/file dir "decoy-db")
        _ (spit sentinel "untouched")
        a (perf/run-case! (options dir)) b (perf/run-case! (options dir))]
    (is (= :ok (:status a) (:status b)))
    (is (:identity-verified? a))
    (is (not= (:pid a) (:pid b) (.pid (ProcessHandle/current))))
    (is (not= (:db-dir a) (:db-dir b)))
    (is (.isDirectory (io/file (:db-dir a))))
    (is (= "untouched" (slurp sentinel)))
    (is (.isFile (io/file (:case-dir a) "supervisor.edn")))
    (is (= :identity-error (:status (perf/run-case! (assoc (options dir) :test-mode :wrong-pid)))))))

(deftest partial-error-scenario
  (let [r (perf/run-case! (assoc (options (root)) :test-mode :error))]
    (is (= :error (:status r)))
    (is (= :warmup (get-in r [:last-record :phase])))
    (is (= 2 (:completed-records r)))
    (is (= 2 (:exit-code r)))
    (is (.isFile (io/file (:case-dir r) "stderr.log")))))

(deftest stalled-child-deadline-scenario
  (doseq [mode [:pre-marker-stall :stall :resist]]
    (let [r (perf/run-case! (assoc (options (root)) :test-mode mode :timeout-seconds 15))]
      (is (= :timeout (:status r)))
      (is (get-in r [:termination :reaped?]))
      (is (< (:elapsed-ms r) 20000))
      (is (not (.isPresent (ProcessHandle/of (:pid r)))))
      (when (= :resist mode)
        (is (= :forced (get-in r [:termination :outcome])))))))

(deftest comparison-preserves-errors
  (let [result (perf/run! (assoc (options (root)) :populations [2 3] :test-mode :error))]
    (is (= :error (:status result)))
    (is (= 2 (count (:cases result))))
    (is (every? #(.isFile (io/file (:case-dir %) "records.edn")) (:cases result)))))

(deftest shutdown-before-launch-scenario
  (let [launched? (atom false)
        r (perf/run-case!
           (assoc (options (root))
                  :shutdown-hook-fn (fn [^Thread hook] (.run hook))
                  :start-process-fn (fn [_]
                                      (reset! launched? true)
                                      (throw (ex-info "Unexpected worker launch" {})))))]
    (is (false? @launched?) "Shutdown prevents a later worker launch")
    (is (= :supervisor-error (:status r)))
    (is (nil? (:pid r)))
    (is (< (:elapsed-ms r) 5000))))

(deftest shutdown-during-registration-scenario
  (let [hook (atom nil)
        child (atom nil)
        shutdown-thread (atom nil)
        blocked? (atom false)]
    (try
      (let [r (perf/run-case!
               (assoc (options (root))
                      :test-mode :pre-marker-stall :timeout-seconds 3
                      :shutdown-hook-fn #(reset! hook %)
                      :start-process-fn
                      (fn [^ProcessBuilder builder]
                        (let [p (.start builder)
                              t (Thread. ^Runnable #(.run ^Thread @hook))
                              deadline (+ (System/nanoTime) 2000000000)]
                          (reset! child p)
                          (reset! shutdown-thread t)
                          (.start t)
                          ;; The process exists, but run-case! has not registered it.
                          ;; Wait for the hook to contend for the lifecycle lock.
                          (loop []
                            (cond
                              (= Thread$State/BLOCKED (.getState t))
                              (reset! blocked? true)
                              (or (not (.isAlive t)) (> (System/nanoTime) deadline)) nil
                              :else (do (Thread/yield) (recur))))
                          (is (.isAlive p) "Worker exists before registration")
                          p))))]
        (.join ^Thread @shutdown-thread 5000)
        (is @blocked? "Shutdown waits until start and registration are atomic")
        (is (not (.isAlive ^Thread @shutdown-thread)) "Shutdown cleanup is bounded")
        (is (not (.isAlive ^Process @child)) "Shutdown reaps the registered worker")
        (is (not (.isPresent (ProcessHandle/of (.pid ^Process @child)))))
        (is (get-in r [:termination :reaped?]))
        (is (< (:elapsed-ms r) 8000)))
      (finally
        (when-let [^Process p @child]
          (when (.isAlive p)
            (.destroyForcibly p)
            (.waitFor p 2 java.util.concurrent.TimeUnit/SECONDS)))
        (when-let [^Thread t @shutdown-thread]
          (.join t 5000))))))

(defn shutdown-parent! [dir]
  (perf/run-case! (assoc (options dir) :test-mode :resist :timeout-seconds 60))
  (System/exit 0))

(deftest parent-shutdown-scenario
  (let [dir (root)
        p (.start (ProcessBuilder.
                   ^java.util.List
                   [(str (System/getProperty "java.home") "/bin/java")
                    "-cp" (System/getProperty "java.class.path") "clojure.main" "-e"
                    (str "(require 'georgetown.dev.island-perf-test)"
                         "(georgetown.dev.island-perf-test/shutdown-parent! " (pr-str dir) ")")]))
        deadline (+ (System/nanoTime) 15000000000)
        identity (try
                   (loop []
                     (let [files (file-seq (io/file dir))
                           record (first (filter #(= "records.edn" (.getName %)) files))
                           data (when record
                                  (try (edn/read-string (slurp record)) (catch Exception _ nil)))]
                       (cond (:pid data) data
                             (> (System/nanoTime) deadline) nil
                             :else (do (Thread/sleep 50) (recur)))))
                   (finally (.destroy p)))]
    (try
      (is (:pid identity) "Owned child reached identity record")
      (is (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))
      (when (:pid identity)
        (is (not (.isPresent (ProcessHandle/of (:pid identity))))))
      (finally
        (when (.isAlive p) (.destroyForcibly p))
        (when-let [pid (:pid identity)]
          (when-let [handle (.orElse (ProcessHandle/of pid) nil)]
            (.destroyForcibly handle)))))))

(defn -main [& args]
  (let [result (if (= ["--deliberate-failure"] (vec args))
                 (binding [*report-counters* (ref *initial-report-counters*)]
                   (is false "Deliberate assertion failure verifies nonzero exit")
                   @*report-counters*)
                 (run-tests 'georgetown.dev.island-perf-test))]
    (shutdown-agents)
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
