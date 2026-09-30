(ns georgetown.dev.island-perf-fixture-test
  (:require [clojure.test :refer [deftest is testing]]
            [georgetown.dev.island-perf-fixture :as fixture]))

(deftest scaled-fixture-test
  (doseq [n [1 50 51 500]]
    (let [{:keys [txs manifest] :as f} (fixture/fixture n)
          island (first txs)]
      (is (= f (fixture/fixture n)))
      (is (= n (count (:island/citizens island))))
      (is (= 0 (:island/epoch island)))
      (is (<= n (get-in manifest [:capacity :housing])))
      (is (<= n (get-in manifest [:capacity :food-sales-per-shift])))
      (is (= (count (:island/lots island))
             (count (:player/deeds (first (:island/players island))))))))
  (is (thrown? clojure.lang.ExceptionInfo (fixture/fixture 0))))

(deftest assignment-witness-test
  (let [world {:world/citizens {:citizen {:citizen/skill.intellect 0.5
                                         :citizen/skill.fitness 0.5
                                         :citizen/skill.social 0.5}}}]
    (is (= {:gross-food-production 5.0 :paid-wages 10.0}
           (fixture/activity-witness world :citizen
                                     {:offer/type :offer/farm.job :offer/amount 10})))
    (is (= {:gross-food-production 0.0 :paid-wages 0.0}
           (fixture/activity-witness world :citizen
                                     {:offer/type :offer/apartment.rental :offer/amount 1})))))

(defn- cycle-records [gross]
  (mapv (fn [epoch shift]
          {:phase :measured
           :public-stats {:sim.out/epoch (double epoch) :sim.out/shift shift
                          :sim.out/resources {:resource/food {:supply 5.0}
                                              :resource/shelter {:supply 10.0}}}
           ;; Net stocks decline; this does not negate witnessed production.
           :player-stocks {:owner {:resource/food {:stock/amount (- 100 epoch)}}}
           :activity-witness {:paid-wages 10.0}
           :production {:status :available :gross-food gross}})
        (range 4) fixture/shifts))

(deftest viability-cycle-test
  (let [records (cycle-records 2.0)]
    (is (= :valid (:status (fixture/cycle-viability records :measured))))
    (is (= [0.0 3.0] (:epoch-range (fixture/cycle-viability
                                    (conj records {:phase :measured
                                                   :public-stats {:sim.out/epoch 4.0
                                                                  :sim.out/shift :time-shift/morning}})
                                    :measured))))
    (is (= :unverified (:status (fixture/cycle-viability (subvec records 0 3)))))
    (is (= :unverified (:status (fixture/cycle-viability records :warm-up))))
    (is (= :unverified (:status (fixture/cycle-viability
                                  (assoc-in records [2 :public-stats :sim.out/epoch] 6.0)))))
    (testing "sales from initial stocks do not prove production"
      (let [result (fixture/cycle-viability (cycle-records 0.0))]
        (is (= :invalid (:status result)))
        (is (= [:gross-food-production] (:missing-activity result)))))
    (testing "daytime shelter snapshots alone do not prove night occupancy"
      (is (= :invalid
             (:status (fixture/cycle-viability
                        (assoc-in records [3 :public-stats :sim.out/resources :resource/shelter :supply] 0))))))))
