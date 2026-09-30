(ns georgetown.dev.island-perf-worker-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [georgetown.dev.island-perf :as supervisor]
            [georgetown.dev.island-perf-fixture :as fixture])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-directory [f]
  (let [directory (.toFile (Files/createTempDirectory
                           "island-perf-worker-test-" (make-array FileAttribute 0)))]
    (try
      (f directory)
      (finally
        (doseq [file (reverse (file-seq directory))]
          (Files/deleteIfExists (.toPath file)))))))

(defn- run-case! [directory options]
  (let [result (supervisor/run-case!
                (merge {:run-dir (str directory) :population 8
                        :warmup 4 :measured 4 :sample-ms 20
                        :timeout-seconds 120}
                       options))]
    (when-not (:report result)
      (throw (ex-info "Worker did not produce a report"
                      {:result result
                       :stderr (slurp (io/file (:case-dir result) "stderr.log"))})))
    result))

(defn- records [result]
  (with-open [reader (io/reader (io/file (:case-dir result) "records.edn"))]
    (mapv edn/read-string (line-seq reader))))

(defn- canonical-initial [observation]
  ;; Database entity IDs and query iteration order are not fixture identity.
  (-> (walk/postwalk #(if (map? %) (dissoc % :db/id) %) observation)
      (update :offers #(vec (sort-by (comp str :offer/id) %)))))

(defn- assert-isolated-child [result]
  (is (:identity-verified? result))
  (is (not= (.pid (ProcessHandle/current)) (:pid result)))
  (is (= (:pid result) (get-in result [:report :pid])))
  (is (true? (get-in result [:report :singleton-uninitialized?])))
  (is (= [] (get-in result [:report :excluded-namespaces-loaded])))
  (is (true? (get-in result [:termination :reaped?])))
  (is (nil? (:evidence-error result))))

(defn- assert-cpu [report measured?]
  (let [cpu (:cpu report)]
    (if measured?
      (do
        (is (number? (get-in report [:boundaries :start :wall-ns])))
        (is (number? (get-in report [:boundaries :end :wall-ns])))
        (if (:unavailable cpu)
          (is (string? (:unavailable cpu)))
          (do (is (pos? (:elapsed-ms cpu)))
              (is (<= 0 (:cpu-ms cpu)))
              (is (<= 0 (:cpu-percent cpu))))))
      (do (is (empty? (:boundaries report)))
          (is (string? (:unavailable cpu)))))))

(deftest real-worker-two-cycles-and-fixture-restore-test
  (with-temp-directory
    (fn [directory]
      (let [result (run-case! directory {:test-mode :persisted-epoch})
            report (:report result)
            evidence (records result)
            observations (filterv #(= :observation (:kind %)) evidence)
            fixture-file (io/file (:case-dir result) "initial-fixture.edn")
            saved-fixture (edn/read-string (slurp fixture-file))]
        (is (= :ok (:status result)))
        (is (= 0 (:exit-code result)))
        (assert-isolated-child result)
        (is (= {:warmup 4 :measured 4} (:requested report) (:completed report)))
        (is (= 8 (get-in report [:fixture-manifest :population])))
        (is (= (:manifest saved-fixture) (:fixture-manifest report)))
        (is (= 0 (get-in report [:drift :initial :island-epoch])))
        (is (= 4 (get-in report [:drift :warmup-end :island-epoch])))
        (is (= 8 (get-in report [:drift :measured-end :island-epoch])))
        (is (= 8 (get-in report [:drift :reopened :island-epoch])))
        (is (= (mapv double (range 8)) (mapv :epoch observations)))
        (is (= (vec (range 1 9)) (mapv :island-epoch observations)))
        (doseq [phase [:warmup :measured]]
          (let [cycle (filterv #(= phase (:phase %)) observations)]
            (is (= fixture/shifts (mapv :shift cycle)))
            (is (= :valid (get-in report [:viability phase :status])))
            (is (= 4 (count cycle)))))
        (is (= :finished (:kind (last evidence))))
        (is (= 4 (get-in report [:latency :completed])))
        (is (pos? (get-in report [:db-directory-sizes :setup-end :logical-file-bytes])))
        (assert-cpu report true)
        (testing "saved transactions restore the same initial state into a fresh database"
          (let [restored (run-case! directory {:fixture (str fixture-file)})]
            (is (= :ok (:status restored)))
            (assert-isolated-child restored)
            (is (not= (:db-dir result) (:db-dir restored)))
            (is (not= (:pid result) (:pid restored)))
            (is (= (:fixture-manifest report) (get-in restored [:report :fixture-manifest])))
            (is (= (canonical-initial (get-in report [:drift :initial]))
                   (canonical-initial (get-in restored [:report :drift :initial]))))))))))

(deftest stock-only-fixture-is-rejected-by-real-worker-and-comparison-test
  (with-temp-directory
    (fn [directory]
      (let [initial (fixture/fixture 8)
            lots (get-in initial [:txs 0 :island/lots])
            farm-lots (filterv #(= :improvement.type/farm
                                  (get-in % [:lot/improvement :improvement/type])) lots)
            farm-deeds (set (map #(get-in % [:lot/deed :deed/id]) farm-lots))
            stock-only (-> initial
                           (assoc-in [:txs 0 :island/lots]
                                     (filterv #(not (contains? farm-deeds
                                                              (get-in % [:lot/deed :deed/id]))) lots))
                           (update-in [:txs 0 :island/players]
                                      (fn [players]
                                        (mapv #(update % :player/deeds
                                                       (fn [deeds]
                                                         (filterv (fn [deed]
                                                                    (not (contains? farm-deeds (:deed/id deed))))
                                                                  deeds)))
                                              players)))
                           (assoc-in [:manifest :buildings :farms] 0)
                           (assoc-in [:manifest :capacity :farm-jobs-per-shift] 0))
            fixture-file (io/file directory "stock-only-fixture.edn")
            _ (spit fixture-file (pr-str stock-only))
            comparison (supervisor/run! {:report-dir (str directory) :populations [8]
                                         :fixture (str fixture-file)
                                         :warmup 4 :measured 4 :sample-ms 20
                                         :timeout-seconds 120})
            result (first (:cases comparison))
            report (:report result)
            evidence (records result)
            observations (filterv #(= :observation (:kind %)) evidence)]
        (is (= 2 (count farm-lots)))
        (is (= :error (:status comparison) (:status result)))
        (is (= comparison (edn/read-string (slurp (io/file (:run-dir comparison) "comparison.edn")))))
        (is (= 1 (:exit-code result)))
        (assert-isolated-child result)
        (is (= :fixture-invalid (:status report)))
        (is (nil? (:failure report)))
        (is (= {:warmup 4 :measured 4} (:requested report) (:completed report)))
        (is (= 0 (get-in report [:fixture-manifest :buildings :farms])))
        (is (= 64.0 (reduce + 0.0
                            (map #(get-in % [:resource/food :stock/amount] 0.0)
                                 (vals (get-in report [:drift :initial :player-stocks]))))))
        (is (= 8 (count observations)))
        (doseq [phase [:warmup :measured]]
          (let [cycle (filterv #(= phase (:phase %)) observations)
                viability (get-in report [:viability phase])]
            (is (= fixture/shifts (mapv :shift cycle)))
            (is (= :fixture-invalid (:status viability)))
            (is (zero? (get-in viability [:activity :gross-food-production])))
            (is (some #{:gross-food-production} (:missing-activity viability)))
            (is (pos? (get-in viability [:activity :food-sales])))))
        (is (= {:kind :finished :status :fixture-invalid} (last evidence)))
        (is (= 4 (get-in report [:latency :completed])))
        (assert-cpu report true)))))

(deftest custom-short-worker-phases-remain-explicitly-unverified-test
  (with-temp-directory
    (fn [directory]
      (let [result (run-case! directory {:warmup 1 :measured 1})
            report (:report result)
            evidence (records result)
            observations (filterv #(= :observation (:kind %)) evidence)]
        (is (= :ok (:status result) (:status report)))
        (is (= 0 (:exit-code result)))
        (assert-isolated-child result)
        (is (= {:warmup 1 :measured 1} (:requested report) (:completed report)))
        (doseq [phase [:warmup :measured]]
          (is (= {:status :unverified :reason :no-complete-four-shift-cycle :phase phase}
                 (get-in report [:viability phase])))
          (is (= 1 (count (filter #(= phase (:phase %)) observations)))))
        (is (= {:kind :finished :status :ok} (last evidence)))
        (is (= 1 (get-in report [:latency :completed])))
        (assert-cpu report true)))))

(deftest worker-phase-errors-retain-partial-evidence-test
  (with-temp-directory
    (fn [directory]
      (doseq [[mode completed error-phase]
              [[:setup-error {:warmup 0 :measured 0} :setup]
               [:warmup-error {:warmup 1 :measured 0} :warmup]
               [:measured-error {:warmup 4 :measured 1} :measured]]]
        (testing (name mode)
          (let [result (run-case! directory {:test-mode mode})
                report (:report result)
                evidence (records result)
                ticks (filterv #(= :tick (:kind %)) evidence)
                observations (filterv #(= :observation (:kind %)) evidence)]
            (is (= :error (:status result) (:status report)))
            (is (= 1 (:exit-code result)))
            (assert-isolated-child result)
            (is (= completed (:completed report) (get-in report [:failure :completed])))
            (is (= error-phase (get-in report [:failure :phase])))
            (is (re-find #"Injected" (get-in report [:failure :message])))
            (doseq [phase [:warmup :measured]]
              (is (= (completed phase) (count (filter #(= phase (:phase %)) ticks))))
              (is (= (completed phase) (count (filter #(= phase (:phase %)) observations)))))
            (is (= (:failure report) (:failure (first (filter #(= :error (:kind %)) evidence)))))
            (is (= :finished (:kind (last evidence))))
            (is (= (:measured completed) (get-in report [:latency :completed])))
            (assert-cpu report (= mode :measured-error))))))))

(deftest worker-forbids-configured-database-and-config-access-test
  (with-temp-directory
    (fn [directory]
      (doseq [mode [:singleton-access :config-access]]
        (testing (name mode)
          (let [result (run-case! directory {:test-mode mode})
                report (:report result)]
            (is (= :error (:status result) (:status report)))
            (is (= 1 (:exit-code result)))
            (assert-isolated-child result)
            (is (= :setup (get-in report [:failure :phase])))
            (is (= {:warmup 0 :measured 0} (:completed report)))
            (is (re-find #"access forbidden" (get-in report [:failure :message])))
            (is (empty? (:drift report)))
            (is (empty? (:db-directory-sizes report)))
            (is (not (.exists (io/file (:case-dir result) "initial-fixture.edn"))))
            (is (empty? (seq (.listFiles (io/file (:db-dir result))))))
            (assert-cpu report false)))))))
