(ns georgetown.dev.island-perf-metrics
  (:require [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.lang ProcessHandle]
           [java.lang.management ManagementFactory]
           [com.sun.management OperatingSystemMXBean]))

(defn cpu-ns []
  (try (let [bean (ManagementFactory/getOperatingSystemMXBean)]
         (when (instance? OperatingSystemMXBean bean)
           (let [n (.getProcessCpuTime ^OperatingSystemMXBean bean)]
             (when (not (neg? n)) n))))
       (catch Exception _ nil)))

(defn boundary [] {:wall-ns (System/nanoTime) :cpu-ns (cpu-ns)})

(defn cpu-summary [start end]
  (if (and (:wall-ns start) (:wall-ns end) (:cpu-ns start) (:cpu-ns end)
           (> (:wall-ns end) (:wall-ns start)))
    (let [wall (- (:wall-ns end) (:wall-ns start))
          cpu (- (:cpu-ns end) (:cpu-ns start))]
      {:elapsed-ms (/ wall 1e6) :cpu-ms (/ cpu 1e6)
       :cpu-percent (* 100.0 (/ cpu wall))})
    {:unavailable "Missing measured wall/CPU boundary"}))

(defn percentile [durations p]
  (when (seq durations)
    (nth (vec (sort durations)) (dec (long (Math/ceil (* p (count durations))))))))

(defn latency-summary [durations]
  {:completed (count durations) :sum-tick-ms (reduce + 0.0 durations)
   :p50-ms (percentile durations 0.50) :p95-ms (percentile durations 0.95)
   :max-ms (when (seq durations) (apply max durations))
   :convention "nearest rank: ceil(p*n), sorted completed full-call durations"})

(defn proc-text [path]
  ;; Buffered line reads avoid FileInputStream.available on procfs pseudo-files.
  (with-open [r (java.io.BufferedReader. (java.io.FileReader. path))]
    (str/join "\n" (doall (line-seq r)))))

(defn resource-sample [phase reason]
  (let [heap (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))
        rss (try (some->> (re-find #"(?m)^VmRSS:\s+(\d+)\s+kB" (proc-text "/proc/self/status"))
                         second Long/parseLong (* 1024))
                 (catch Exception _ nil))]
    {:kind :resource :phase phase :reason reason :wall-ns (System/nanoTime)
     :timestamp-ms (System/currentTimeMillis) :pid (.pid (ProcessHandle/current))
     :rss-bytes rss :rss-source "/proc/self/status VmRSS (KiB * 1024)"
     :rss-unavailable (when-not rss "Linux VmRSS not readable")
     :heap-used-bytes (.getUsed heap) :heap-committed-bytes (.getCommitted heap)
     :heap-max-bytes (.getMax heap)}))

(defn resource-summary [samples start end]
  (let [samples (filter #(and (= :measured (:phase %))
                              (<= (:wall-ns start) (:wall-ns %) (:wall-ns end))) samples)
        rss (keep :rss-bytes samples) heap (keep :heap-used-bytes samples)]
    {:sample-count (count samples) :rss-sample-count (count rss)
     :heap-sample-count (count heap)
     :sampled-rss-peak-bytes (when (seq rss) (apply max rss))
     :sampled-heap-used-peak-bytes (when (seq heap) (apply max heap))
     :unavailable (when (or (empty? rss) (empty? heap)) "No successful measured RSS/heap samples")}))

(defn directory-size [path]
  (let [files (filter #(.isFile %) (file-seq (io/file path)))]
    {:logical-file-bytes (reduce + 0 (map #(.length %) files))
     :file-count (count files) :convention "sum of regular file lengths; not disk allocation or resident memory"}))

(defn resource-identity [name]
  (when-let [url (io/resource name)]
    (with-open [stream (.openStream url)]
      (let [digest (java.security.MessageDigest/getInstance "SHA-256")
            buffer (byte-array 8192)]
        (loop [] (let [n (.read stream buffer)]
                   (when (pos? n) (.update digest buffer 0 n) (recur))))
        {:resource (str url)
         :sha256 (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest)))}))))

(defn environment []
  {:os (System/getProperty "os.name") :os-version (System/getProperty "os.version")
   :architecture (System/getProperty "os.arch")
   :cpu-model (try (second (re-find #"(?m)^model name\s+:\s+(.+)$" (proc-text "/proc/cpuinfo")))
                   (catch Exception _ nil))
   :logical-cores (.availableProcessors (Runtime/getRuntime))
   :jvm-version (System/getProperty "java.version")
   :jvm-name (System/getProperty "java.vm.name")
   :jvm-options (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
   :heap-max-bytes (.maxMemory (Runtime/getRuntime))
   :dependency-identities (into {} (for [name ["dat/api.clj" "dat/schema.cljc" "datalevin/core.clj" "datalevin/binding/cpp.clj"]] [name (resource-identity name)]))
   :harness-identities (into {} (for [name ["georgetown/dev/island_perf_worker.clj" "georgetown/dev/island_perf_fixture.clj" "georgetown/dev/island_perf_metrics.clj" "georgetown/sim/tick.clj" "georgetown/sim/blueprints.cljc"]] [name (resource-identity name)]))
   :dat-api-resource (str (io/resource "dat/api.clj"))
   :datalevin-resource (str (io/resource "datalevin/core.clj"))
   :classpath (System/getProperty "java.class.path")})
