(ns georgetown.dev.island-perf-worker
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dat.api :as dat]
            [georgetown.server.db :as db-api]
            [georgetown.server.config :as config]
            [georgetown.sim.schema :as schema]
            [georgetown.sim.tick :as tick]
            [georgetown.sim.rules.allocation :as allocation]
            [georgetown.dev.island-perf-metrics :as metrics]
            [georgetown.dev.island-perf-fixture :as fixture])
  (:import [java.lang ProcessHandle]))

(defn write-edn! [path value] (spit path (str (pr-str value) "\n")))

(defn run-worker! [{:keys [case-dir db-dir population warmup measured sample-ms] :as settings}]
  (let [phase (atom :setup) records-file (io/file case-dir "records.edn")
        lock (Object.) samples (atom []) observations (atom []) durations (atom [])
        boundaries (atom {}) db-sizes (atom {}) completed (atom {:warmup 0 :measured 0})
        running (atom true) connection (atom nil) failure (atom nil)
        fixture-data (atom nil) drift (atom {})
        emit! (fn [record] (locking lock (spit records-file (str (pr-str record) "\n") :append true)))
        sample! (fn [reason] (locking lock
                              (let [s (metrics/resource-sample @phase reason)]
                                (swap! samples conj s) (emit! s))))
        mark! (fn [p] (locking lock (reset! phase p)
                               (emit! {:kind :phase :phase p :wall-ns (System/nanoTime)
                                       :timestamp-ms (System/currentTimeMillis)})))
        deny (fn [& _] (throw (ex-info "Configured database/config access forbidden in benchmark child" {})))]
    (emit! {:kind :identity :pid (.pid (ProcessHandle/current)) :settings settings})
    (let [sampler (Thread. (fn []
                            (try (while @running
                                   (sample! :periodic)
                                   (Thread/sleep (long (or sample-ms 100))))
                                 (catch InterruptedException _ nil))) "island-perf-sampler")]
      (.setDaemon sampler true)
      (.start sampler)
      (with-redefs [db-api/db deny db-api/connect! deny config/get deny config/config (delay (deny))]
        (try
          (mark! :setup)
          (when (= :setup-stall (:test-mode settings)) (Thread/sleep 600000))
          (when (= :setup-error (:test-mode settings)) (throw (ex-info "Injected setup failure" {})))
          (when (= :singleton-access (:test-mode settings)) (db-api/db))
          (when (= :config-access (:test-mode settings)) (config/get :db-dir))
          (let [f (if-let [path (:fixture settings)]
                    (edn/read-string (slurp path))
                    (fixture/fixture population))
                island-id (:island-id f)]
            (reset! fixture-data f)
            (write-edn! (io/file case-dir "initial-fixture.edn") f)
            (reset! connection (dat/init! :dat.db/datalevin schema/schema {:dir db-dir}))
            (dat/transact! @connection (:txs f))
            (swap! drift assoc :initial (fixture/observe @connection island-id {}))
            (swap! db-sizes assoc :setup-end (metrics/directory-size db-dir))
            (when (= :persisted-epoch (:test-mode settings))
              (dat/close! @connection)
              (reset! connection (dat/init! :dat.db/datalevin schema/schema {:dir db-dir})))
            (doseq [[p n] [[:warmup warmup] [:measured measured]]]
              (mark! p)
              (when (= p :measured)
                (swap! boundaries assoc :start (metrics/boundary))
                (sample! :entry))
              (try
                (dotimes [i n]
                  (when (= (keyword (str (name p) "-error")) (:test-mode settings))
                    (when (= i 1) (throw (ex-info "Injected phase failure" {:phase p}))))
                  (let [witness (atom {:gross-food-production 0.0 :paid-wages 0.0})
                        original allocation/apply-assignment-effects
                        duration (atom nil)]
                    (with-redefs [allocation/apply-assignment-effects
                                  (fn [world citizen-id offer]
                                    (swap! witness #(merge-with + % (fixture/activity-witness world citizen-id offer)))
                                    (original world citizen-id offer))]
                      (let [start (System/nanoTime)]
                        (tick/tick! @connection island-id)
                        (reset! duration (/ (- (System/nanoTime) start) 1e6))))
                    (let [ms @duration]
                      (swap! completed update p inc)
                      (when (= p :measured) (swap! durations conj ms))
                      (emit! {:kind :tick :phase p :index i :duration-ms ms})
                      (let [obs (assoc (fixture/observe @connection island-id @witness)
                                      :phase p :index i :kind :observation)]
                        (swap! observations conj obs) (emit! obs)))))
                (finally
                  (when (= p :measured)
                    (locking lock
                      (sample! :exit)
                      (swap! boundaries assoc :end (metrics/boundary))
                      (mark! :measured-ended)))))
              (swap! drift assoc (keyword (str (name p) "-end")) (fixture/observe @connection island-id {}))
              (swap! db-sizes assoc (keyword (str (name p) "-end")) (metrics/directory-size db-dir))))
          (catch Throwable e
            (reset! failure {:class (.getName (class e)) :message (.getMessage e)
                             :phase (if (= :measured-ended @phase) :measured @phase) :completed @completed})
            (emit! {:kind :error :failure @failure}))
          (finally
            (reset! running false) (.interrupt sampler) (.join sampler 2000)
            (when @connection
              (try
                (when (= :persisted-epoch (:test-mode settings))
                  (dat/close! @connection)
                  (reset! connection (dat/init! :dat.db/datalevin schema/schema {:dir db-dir}))
                  (swap! drift assoc :reopened (fixture/observe @connection (:island-id @fixture-data) {})))
                (dat/close! @connection)
                   (catch Throwable e (reset! failure {:phase :close :message (.getMessage e)})))))))
      (let [viability (into {} (for [p [:warmup :measured]] [p (fixture/viability @observations p)]))
            status (cond @failure :error
                         (some #(= :fixture-invalid (:status %)) (vals viability)) :fixture-invalid
                         :else :ok)
            report {:status status :failure @failure :settings settings
                    :pid (.pid (ProcessHandle/current))
                    :singleton-uninitialized? (nil? @db-api/db-atom)
                    :excluded-namespaces-loaded (vec (filter #(find-ns %) '[georgetown.core georgetown.server.scheduler georgetown.server.push georgetown.dev.core]))
                    :environment (metrics/environment)
                    :requested {:warmup warmup :measured measured} :completed @completed
                    :fixture-manifest (:manifest @fixture-data)
                    :drift @drift :viability viability :boundaries @boundaries
                    :db-directory-sizes @db-sizes
                    :latency (metrics/latency-summary @durations)
                    :cpu (metrics/cpu-summary (:start @boundaries) (:end @boundaries))
                    :resources (if (and (:start @boundaries) (:end @boundaries))
                                 (metrics/resource-summary @samples (:start @boundaries) (:end @boundaries))
                                 {:unavailable "No measured boundaries"})}]
        (write-edn! (io/file case-dir "report.edn") report)
        (emit! {:kind :finished :status status})
        report))))

(defn -main [settings-edn]
  (try
    (let [report (run-worker! (edn/read-string settings-edn))]
      (shutdown-agents)
      (System/exit (if (= :ok (:status report)) 0 1)))
    (catch Throwable e
      (binding [*out* *err*] (println "Worker failed:" (.getMessage e)))
      (System/exit 1))))
