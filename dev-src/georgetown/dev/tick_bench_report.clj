(ns georgetown.dev.tick-bench-report
  "Criterium statistics and static fastester reports for restored single ticks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [criterium.core :as criterium]
            [fastester.display :as display]))

(def statistics-options {:tail-quantile 0.025 :bootstrap-size 1000})
(def result-version "0.0.1")
(def tick-expression "georgetown.sim.tick/tick!")

(defn sanitize
  "Remove JVM runtime details and arguments before any result persistence."
  [value]
  (walk/postwalk #(if (map? %) (dissoc % :runtime-details :input-arguments) %) value))

(defn statistics
  "Compute public Criterium statistics from single-call integer nanoseconds.
  Derived times are seconds; variance is seconds squared. Smoke skips this API."
  ([durations] (statistics durations {}))
  ([durations opts]
   (let [samples (vec durations)
         options (merge statistics-options opts)]
     (when-not (and (>= (count samples) 10)
                    (every? #(and (integer? %) (pos? %) (<= % Long/MAX_VALUE)) samples)
                    (every? #{:tail-quantile :bootstrap-size} (keys opts))
                    (number? (:tail-quantile options))
                    (< 0 (:tail-quantile options) 0.5)
                    (integer? (:bootstrap-size options))
                    (>= (:bootstrap-size options) 10))
       (throw (ex-info "Invalid normal statistics samples or options" {:phase :statistics})))
     (-> (criterium/benchmark-stats {:samples samples :execution-count 1
                                     :sample-count (count samples)} options)
         sanitize
         (assoc :tick-bench/measurement {:timing :restored-single-full-tick
                                        :raw-unit :nanoseconds
                                        :time-unit :seconds
                                        :variance-unit :seconds-squared
                                        :statistics :criterium
                                        :reporting :fastester})))))

(defn- directory! [f]
  (.mkdirs (io/file f))
  (when-not (.isDirectory (io/file f))
    (throw (ex-info "Cannot create report directory" {:phase :report})))
  (io/file f))

(defn- root [run-dir] (.getCanonicalFile (io/file run-dir)))
(defn- slash [f] (str (.getPath (io/file f)) "/"))

(defn- case-metadata [{:keys [scenario shift population]} index]
  (let [scenario (if (keyword? scenario) (name scenario) scenario)
        shift (if (keyword? shift) (name shift) shift)]
    (when-not (and (string? scenario) (re-matches #"[a-zA-Z0-9_-]+" scenario)
                   (#{"morning" "afternoon" "evening" "night"} shift)
                   (integer? population) (pos? population)
                   (integer? index) (not (neg? index)))
      (throw (ex-info "Invalid report case identity" {:phase :report})))
    {:group (str scenario "/" shift) :fexpr tick-expression
     :arg population :version result-version :index index
     :name "tick!" :ns "georgetown.sim.tick"}))

(defn save-result!
  "Persist one completed normal case in the fastester-only ingestion tree.
  The runner writes raw data, manifests and status elsewhere. Refuse collisions."
  [run-dir case-index case stats]
  (let [metadata (case-metadata case case-index)
        results-dir (directory! (io/file (root run-dir) "results"))
        opts {:results-directory (slash results-dir)}
        existing (display/load-results (display/get-result-filenames opts))
        identity #(select-keys (:fastester/metadata %) [:group :fexpr :arg :version])
        result (assoc (sanitize stats) :fastester/metadata metadata)
        f (io/file results-dir (str "version " result-version)
                   (str "test-" case-index ".edn"))]
    (when-not (and (= 1 (:execution-count result))
                   (= (:sample-count result) (count (:samples result)))
                   (>= (:sample-count result 0) 10)
                   (sequential? (:mean result)) (sequential? (:variance result)))
      (throw (ex-info "Invalid completed Criterium result" {:phase :report})))
    (when (or (.exists f) (some #(= (identity result) (identity %)) existing))
      (throw (ex-info "Report result identity already exists" {:phase :report})))
    (directory! (.getParentFile f))
    (spit f (pr-str result))
    (.getPath f)))

(defn- trusted-options [run-root report-dir]
  {:title "Full simulation tick benchmarks"
   :copyright-holder "Georgetown contributors"
   :fastester-UUID (str (.getName run-root))
   :html-directory (slash report-dir) :html-filename "index.html"
   :markdown-directory (slash report-dir) :markdown-filename "report.md"
   :img-subdirectory "img/"
   :results-directory (slash (io/file run-root "results")) :results-url ""
   :comments {} :tidy-html? false
   :preamble [:div
              [:p "Custom timing: exactly one complete tick per sample, with fixture restoration outside timestamps. Criterium statistics; fastester reporting."]
              [:p "Raw samples are nanoseconds; derived times are seconds and variance is seconds squared. Charts show mean +/- standard deviation, not confidence intervals."]
              [:p "Deterministic starting inputs do not make random outcomes deterministic. Restoring fixtures influences caches and GC. These single-call measurements differ from Criterium's amortized batches; no overhead or reset cost is subtracted."]]})

(defn- static-document [text opts]
  (-> text
      ;; fastester does not supply these scripts. Keep all details visible instead.
      (str/replace #"(?is)<script\b[^>]*>.*?</script>" "")
      (str/replace #"(?is)<button\b[^>]*>.*?</button>" "")
      (str/replace #"(?is)<link\b[^>]*>" "")
      (str/replace (:results-directory opts) "../results/")
      (str/replace (str/replace (:html-directory opts) #"doc/" "") "")
      (str/replace #"(?i)display\s*:\s*none\s*;?" "display:block;")
      (str/replace "</head>" "<style>body{max-width:1000px;margin:2em auto;font-family:sans-serif}img{max-width:100%}table{border-collapse:collapse}td,th{padding:.4em;border:1px solid #ccc}.collapsable{display:block!important}</style></head>")))

(defn generate!
  "Generate or regenerate reports using saved completed results, without ticks.
  Always overwrite project-owned options before fastester evaluates them. Never
  accept a caller-supplied executable options file. Paths are independent of CWD."
  [run-dir]
  (let [run-root (root run-dir)
        report-dir (directory! (io/file run-root "reports"))
        opts (trusted-options run-root report-dir)
        result-files (vec (display/get-result-filenames opts))
        options-file (io/file report-dir "fastester-options.clj")]
    (when (empty? result-files)
      (throw (ex-info "No completed normal results to report" {:phase :report})))
    (directory! (io/file report-dir "img"))
    (spit options-file (pr-str opts))
    (display/generate-documents (.getPath options-file))
    (doseq [filename ["index.html" "report.md"]]
      (let [f (io/file report-dir filename)]
        (spit f (static-document (slurp f) opts))))
    (let [manifest {:format-version 1 :renderer "fastester 1"
                    :statistics "criterium 0.4.6" :presentation :static
                    :case-count (count result-files)
                    :html "reports/index.html" :markdown "reports/report.md"}]
      (spit (io/file run-root "report-manifest.edn") (pr-str manifest))
      manifest)))
