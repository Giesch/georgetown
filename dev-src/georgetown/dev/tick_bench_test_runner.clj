(ns georgetown.dev.tick-bench-test-runner
  (:require [clojure.test :as test]
            [georgetown.dev.tick-bench-test]
            [georgetown.dev.tick-bench-fixture-test]
            [georgetown.dev.tick-bench-report-test]
            [georgetown.dev.tick-bench-cli-test]))

(defn -main [& _args]
  (let [result (test/run-tests 'georgetown.dev.tick-bench-test
                               'georgetown.dev.tick-bench-fixture-test
                               'georgetown.dev.tick-bench-report-test
                               'georgetown.dev.tick-bench-cli-test)
        failed (+ (:fail result) (:error result))]
    (shutdown-agents)
    (System/exit (if (pos? failed) 1 0))))
