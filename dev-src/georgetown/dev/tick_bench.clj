(ns georgetown.dev.tick-bench
  "Development-only full tick measurements; no application startup or configured DB."
  (:refer-clojure :exclude [run!])
  (:require [hyperfiddle.rcf :as rcf]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [georgetown.dev.tick-bench-report :as report])
  (:import [java.nio.file Files]
           [java.util UUID]))

;; The benchmarking entry point must not enable inline application tests.
(rcf/enable! false)
(require '[georgetown.dev.tick-bench-fixture :as fixture]
         '[georgetown.sim.tick :as tick])

(def defaults {:populations [50 500] :shifts [:morning :afternoon :evening :night]
               :scenarios [:baseline] :samples 30 :warmup-calls 4
               :warmup-ns 10000000000 :warmup-limit 1000 :smoke false
               :bootstrap-size 1000 :tail-quantile 0.025})

(defn options
  "Resolve selections and reject invalid options before creating any directory or DB."
  [requested]
  (when-let [unknown (seq (remove (set (keys defaults)) (keys requested)))]
    (throw (ex-info "Unknown benchmark options" {:options unknown})))
  (let [opts (merge defaults requested)
        opts (if (:smoke opts)
               (assoc opts :samples 2 :warmup-calls 1 :warmup-ns 0) opts)]
    (doseq [[k allowed] [[:populations #{50 500}]
                        [:shifts #{:morning :afternoon :evening :night}]
                        [:scenarios #{:baseline}]]]
      (when-not (and (sequential? (k opts)) (seq (k opts))
                     (= (count (k opts)) (count (distinct (k opts))))
                     (every? allowed (k opts)))
        (throw (ex-info "Invalid benchmark selection" {:option k :value (k opts)}))))
    (when-not (boolean? (:smoke (merge defaults requested))) (throw (ex-info "Smoke must be boolean" {})))
    (doseq [k [:samples :warmup-calls :warmup-ns :warmup-limit :bootstrap-size]]
      (when-not (and (integer? (k opts)) (pos? (k opts)))
        (when-not (and (= k :warmup-ns) (:smoke opts) (zero? (k opts)))
          (throw (ex-info "Expected positive integer" {:option k})))))
    (when (and (not (:smoke opts))
               (or (< (:samples opts) 10) (< (:warmup-calls opts) 4)
                   (< (:warmup-ns opts) 10000000000)))
      (throw (ex-info "Normal minima: 10 samples, 4 warmups and 10 seconds tick time" {})))
    (when (or (> (:warmup-limit opts) 1000)
              (> (:warmup-calls opts) (:warmup-limit opts)))
      (throw (ex-info "Warmup call limit must cover calls and be at most 1000" {})))
    (when (< (:bootstrap-size opts) 10)
      (throw (ex-info "Bootstrap size must be at least 10" {})))
    (when-not (and (number? (:tail-quantile opts)) (< 0 (:tail-quantile opts) 0.5))
      (throw (ex-info "Invalid tail quantile" {})))
    opts))

(def scenarios {:baseline fixture/fixture})

(defn cases [opts]
  (vec (for [scenario (:scenarios opts) shift (:shifts opts) population (:populations opts)]
         {:scenario scenario :shift shift :population population})))

(defn timed-call!
  "Restore/check outside two timestamps; duration brackets exactly one tick call."
  [{:keys [restore! tick! verify! clock]}]
  (restore!)
  (let [start (clock)]
    (tick!)
    (let [duration (- (clock) start)]
      (verify!)
      duration)))

(defn measure!
  "Injectable operations permit timing-order tests without a native solver."
  [opts operations]
  (let [wall-start ((:clock operations))
        warmup (loop [n 0 total 0]
                 (cond
                   (and (>= n (:warmup-calls opts)) (>= total (:warmup-ns opts)))
                   {:calls n :tick-ns total :wall-ns (- ((:clock operations)) wall-start)}
                   (>= n (:warmup-limit opts))
                   (throw (ex-info "Warmup minima unmet at call budget" {:phase :warmup :calls n :tick-ns total}))
                   :else (recur (inc n) (+ total (timed-call! operations)))))]
    {:warmup warmup
     :samples (mapv (fn [_] (timed-call! operations)) (range (:samples opts)))}))

(defn write-edn! [file value]
  (io/make-parents file)
  (spit file (str (pr-str value) "\n")))

(defn create-run!
  "Atomic unique directory creation strictly beneath target/tick-bench."
  []
  (let [root (.toPath (io/file "target/tick-bench"))]
    (Files/createDirectories root (make-array java.nio.file.attribute.FileAttribute 0))
    (.toFile (Files/createDirectory (.resolve root (str (System/currentTimeMillis) "-" (UUID/randomUUID)))
                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn provenance []
  ;; Deliberately do not collect environment, config, runtime-details or JVM arguments.
  {:clojure (clojure-version) :java-version (System/getProperty "java.version")
   :java-vendor (System/getProperty "java.vendor") :os-name (System/getProperty "os.name")
   :os-arch (System/getProperty "os.arch")
   :dependencies {:fastester "1" :criterium "0.4.6" :datalevin "1.0.2"}
   :source-revision (try (let [process (.start (ProcessBuilder. ["git" "rev-parse" "HEAD"]))
                               output (str/trim (slurp (.getInputStream process)))]
                           (when (zero? (.waitFor process)) output))
                         (catch Exception _ nil))})

(defn run!
  "Run sequential cases. Throws with phase/case/run context and preserves partial artifacts."
  ([] (run! {}))
  ([requested]
   (let [opts (options requested) run-dir (create-run!)
         status (atom {:format-version 1 :run-id (.getName run-dir) :status :running
                       :mode (if (:smoke opts) :smoke-non-statistical :normal)
                       :requested-settings requested :settings opts :provenance (provenance) :cases []})
         phase (atom :fixture) current (atom nil)
         persist! #(write-edn! (io/file run-dir "status.edn") @status)]
     (persist!)
     (try
       (doseq [[index case] (map-indexed vector (cases opts))]
         (reset! current case)
         (reset! phase :fixture)
         (swap! status assoc :active-case (assoc case :index index :status :running))
         (persist!)
         (let [data ((get scenarios (:scenario case)) (:population case) (:shift case))
               case-dir (io/file run-dir "cases" (str index))]
           (write-edn! (io/file case-dir "fixture.edn") data)
           (reset! phase :open)
           (let [db (fixture/open-db! (.getPath (io/file case-dir "db")))]
             (try
               (reset! phase :measurement)
               (let [measured (measure! opts
                                       {:clock #(System/nanoTime)
                                        :restore! #(fixture/restore! db data)
                                        :tick! #(tick/tick! db (:island-id data))
                                        :verify! #(when-not (= (inc (:epoch data)) (fixture/epoch db data))
                                                    (throw (ex-info "Tick did not advance epoch once" {})))})]
                 (write-edn! (io/file case-dir "raw.edn") (assoc measured :units :ns :case case))
                 (when-not (:smoke opts)
                   (reset! phase :statistics)
                   (let [stats (report/statistics (:samples measured) (select-keys opts [:bootstrap-size :tail-quantile]))]
                     (reset! phase :result)
                     (report/save-result! run-dir index case stats)))
                 (swap! status update :cases conj (assoc case :index index :status :complete
                                                        :warmup (:warmup measured)
                                                        :sample-count (count (:samples measured))))
                 (persist!))
               (finally (fixture/close! db))))))
       (swap! status dissoc :active-case)
       (when-not (:smoke opts)
         (reset! phase :report)
         (report/generate! run-dir))
       (swap! status #(-> % (assoc :status :complete) (dissoc :active-case)))
       (persist!)
       {:run-directory (.getCanonicalPath run-dir) :status @status}
       (catch Throwable error
         ;; Quarantine a published result if this case's lifecycle did not finish.
         (when-let [index (get-in @status [:active-case :index])]
           (let [result (io/file run-dir "results" (str "version " report/result-version)
                                 (str "test-" index ".edn"))]
             (when (.exists result)
               (let [diagnostic (io/file run-dir "cases" (str index) "failed-result.edn")]
                 (io/make-parents diagnostic)
                 (Files/move (.toPath result) (.toPath diagnostic)
                             (make-array java.nio.file.CopyOption 0)))))
           (swap! status update :cases
                  #(vec (remove (fn [entry] (= index (:index entry))) %))))
         (swap! status update :active-case #(when % (assoc % :status :failed :phase @phase)))
         (swap! status assoc :status :failed :failure {:phase @phase :case @current
                                                      :error-class (.getName (class error))})
         (persist!)
         (throw (ex-info "Benchmark failed; partial artifacts preserved"
                         {:phase @phase :case @current :run-directory (.getCanonicalPath run-dir)} error)))))))

(defn parse-cli [args]
  (loop [args args opts {}]
    (if (empty? args) opts
      (let [[flag value & more] args]
        (case flag
          "--smoke" (recur (rest args) (assoc opts :smoke true))
          "--population" (recur more (assoc opts :populations [(Long/parseLong value)]))
          "--shift" (recur more (assoc opts :shifts [(keyword value)]))
          "--scenario" (recur more (assoc opts :scenarios [(keyword value)]))
          "--samples" (recur more (assoc opts :samples (Long/parseLong value)))
          "--warmup-calls" (recur more (assoc opts :warmup-calls (Long/parseLong value)))
          "--warmup-seconds" (recur more (assoc opts :warmup-ns (long (* 1e9 (Double/parseDouble value)))))
          "--warmup-limit" (recur more (assoc opts :warmup-limit (Long/parseLong value)))
          (throw (ex-info "Unknown CLI option" {:option flag})))))))

(defn -main [& args]
  (try
    (if (= "--regenerate" (first args))
      (do (when-not (= 2 (count args)) (throw (ex-info "Regenerate requires one run directory" {})))
          (report/generate! (io/file (second args)))
          (println "Reports regenerated"))
      (println (pr-str (select-keys (run! (parse-cli args)) [:run-directory]))))
    (shutdown-agents)
    (System/exit 0)
    (catch Throwable error
      ;; Do not print library exception text or argument dumps.
      (binding [*out* *err*]
        (println "tick-bench failed"
                 (pr-str (select-keys (ex-data error) [:phase :case :run-directory]))))
      (System/exit 1))))
