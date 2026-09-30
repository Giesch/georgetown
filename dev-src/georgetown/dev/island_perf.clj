(ns georgetown.dev.island-perf
  "Developer-only supervisor. Never loads application configuration or a database."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def defaults {:populations [50 500] :warmup 20 :measured 200
               :timeout-seconds 7200 :sample-ms 100 :termination-grace-ms 2000
               :force-reap-ms 2000 :report-dir "target/island-perf"})

(defn validate-options [options]
  (let [o (merge defaults options)
        output (.toPath (.getCanonicalFile (io/file (:report-dir o))))
        forbidden (.toPath (.getCanonicalFile (io/file "checkouts")))]
    (when (.startsWith output forbidden)
      (throw (ex-info "Report output cannot be inside checkouts" {})))
    (when-let [path (:fixture o)]
      (let [f (edn/read-string (slurp path))]
        (when-not (and (:island-id f) (vector? (:txs f))
                       (= [(:population (:manifest f))] (vec (:populations o))))
          (throw (ex-info "Restored fixture requires exactly its manifest population" {})))))
    (doseq [k [:warmup :measured :termination-grace-ms :force-reap-ms]]
      (when-not (and (integer? (get o k)) (<= 0 (get o k) Integer/MAX_VALUE))
        (throw (ex-info "Expected a bounded nonnegative integer" {:option k}))))
    (when-not (and (integer? (:timeout-seconds o))
                   (<= 1 (:timeout-seconds o) 86400))
      (throw (ex-info "Whole-case timeout must be an integer from 1 to 86400 seconds" {})))
    (when-not (and (sequential? (:populations o)) (seq (:populations o))
                   (every? #(and (integer? %) (<= 1 % 100000)) (:populations o)))
      (throw (ex-info "Populations must be a nonempty sequence of positive integers" {})))
    (when-not (and (integer? (:sample-ms o)) (<= 1 (:sample-ms o) 60000))
      (throw (ex-info "Sample cadence must be an integer from 1 to 60000 milliseconds" {})))
    (when-not (pos? (:measured o))
      (throw (ex-info "Measured tick count must be positive" {})))
    o))

(defn parse-options [args]
  (loop [args args o {}]
    (if (empty? args)
      (validate-options o)
      (let [[flag value & more] args
            k ({"--populations" :populations "--population" :populations
                "--warmup" :warmup "--measured" :measured
                "--timeout-seconds" :timeout-seconds "--report-dir" :report-dir
                "--fixture" :fixture} flag)]
        (when-not (and k value)
          (throw (ex-info "Unknown option or missing value" {:option flag})))
        (recur more (assoc o k
                          (case k
                            :populations (mapv #(Long/parseLong %) (str/split value #","))
                            (:warmup :measured :timeout-seconds) (Long/parseLong value)
                            value)))))))

(defn- owned-directory! [parent prefix]
  (let [p (.toPath (io/file parent))]
    (Files/createDirectories p (make-array FileAttribute 0))
    (str (.toAbsolutePath (Files/createTempDirectory p prefix (make-array FileAttribute 0))))))

(defn- git-value [& args]
  (try
    (let [p (.start (ProcessBuilder. ^java.util.List (vec (cons "git" args))))]
      (if (.waitFor p 2 TimeUnit/SECONDS)
        (when (zero? (.exitValue p)) (str/trim (slurp (.getInputStream p))))
        (do (.destroyForcibly p) nil)))
    (catch Exception _ nil)))

(defn environment-metadata []
  ;; Only selected system properties and git identities are recorded. No config,
  ;; environment variables, arbitrary JVM properties or classpath contents.
  {:revision (git-value "rev-parse" "HEAD")
   :worktree-status (git-value "status" "--porcelain" "--untracked-files=no")
   :os (System/getProperty "os.name") :architecture (System/getProperty "os.arch")
   :java-version (System/getProperty "java.version")
   :logical-cores (.availableProcessors (Runtime/getRuntime))
   :classpath-provenance
   (mapv (fn [entry] {:name (.getName (io/file entry))
                     :kind (if (str/ends-with? entry ".jar") :jar :directory)})
         (str/split (System/getProperty "java.class.path")
                    (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))})

(defn worker-command [settings]
  (let [flags (filter #(or (str/starts-with? % "--add-opens")
                          (re-matches #"-Xm[sx].+" %))
                      (.getInputArguments (ManagementFactory/getRuntimeMXBean)))]
    (vec (concat [(str (System/getProperty "java.home") "/bin/java")]
                 flags ["-cp" (System/getProperty "java.class.path")
                        "clojure.main" "-m" "georgetown.dev.island-perf-worker"
                        (pr-str settings)]))))

(defn terminate! [^Process process grace-ms force-ms]
  (if-not (.isAlive process)
    {:outcome :already-exited :reaped? true :exit-code (.exitValue process)}
    (do
      (.destroy process)
      (if (.waitFor process (long grace-ms) TimeUnit/MILLISECONDS)
        {:outcome :graceful :reaped? true :exit-code (.exitValue process)}
        (do
          (.destroyForcibly process)
          (if (.waitFor process (long force-ms) TimeUnit/MILLISECONDS)
            {:outcome :forced :reaped? true :exit-code (.exitValue process)}
            {:outcome :reap-failed :reaped? false}))))))

(defn- evidence [case-dir]
  (let [records-file (io/file case-dir "records.edn")
        report-file (io/file case-dir "report.edn")]
    (try
      (let [parsed (if (.isFile records-file)
                     (with-open [reader (io/reader records-file)]
                       (mapv (fn [line]
                               (try {:record (edn/read-string line)}
                                    (catch Exception _ {:malformed? true})))
                             (remove str/blank? (line-seq reader)))) [])
            records (mapv :record (remove :malformed? parsed))
            report (when (.isFile report-file)
                     (try (edn/read-string (slurp report-file))
                          (catch Exception _ ::malformed)))]
        (cond-> {:identity (:record (first parsed))
                 :last-record (last records)
                 :completed-records (count records)
                 :completed-ticks (frequencies (map :phase (filter #(= :tick (:kind %)) records)))
                 :failure-phase (:phase (last (filter #(= :phase (:kind %)) records)))
                 :report (when-not (= ::malformed report) report)}
          (or (some :malformed? parsed) (= ::malformed report))
          (assoc :evidence-error :malformed-or-incomplete-edn)))
      (catch Exception _ {:evidence-error :malformed-or-incomplete-edn}))))

(defn run-case!
  "Run one case in an owned JVM and directory. Internal :command-fn,
  :shutdown-hook-fn and :start-process-fn enable isolated lifecycle tests;
  they are never accepted by the command line."
  [options]
  (let [o (validate-options (if (:population options)
                              (assoc options :populations [(:population options)]) options))
        population (or (:population o) (first (:populations o)))
        case-dir (owned-directory! (or (:run-dir o) (:report-dir o)) (str "case-" population "-"))
        db-dir (str (.toAbsolutePath (Files/createDirectory
                                     (.toPath (io/file case-dir "db"))
                                     (make-array FileAttribute 0))))
        settings (merge (select-keys o [:warmup :measured :sample-ms :fixture :test-mode])
                        {:population population :case-dir case-dir :db-dir db-dir})
        lifecycle-lock (Object.)
        shutdown-started? (atom false)
        child (atom nil)
        termination (atom nil)
        hook (Thread. ^Runnable
                      (fn []
                        (when-let [p (locking lifecycle-lock
                                       (reset! shutdown-started? true)
                                       @child)]
                          ;; Reaping must not hold the launch/registration lock.
                          (reset! termination
                                  (terminate! p (:termination-grace-ms o) (:force-reap-ms o))))))
        started (System/nanoTime)
        deadline (+ started (* 1000000000 (long (:timeout-seconds o))))
        result
        (do
          (.addShutdownHook (Runtime/getRuntime) hook)
          (try
            (when-let [f (:shutdown-hook-fn o)] (f hook))
            (let [command ((or (:command-fn o) worker-command) settings)
                  pb (doto (ProcessBuilder. ^java.util.List command)
                       (.redirectOutput (io/file case-dir "stdout.log"))
                       (.redirectError (io/file case-dir "stderr.log")))
                  p (locking lifecycle-lock
                      (when @shutdown-started?
                        (throw (ex-info "Shutdown started before worker launch" {})))
                      (let [p ((or (:start-process-fn o)
                                   (fn [^ProcessBuilder builder] (.start builder))) pb)]
                        (reset! child p)
                        p))
                  remaining (max 0 (- deadline (System/nanoTime)))
                  exited? (.waitFor p remaining TimeUnit/NANOSECONDS)
                  _ (when-not exited?
                      (reset! termination
                              (terminate! p (:termination-grace-ms o) (:force-reap-ms o))))
                  ev (evidence case-dir)
                  identity (:identity ev)
                  identity-ok? (and (= :identity (:kind identity)) (= (.pid p) (:pid identity)))
                  exit-code (when-not (.isAlive p) (.exitValue p))
                  status (cond (not exited?) :timeout
                               (not identity-ok?) :identity-error
                               (:evidence-error ev) :evidence-error
                               (not= 0 exit-code) :error
                               (not (contains? #{:ok :success :complete} (get-in ev [:report :status]))) :invalid
                               :else :ok)]
              (merge ev {:status status :pid (.pid p) :identity-verified? identity-ok?
                         :exit-code exit-code
                         :termination (or @termination {:outcome :normal :reaped? true})}))
            (catch Exception e
              (when-let [p @child]
                (reset! termination (terminate! p (:termination-grace-ms o) (:force-reap-ms o))))
              {:status :supervisor-error :error-class (.getName (class e))
               :termination @termination})
            (finally
              (try (.removeShutdownHook (Runtime/getRuntime) hook)
                   (catch IllegalStateException _ nil)))))
        result (merge result {:population population :case-dir case-dir :db-dir db-dir
                              :settings settings :timeout-seconds (:timeout-seconds o)
                              :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})]
    (spit (io/file case-dir "supervisor.edn") (pr-str result))
    result))

(defn run! [options]
  (let [o (validate-options options)
        run-dir (owned-directory! (:report-dir o) "run-")
        metadata (environment-metadata)
        _ (spit (io/file run-dir "metadata.edn") (pr-str metadata))
        cases (mapv #(run-case! (assoc o :run-dir run-dir :population %)) (:populations o))
        result {:status (if (every? #(= :ok (:status %)) cases) :ok :error)
                :run-dir run-dir :cases cases :metadata metadata}]
    (spit (io/file run-dir "comparison.edn") (pr-str result))
    result))

(defn -main [& args]
  (try
    (let [result (run! (parse-options args))]
      (println (pr-str (select-keys result [:status :run-dir])))
      (shutdown-agents)
      (System/exit (if (= :ok (:status result)) 0 1)))
    (catch Exception e
      (binding [*out* *err*] (println "island-perf failed:" (.getName (class e))))
      (shutdown-agents)
      (System/exit 1))))
