(ns georgetown.dev.island-perf-metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [georgetown.dev.island-perf-metrics :as metrics])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest nearest-rank-latency-summary
  (testing "empty durations have no percentile or maximum"
    (is (= {:completed 0
            :sum-tick-ms 0.0
            :p50-ms nil
            :p95-ms nil
            :max-ms nil
            :convention "nearest rank: ceil(p*n), sorted completed full-call durations"}
           (metrics/latency-summary []))))
  (testing "nearest rank uses ceil(p*n) on sorted completed durations"
    (is (= 10 (metrics/percentile [10 20 30 40] 0.25)))
    (is (= 20 (metrics/percentile [40 10 30 20] 0.50)))
    (is (= 40 (metrics/percentile [10 20 30 40] 0.95)))
    (is (= {:completed 4 :sum-tick-ms 100.0 :p50-ms 20 :p95-ms 40 :max-ms 40
            :convention "nearest rank: ceil(p*n), sorted completed full-call durations"}
           (metrics/latency-summary [40 10 30 20])))))

(deftest cpu-summary-boundaries
  (testing "CPU can exceed elapsed wall time when multiple cores are used"
    (is (= {:elapsed-ms 10.0 :cpu-ms 25.0 :cpu-percent 250.0}
           (metrics/cpu-summary {:wall-ns 1000000 :cpu-ns 500000}
                                {:wall-ns 11000000 :cpu-ns 25500000}))))
  (testing "a missing start or end measurement makes CPU unavailable"
    (is (= {:unavailable "Missing measured wall/CPU boundary"}
           (metrics/cpu-summary nil {:wall-ns 10 :cpu-ns 10})))
    (is (= {:unavailable "Missing measured wall/CPU boundary"}
           (metrics/cpu-summary {:wall-ns 10 :cpu-ns 10} nil)))
    (is (= {:unavailable "Missing measured wall/CPU boundary"}
           (metrics/cpu-summary {:wall-ns 10} {:wall-ns 20 :cpu-ns 15}))))
  (testing "partial boundary intervals use the overlapping measured wall interval"
    (is (= {:elapsed-ms 2.0 :cpu-ms 3.0 :cpu-percent 150.0}
           (metrics/cpu-summary {:wall-ns 1000000 :cpu-ns 2000000}
                                {:wall-ns 3000000 :cpu-ns 5000000})))
    (is (= {:unavailable "Missing measured wall/CPU boundary"}
           (metrics/cpu-summary {:wall-ns 5 :cpu-ns 10}
                                {:wall-ns 5 :cpu-ns 20})))))

(deftest resource-summary-filters-phase-and-boundaries
  (let [start {:wall-ns 100}
        end {:wall-ns 200}
        samples [{:phase :warmup :wall-ns 150 :rss-bytes 900 :heap-used-bytes 900}
                 {:phase :measured :wall-ns 99 :rss-bytes 800 :heap-used-bytes 800}
                 {:phase :measured :wall-ns 100 :rss-bytes 10 :heap-used-bytes 20}
                 {:phase :measured :wall-ns 150 :rss-bytes 30 :heap-used-bytes 40}
                 {:phase :measured :wall-ns 200 :rss-bytes 25 :heap-used-bytes 35}
                 {:phase :measured :wall-ns 201 :rss-bytes 700 :heap-used-bytes 700}]]
    (is (= {:sample-count 3 :rss-sample-count 3 :heap-sample-count 3
            :sampled-rss-peak-bytes 30 :sampled-heap-used-peak-bytes 40 :unavailable nil}
           (metrics/resource-summary samples start end))))
  (testing "missing successful RSS or heap measurements are unavailable"
    (is (= {:sample-count 1 :rss-sample-count 0 :heap-sample-count 1
            :sampled-rss-peak-bytes nil :sampled-heap-used-peak-bytes 20
            :unavailable "No successful measured RSS/heap samples"}
           (metrics/resource-summary [{:phase :measured :wall-ns 150 :heap-used-bytes 20}]
                                     {:wall-ns 100} {:wall-ns 200})))
    (is (= {:sample-count 0 :rss-sample-count 0 :heap-sample-count 0
            :sampled-rss-peak-bytes nil :sampled-heap-used-peak-bytes nil
            :unavailable "No successful measured RSS/heap samples"}
           (metrics/resource-summary [] {:wall-ns 100} {:wall-ns 200})))))

(deftest directory-size-sums-regular-file-lengths-in-owned-temp-directory
  (let [root (Files/createTempDirectory "island-perf-metrics-test-"
                                        (make-array FileAttribute 0))]
    (try
      (let [subdir (.resolve root "nested")]
        (Files/createDirectory subdir (make-array FileAttribute 0))
        (Files/write (.resolve root "first.bin") (byte-array [1 2 3])
                     (make-array java.nio.file.OpenOption 0))
        (Files/write (.resolve subdir "second.bin") (byte-array [4 5])
                     (make-array java.nio.file.OpenOption 0))
        (is (= {:logical-file-bytes 5
                :file-count 2
                :convention "sum of regular file lengths; not disk allocation or resident memory"}
               (metrics/directory-size (str root)))))
      (finally
        (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          (doseq [path (reverse (vec (.toArray paths)))]
            (Files/deleteIfExists ^java.nio.file.Path path)))))))
