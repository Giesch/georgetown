(ns georgetown.dev.tick-bench-fixture-test
  (:require [clojure.test :refer [deftest is testing]]
            [dat.api :as dat]
            [georgetown.dev.tick-bench-fixture :as fixture]
            [hyperfiddle.rcf :as rcf])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]))

(defn- tick! [db island-id]
  (rcf/enable! false)
  ((requiring-resolve 'georgetown.sim.tick/tick!) db island-id))

(defn- with-db [f]
  (let [root (Files/createTempDirectory "georgetown-fixture-test-" (make-array FileAttribute 0))
        path (.resolve root "db")
        db (fixture/open-db! path)]
    (try (f db path)
         (finally
           (fixture/close! db)
           ;; Only the freshly generated test directory is removed.
           (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
             (doseq [p (reverse (sort-by #(.getNameCount %) (iterator-seq (.iterator paths))))]
               (Files/deleteIfExists p)))))))

(deftest fixture-capacity-test
  (doseq [population fixture/populations shift fixture/shifts]
    (let [f (fixture/fixture population shift)]
      (is (= f (fixture/fixture population shift)))
      (is (= f (fixture/validate-fixture! f)))
      (is (= (:epoch f) (.indexOf fixture/shifts shift)))
      (is (= (:tx f) (:tx-data f)))
      (doseq [owner (get-in f [:manifest :owners])]
        (is (>= (:housing-capacity owner) (:citizens owner)))
        (is (>= (:food-sale-capacity owner) (:citizens owner)))
        (is (>= (:food owner) (:citizens owner)))
        (is (>= (:market-labour owner) (* 0.05 (:citizens owner)))))))
  (is (thrown? clojure.lang.ExceptionInfo (fixture/fixture 51 :morning)))
  (is (thrown? clojure.lang.ExceptionInfo (fixture/fixture 50 :dawn))))

(deftest isolated-db-lifecycle-test
  (with-db
    (fn [db path]
      (is (thrown? java.nio.file.FileAlreadyExistsException (fixture/open-db! path)))
      (is (thrown? clojure.lang.ExceptionInfo (fixture/restore! (atom {}) (fixture/fixture 50 :morning))))
      (fixture/restore! db (fixture/fixture 50 :morning))
      (fixture/close! db)
      (fixture/close! db)
      (is (thrown? clojure.lang.ExceptionInfo (fixture/epoch db (fixture/fixture 50 :morning)))))))

(deftest fixture-matrix-restore-test
  (with-db
    (fn [db _]
      (doseq [population fixture/populations shift fixture/shifts]
        (testing (str population "/" shift)
          (let [f (fixture/fixture population shift)
                conn (dat/conn db)]
            (fixture/restore! db f)
            (is (= (:fingerprint f) (fixture/fingerprint db f)))
            (is (= 2 (dat/q '[:find (count ?p) . :where [?p :player/id _]] @db)))
            (is (= population (dat/q '[:find (count ?c) . :where [?c :citizen/id _]] @db)))
            (fixture/restore! db f)
            (is (= (:fingerprint f) (fixture/fingerprint db f)))
            (tick! db (:island-id f))
            (is (= (inc (:epoch f)) (fixture/epoch db f)))
            (is (not= (:fingerprint f) (fixture/fingerprint db f)))
            ;; Inject changes that can arise from prior tick calls, including orphan entities.
            (dat/transact! db [{:event/id (UUID/randomUUID) :event/epoch 20
                               :event/type :event/test :event/source :source/simulation
                               :event/visibility :visibility/public :event/render []}
                              {:citizen/id (UUID/randomUUID) :citizen/name "Unexpected birth"}
                              {:stock/id (UUID/randomUUID) :stock/resource :resource/food :stock/amount 3.0}
                              [:db/retractEntity [:player/id (get-in f [:manifest :owners 0 :player-id])]]])
            (fixture/restore! db f)
            (is (identical? conn (dat/conn db)))
            (is (= f (fixture/validate-restored! db f)))
            (is (empty? (dat/q '[:find [?e ...] :where [?e :event/id _]] @db)))))))))

(defn- utilized [db type]
  (dat/q '[:find ?utilization ?amount ?o :in $ ?type
           :where [?o :offer/type ?type] [?o :offer/utilization ?utilization]
                  [?o :offer/amount ?amount]] @db type))

(deftest controlled-economic-activity-test
  (with-db
    (fn [db _]
      (doseq [population fixture/populations shift fixture/shifts]
        (testing (str population "/" shift)
          (let [f (fixture/fixture population shift)]
            (fixture/restore! db f)
            ;; Prevent demographic additions/removals only in correctness tests.
            (with-redefs [clojure.core/rand (fn ([] 0.99) ([n] (* 0.99 n)))]
              (tick! db (:island-id f)))
            (is (= (inc (:epoch f)) (fixture/epoch db f)))
            (if (= shift :night)
              (let [rentals (utilized db :offer/apartment.rental)]
                (is (every? #(pos? (first %)) rentals))
                (is (= (double population) (reduce + (map #(* 25 (first %)) rentals)))))
              (let [sales (utilized db :offer/food-market.offer)]
                (is (every? #(pos? (first %)) sales))
                (is (< (Math/abs (- (double population) (reduce + (map #(* 25 (first %)) sales)))) 0.001))))
            (when (#{:morning :afternoon} shift)
              (let [farm-jobs (utilized db :offer/farm.job)
                    market-jobs (utilized db :offer/food-market.job)]
                (is (pos? (reduce + (map first farm-jobs))))
                (is (pos? (reduce + (map first market-jobs))))
                (is (every? #(= 10 (second %)) (concat farm-jobs market-jobs)))
                (doseq [owner (get-in f [:manifest :owners])]
                  (let [food (dat/q '[:find ?food . :in $ ?id
                                     :where [?p :player/id ?id] [?p :player/stocks ?s]
                                            [?s :stock/resource :resource/food] [?s :stock/amount ?food]]
                                   @db (:player-id owner))]
                    ;; Food produced exceeds sales for each funded owner.
                    (is (> food (:food owner)))))))))))))
