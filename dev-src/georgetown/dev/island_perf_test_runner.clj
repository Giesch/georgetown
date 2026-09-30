(ns georgetown.dev.island-perf-test-runner
  (:require [clojure.test :refer [deftest is run-tests]]
            [hyperfiddle.rcf :as rcf]))

(rcf/enable! false)

(def test-namespaces
  '[georgetown.dev.island-perf-metrics-test
    georgetown.dev.island-perf-test
    georgetown.dev.island-perf-fixture-test
    georgetown.dev.island-perf-worker-test])

(deftest deliberate-failure
  (is false "Requested deliberate test-runner failure"))

(defn -main [& args]
  (let [deliberate? (some #{"--deliberate-failure"} args)
        namespaces (cond-> test-namespaces
                     deliberate? (conj 'georgetown.dev.island-perf-test-runner))
        _ (doseq [namespace namespaces] (require namespace))
        result (apply run-tests namespaces)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
