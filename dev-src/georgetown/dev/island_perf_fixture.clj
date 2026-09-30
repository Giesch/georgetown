(ns georgetown.dev.island-perf-fixture
  "Deterministic transaction data and explicit-database workload observations.
  Does not initialize, transact against, or select a database. Save the complete
  fixture result as EDN before ticking; replay :txs on a fresh owned database."
  (:require [georgetown.server.db :as db-api]
            [georgetown.sim.blueprints :as blueprints]
            [georgetown.sim.rules.allocation :as allocation]
            [georgetown.sim.tick :as tick])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def recipe-version 1)

(defn- stable-id [population kind index]
  (UUID/nameUUIDFromBytes
    (.getBytes (str "island-perf/" recipe-version "/" population "/" kind "/" index)
               StandardCharsets/UTF_8)))

(defn- ceil-div [n d] (quot (+ n (dec d)) d))

(defn fixture
  "Return {:txs :manifest :island-id} for exactly population citizens at epoch 0.
  Blueprint capacities: apartments 25, farms 2 (two shifts, 10 food times
  skill), markets 25 sales and 1 worker (three shifts). All buildings share
  one funded owner's food stock, as required by the simulation's stock flows.
  Rounded-up building counts and initial funding/stocks are in :manifest.
  Epoch 0 follows simulation initialization despite schema's PosInt annotation;
  the storage attribute is long. No generated citizens or command APIs are used."
  [population]
  (when-not (and (integer? population) (pos? population) (<= population 100000))
    (throw (ex-info "Population must be an integer from 1 through 100000"
                    {:population population})))
  (let [id #(stable-id population %1 %2)
        island-id (id :island 0)
        apartments (ceil-div population 25)
        farms (ceil-div population 4)
        markets (ceil-div population 20)
        buildings (vec (concat (repeat apartments :improvement.type/apartment)
                               (repeat farms :improvement.type/farm)
                               (repeat markets :improvement.type/food-market)))
        lots (mapv
               (fn [i type]
                 {:lot/id (id :lot i) :lot/x (inc i) :lot/y 1
                  :lot/elevation 0.5 :lot/moisture 0.5
                  :lot/deed {:deed/id (id :deed i) :deed/rate 1}
                  :lot/improvement
                  (cond-> {:improvement/id (id :improvement i)
                           :improvement/type type
                           :improvement/offers
                           (mapv (fn [j offerable]
                                   {:offer/id (id :offer (+ (* i 10) j))
                                    :offer/type (:offerable/id offerable)
                                    :offer/amount (case (:offerable/id offerable)
                                                    :offer/apartment.rental 1
                                                    :offer/food-market.offer 1
                                                    10)
                                    :offer/utilization 0.0})
                                 (range) (:blueprint/offerables (blueprints/blueprints type)))}
                    (= type :improvement.type/food-market)
                    (assoc :improvement/stocks
                           [{:stock/id (id :labour i) :stock/resource :resource/labour
                             :stock/amount 25.0}]))})
               (range) buildings)
        citizens (mapv
                   (fn [i]
                     (merge {:citizen/id (id :citizen i)
                             :citizen/name (str "Benchmark Citizen " (inc i))
                             :citizen/savings 100.0 :citizen/age-ticks 40000
                             :citizen/residency-ticks 0
                             :citizen/physical-stress 0.1 :citizen/mental-stress 0.1}
                            (zipmap [:citizen/preference.security
                                     :citizen/preference.self-improvement
                                     :citizen/preference.physical-stress
                                     :citizen/preference.mental-stress
                                     :citizen/preference.spiritual-activity
                                     :citizen/preference.social-activity
                                     :citizen/preference.physical-activity
                                     :citizen/preference.intellectual-activity
                                     :citizen/talent.intellect :citizen/talent.fitness
                                     :citizen/talent.social
                                     :citizen/skill.intellect :citizen/skill.fitness
                                     :citizen/skill.social]
                                    (repeat 0.75))))
                   (range population))
        manifest {:recipe-version recipe-version :population population :initial-epoch 0
                  :rounding :ceiling-per-building
                  :buildings {:apartments apartments :farms farms :markets markets}
                  :capacity {:housing (* 25 apartments) :farm-jobs-per-shift (* 2 farms)
                             :market-jobs-per-shift markets :food-sales-per-shift (* 25 markets)}
                  :rates {:rent 1 :wage 10 :food-price 1}
                  :initial {:citizen-savings (* 100.0 population)
                            :owner-balance (* 100000 population)
                            :government-balance (* 1000 population)
                            :food (* 8.0 population) :market-labour (* 25.0 markets)}
                  :identity (str "island-perf-v" recipe-version "-" population)}]
    {:island-id island-id :manifest manifest
     :txs [{:island/id island-id :island/seed 1 :island/epoch 0
            :island/joy population :island/government-money-balance (* 1000 population)
            :island/citizens citizens :island/lots lots
            :island/players
            [{:player/id (id :player 0) :player/money-balance (* 100000 population)
              :player/deeds (mapv #(select-keys (:lot/deed %) [:deed/id]) lots)
              :player/stocks [{:stock/id (id :food 0) :stock/resource :resource/food
                               :stock/amount (* 8.0 population)}]}]}]}))

(defn production-witness
  "Conservative gross food output from surviving recorded farm assignments.
  Uses PRE-tick skills, exactly as apply-assignment-effects does. This is a
  lower bound if an assigned citizen died/emigrated before stats were written.
  Returns unavailable without a matching pre-tick epoch, never guesses from
  employment counts or net stock growth."
  [pre-world stats]
  (if (and pre-world (= (:world/epoch pre-world) (:sim.out/epoch stats)))
    (let [assignments
          (keep (fn [[citizen-id state]]
                  (let [type (:citizen-state/last-activity state)
                        citizen (get-in pre-world [:world/citizens citizen-id])
                        offerable (blueprints/offerables type)]
                    (when (and citizen (contains? #{:offer/farm.job :offer/big-farm.job} type))
                      {:citizen-id citizen-id :offer-type type
                       :productivity (allocation/productivity
                                       citizen (:offerable/skill-productivity-weights offerable))
                       :gross-food (* (if (= type :offer/farm.job) 10.0 20.0)
                                      (allocation/productivity
                                        citizen (:offerable/skill-productivity-weights offerable)))})))
                (:sim.out/citizen-states stats))]
      {:status :available :method :pre-tick-skill-weighted-surviving-assignments
       :gross-food (reduce + 0.0 (map :gross-food assignments))
       :assignments (vec assignments)})
    {:status :unavailable :reason :missing-or-mismatched-pre-tick-world}))

(defn activity-witness
  "Compute gross output and paid wages before an assignment's effects run.
  A child-local wrapper adds these values for each real assignment, then calls
  the original apply-assignment-effects unchanged. Includes removed citizens."
  [world citizen-id offer]
  (let [definition (blueprints/offerables (:offer/type offer))
        factor (allocation/productivity (get-in world [:world/citizens citizen-id])
                                        (:offerable/skill-productivity-weights definition))]
    (reduce (fn [totals [direction target _ :as effect]]
              (let [amount (blueprints/resolve-effect-amount offer effect)]
                (cond
                  (and (= target :resource/food)
                       (contains? #{:effect.direction/to-player :effect.direction/to-self} direction)
                       (pos? amount))
                  (update totals :gross-food-production + (* amount factor))
                  (and (= direction :effect.direction/from-player)
                       (= target :resource/money) (pos? amount))
                  (update totals :paid-wages + amount)
                  :else totals)))
            {:gross-food-production 0.0 :paid-wages 0.0}
            (:offerable/effects definition))))

(defn observe
  "Observe only the supplied database/island. Third argument is the tick's
  accumulated activity-witness map; alternatively a pre-tick extracted world
  provides a conservative production lower bound. Attach :phase in the caller.
  Observation work is outside tick timing. :epoch/:shift come from completed
  public stats; :island-epoch is the incremented stored epoch. Initial snapshots
  have no completed public stats and must not enter cycle validation."
  ([db island-id] (observe db island-id nil))
  ([db island-id pre-world]
   (let [world (tick/extract-world db island-id)
         stats (:world/previous-public-stats world)
         offers (db-api/q db
                  '[:find [(pull ?offer [:offer/id :offer/type :offer/amount :offer/utilization]) ...]
                    :in $ ?id
                    :where [?island :island/id ?id] [?island :island/lots ?lot]
                    [?lot :lot/improvement ?improvement] [?improvement :improvement/offers ?offer]]
                  island-id)
         players (:world/players world)
         improvements (:world/improvements world)]
     {:island-id island-id :island-epoch (:world/epoch world)
      :epoch (or (:sim.out/epoch stats) (:world/epoch world))
      :shift (or (:sim.out/shift stats) (:world/shift world)) :world world
      :public-stats stats :population (count (:world/citizens world))
      :citizen-savings (reduce + 0.0 (map :citizen/savings (vals (:world/citizens world))))
      :player-balances (into {} (map (fn [[id p]] [id (:player/money-balance p)]) players))
      :government-balance (:world/government-money-balance world)
      :player-stocks (into {} (map (fn [[id p]] [id (:player/stocks p)]) players))
      :improvement-stocks (into {} (map (fn [[id p]] [id (:improvement/stocks p)]) improvements))
      :holders {:players (set (keys players)) :improvements (set (keys improvements))}
      :offers (vec offers)
      :activity-witness (when (contains? pre-world :gross-food-production) pre-world)
      :production (if (contains? pre-world :gross-food-production)
                    {:status :available :method :assignment-effects-instrumentation
                     :gross-food (:gross-food-production pre-world)}
                    (production-witness pre-world stats))})))

(def shifts [:time-shift/morning :time-shift/afternoon :time-shift/evening :time-shift/night])

(defn cycle-viability
  "Select the latest contiguous full morning-to-night sequence in recorded
  order, wholly in phase when supplied. Initial/boundary snapshots must not be
  included as tick records. No complete cycle is :unverified, not :valid.
  Housing counts only the night snapshot; food sales and positive paid wages
  must be observed, and gross production must have a positive skill witness."
  ([observations] (cycle-viability observations nil))
  ([observations phase]
   (let [records (vec (if phase (filter #(= phase (:phase %)) observations) observations))
         cycle (last
                 (filter (fn [window]
                           (let [stats (map :public-stats window)
                                 epochs (map :sim.out/epoch stats)]
                             (and (= shifts (mapv :sim.out/shift stats))
                                  (every? number? epochs)
                                  (= (vec epochs) (vec (range (first epochs) (+ 4 (first epochs))))))))
                         (partition 4 1 records)))]
     (if-not cycle
       {:status :unverified :reason :no-complete-four-shift-cycle :phase phase}
       (let [paid-wages
             (reduce + 0.0
                     (for [record cycle offer (:offers record)
                           :let [definition (blueprints/offerables (:offer/type offer))
                                 wage (blueprints/effect-sum offer :effect.direction/from-player :resource/money)]
                           :when (and (pos? wage)
                                      (contains? (:offerable/time-shifts definition)
                                                 (get-in record [:public-stats :sim.out/shift]))) ]
                       (* wage (or (:offer/utilization offer) 0.0)
                          (or (:offerable/capacity definition) 1)) ))
             activity {:housing-occupancy (or (get-in (last cycle) [:public-stats :sim.out/resources :resource/shelter :supply]) 0)
                       :paid-wages (if (every? #(number? (get-in % [:activity-witness :paid-wages])) cycle)
                                     (reduce + 0.0 (map #(get-in % [:activity-witness :paid-wages]) cycle))
                                     paid-wages)
                       :food-sales (reduce + 0.0 (map #(or (get-in % [:public-stats :sim.out/resources :resource/food :supply]) 0) cycle))
                       :gross-food-production (reduce + 0.0 (map #(or (get-in % [:production :gross-food]) 0) cycle))}
             missing (vec (keep (fn [[k v]] (when-not (pos? v) k)) activity))]
         {:status (if (empty? missing) :valid :invalid) :phase phase
          :epoch-range (mapv #(get-in % [:public-stats :sim.out/epoch]) [(first cycle) (last cycle)])
          :activity activity :missing-activity missing})))))

(defn viability
  "Worker-facing cycle assessment; invalid activity is :fixture-invalid."
  ([observations] (viability observations nil))
  ([observations phase]
   (let [result (cycle-viability observations phase)]
     (cond-> result
       (= :invalid (:status result)) (assoc :status :fixture-invalid)))))
