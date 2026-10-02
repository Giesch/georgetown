(ns georgetown.dev.tick-bench-test
  (:require [clojure.test :refer :all]
            [georgetown.dev.tick-bench :as bench]
            [georgetown.dev.tick-bench-fixture :as fixture]
            [georgetown.dev.tick-bench-report :as report]
            [georgetown.sim.tick :as tick]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(deftest option-validation-test
  (is (= 8 (count (bench/cases (bench/options {})))))
  (is (= 2 (:samples (bench/options {:smoke true}))))
  (doseq [opts [{:populations [51]} {:shifts [:dawn]} {:scenarios [:unknown]}
                {:samples 9} {:warmup-calls 3} {:warmup-ns 1} {:warmup-limit 1001}
                {:populations []} {:shifts [:night :night]} {:unknown true}]]
    (is (thrown? Exception (bench/options opts)) (pr-str opts)))
  (is (= [:night] (:shifts (bench/options (bench/parse-cli ["--shift" "night"])))))
  (is (thrown? Exception (bench/parse-cli ["--bogus"]))))

(deftest single-tick-timing-order-test
  (let [events (atom []) times (atom [10 35])
        op (fn [x] #(swap! events conj x))
        duration (bench/timed-call! {:restore! (op :restore) :tick! (op :tick)
                                    :verify! (op :verify)
                                    :clock #(do (swap! events conj :clock)
                                                (let [v (first @times)] (swap! times rest) v))})]
    (is (= 25 duration))
    (is (= [:restore :clock :tick :clock :verify] @events))))

(deftest warmup-budget-test
  (let [ticks (atom 0) clock (atom 0)
        ops {:restore! #(swap! clock + 100) :tick! #(do (swap! ticks inc) (swap! clock + 3))
             :verify! (fn []) :clock #(deref clock)}
        opts {:warmup-calls 4 :warmup-ns 15 :warmup-limit 10 :samples 2}
        result (bench/measure! opts ops)]
    (is (= 5 (get-in result [:warmup :calls])))
    (is (= 15 (get-in result [:warmup :tick-ns])))
    (is (= 515 (get-in result [:warmup :wall-ns])))
    (is (= [3 3] (:samples result)))
    (is (= 7 @ticks))
    (is (thrown? Exception (bench/measure! (assoc opts :warmup-limit 4) ops)))
    (is (= 4 (get-in (bench/measure! (assoc opts :warmup-ns 1) ops) [:warmup :calls])))))

(deftest failed-tick-no-success-test
  (let [verified (atom false)]
    (is (thrown? Exception
                 (bench/timed-call! {:restore! (fn []) :clock (constantly 0)
                                     :tick! #(throw (ex-info "injected" {}))
                                     :verify! #(reset! verified true)})))
    (is (false? @verified))))

(deftest artifact-failure-status-test
  (doseq [phase [:restore :tick :statistics :report]]
    (let [closed (atom 0) path (atom nil)
          boom #(throw (ex-info "synthetic-secret-not-for-artifacts" {}))
          bindings {#'bench/scenarios {:baseline (fn [_ _] {:island-id :test :epoch 0 :manifest {}})}
                    #'fixture/open-db! (fn [_] :fake)
                    #'fixture/close! (fn [_] (swap! closed inc))
                    #'fixture/restore! (fn [_ _] (when (= phase :restore) (boom)))
                    #'fixture/epoch (fn [_ _] 1)
                    #'tick/tick! (fn [_ _] (when (= phase :tick) (boom)))
                    #'bench/measure! (fn [_ operations]
                                      (bench/timed-call! operations)
                                      {:warmup {:calls 4 :tick-ns 10000000000 :wall-ns 1}
                                       :samples (vec (repeat 10 100))})
                    #'report/statistics (fn [_ _] (when (= phase :statistics) (boom)) {})
                    #'report/save-result! (fn [& _])
                    #'report/generate! (fn [_] (when (= phase :report) (boom)))}]
      (with-redefs-fn bindings
        #(try (bench/run! {:populations [50] :shifts [:morning] :samples 10})
              (is false "injected failure must propagate")
              (catch clojure.lang.ExceptionInfo error
                (reset! path (:run-directory (ex-data error))))))
      (is (= 1 @closed))
      (let [text (slurp (io/file @path "status.edn")) status (edn/read-string text)]
        (is (= :failed (:status status)))
        (is (not (.contains text "synthetic-secret")))
        (when (#{:restore :tick :statistics} phase) (is (empty? (:cases status))))))))

(deftest requested-settings-artifact-test
  (with-redefs [bench/scenarios {:baseline (fn [_ _] {:island-id :test :epoch 0})}
                fixture/open-db! (fn [_] :fake) fixture/close! (fn [_])
                bench/measure! (fn [_ _] {:warmup {:calls 1 :tick-ns 1 :wall-ns 1} :samples [1 1]})]
    (let [requested {:smoke true :samples 30 :populations [50] :shifts [:night]}
          result (bench/run! requested)]
      (is (= requested (get-in result [:status :requested-settings])))
      (is (= 2 (get-in result [:status :settings :samples]))))))

(deftest post-result-failure-quarantine-test
  (doseq [failure [:close :status-write]]
    (let [path (atom nil) original-write bench/write-edn!]
      (with-redefs [bench/scenarios {:baseline (fn [_ _] {:island-id :test :epoch 0})}
                    fixture/open-db! (fn [_] :fake)
                    fixture/close! (fn [_] (when (= failure :close) (throw (ex-info "close" {}))))
                    bench/measure! (fn [_ _] {:warmup {:calls 4 :tick-ns 10000000000 :wall-ns 1}
                                             :samples (vec (repeat 10 1))})
                    report/statistics (fn [_ _] {})
                    report/save-result! (fn [root index _ _]
                                          (original-write (io/file root "results" (str "version " report/result-version)
                                                                   (str "test-" index ".edn")) {}))
                    bench/write-edn! (fn [file value]
                                      (when (and (= failure :status-write) (= :running (:status value)) (seq (:cases value)))
                                        (throw (ex-info "status write" {})))
                                      (original-write file value))]
        (try (bench/run! {:populations [50] :shifts [:night] :samples 10})
             (is false "failure must propagate")
             (catch clojure.lang.ExceptionInfo error (reset! path (:run-directory (ex-data error))))))
      (let [root (io/file @path) status (edn/read-string (slurp (io/file root "status.edn")))]
        (is (= :failed (:status status)))
        (is (empty? (:cases status)))
        (is (.exists (io/file root "cases/0/failed-result.edn")))
        (is (not (.exists (io/file root "results" (str "version " report/result-version) "test-0.edn"))))))))

(deftest output-path-safety-test
  (let [paths (doall (repeatedly 4 bench/create-run!))]
    (is (= 4 (count (distinct (map #(.getCanonicalPath %) paths)))))
    (is (every? #(.contains (.getCanonicalPath %) "/target/tick-bench/") paths))))
