(ns georgetown.dev.tick-bench-report-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [criterium.core :as criterium]
            [fastester.display :as display]
            [georgetown.dev.tick-bench-report :as report]))

(def durations (mapv #(+ 1000000000 (* % 10000000)) (range 30)))
(def secret "SYNTHETIC-ARGUMENT-SECRET-DO-NOT-PERSIST")

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "tick-bench-report-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree! [dir]
  (doseq [f (reverse (file-seq dir))] (io/delete-file f true)))

(deftest criterium-units-schema-test
  (with-redefs [criterium/runtime-details (constantly {:input-arguments [secret]})]
    (let [stats (report/statistics durations {:bootstrap-size 100})]
      (is (= 1 (:execution-count stats)))
      (is (= 30 (:sample-count stats)))
      (is (= durations (:samples stats)))
      (is (< (Math/abs (- 1.145 (first (:sample-mean stats)))) 1.0e-10))
      ;; Bootstrap point estimates need not equal the arithmetic sample mean.
      (is (< 1.0 (first (:mean stats)) 1.3))
      (is (< 0 (first (:variance stats)) 0.01))
      (is (= 2 (count (:mean stats))))
      (is (= 2 (count (second (:mean stats)))))
      (is (= {:tail-quantile 0.025 :bootstrap-size 100} (:options stats)))
      (is (= :seconds (get-in stats [:tick-bench/measurement :time-unit])))
      (is (= :seconds-squared (get-in stats [:tick-bench/measurement :variance-unit])))
      (is (not (contains? stats :runtime-details)))
      (is (not (str/includes? (pr-str stats) secret)))))
  (doseq [samples [[] [1 2] (repeat 10 0) (repeat 10 1.5) (repeat 10 -1)]]
    (is (thrown? clojure.lang.ExceptionInfo (report/statistics samples))))
  (is (thrown? clojure.lang.ExceptionInfo
               (report/statistics durations {:input-arguments [secret]}))))

(defn local-links [text]
  (for [[_ link] (re-seq #"(?:href|src)=\"([^\"]+)\"" text)
        :when (not (or (str/starts-with? link "#")
                       (re-find #"^[a-zA-Z]+:" link)))]
    link))

(defn regenerate-outside-cwd! [dir]
  (let [classpath (->> (str/split (System/getProperty "java.class.path")
                                 (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
                       (map #(.getCanonicalPath (io/file %)))
                       (str/join java.io.File/pathSeparator))
        code (str "(require 'georgetown.dev.tick-bench-report) "
                  "(georgetown.dev.tick-bench-report/generate! "
                  (pr-str (.getCanonicalPath dir)) ")")
        process (-> (ProcessBuilder.
                     (into-array String [(str (System/getProperty "java.home") "/bin/java")
                                         "-Djava.awt.headless=true" "-cp" classpath
                                         "clojure.main" "-e" code]))
                    (.directory (io/file "/tmp"))
                    (.redirectErrorStream true)
                    (.start))
        output (future (slurp (.getInputStream process)))]
    (when-not (.waitFor process 90 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Outside-CWD regeneration timed out" {})))
    {:exit (.exitValue process) :output @output}))

(deftest eight-case-report-regeneration-test
  (let [dir (temp-dir)]
    (try
      (let [stats (with-redefs [criterium/runtime-details
                               (constantly {:input-arguments [secret]})]
                    (report/statistics durations {:bootstrap-size 100}))
            ;; save-result! must also sanitize results from other callers.
            stats (assoc stats :runtime-details {:input-arguments [secret]}
                               :input-arguments [secret])
            cases (vec (for [shift [:morning :afternoon :evening :night]
                             population [50 500]]
                         {:scenario :baseline :shift shift :population population}))]
        (doseq [[index case] (map-indexed vector cases)]
          (report/save-result! dir index case stats))
        (spit (io/file dir "raw.edn") (pr-str {:not-a-result true}))
        (let [opts {:results-directory (str (.getCanonicalPath dir) "/results/")}
              files (vec (display/get-result-filenames opts))
              results (display/load-results files)
              organized (display/organize-data results)]
          (is (= 8 (count files)))
          (is (= 4 (count organized)))
          (doseq [group (vals organized)]
            (is (= #{50 500} (set (keys (get group report/tick-expression))))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (report/save-result! dir 8 (first cases) stats)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (report/save-result! dir 0 (assoc (first cases) :scenario :other) stats))))
        (is (= 8 (:case-count (report/generate! dir))))
        ;; Never evaluate an existing edited options file during regeneration.
        (spit (io/file dir "reports" "fastester-options.clj")
              "(throw (Exception. \"untrusted options were evaluated\"))")
        (let [subprocess (regenerate-outside-cwd! dir)]
          (is (= 0 (:exit subprocess)) (:output subprocess)))
        (doseq [filename ["index.html" "report.md"]]
          (let [f (io/file dir "reports" filename)
                text (slurp f)
                links (local-links text)]
            (is (str/includes? text "Custom timing"))
            (is (not (re-find #"(?i)<script|<button|<link" text)))
            (is (= 8 (count (filter #(str/ends-with? % ".edn") links))))
            (is (= 4 (count (filter #(str/ends-with? % ".svg") links))))
            (doseq [link links]
              (is (not (str/starts-with? link "/")) link)
              (is (.isFile (io/file (.getParentFile f) link)) link))))
        (doseq [f (filter #(.isFile %) (file-seq dir))]
          (is (not (str/includes? (slurp f) secret)) (.getPath f)))
        (is (= :static (:presentation (edn/read-string
                                      (slurp (io/file dir "report-manifest.edn")))))))
      (finally (delete-tree! dir)))))

(deftest empty-results-and-invalid-case-test
  (let [dir (temp-dir)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo (report/generate! dir)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (report/save-result! dir 0 {:scenario "../escape"
                                               :shift :night :population 50} {})))
      (finally (delete-tree! dir)))))
