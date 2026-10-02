(ns georgetown.dev.tick-bench-fixture
  "Deterministic, development-only full-tick inputs. Never opens configured data."
  (:require [dat.api :as dat]
            [georgetown.sim.blueprints :as blueprints]
            [georgetown.sim.schema :as schema])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util UUID]))

(def recipe-version 1)
(def populations [50 500])
(def shifts [:morning :afternoon :evening :night])

(defn- id [& parts]
  (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:tick-bench recipe-version] parts)) StandardCharsets/UTF_8)))

(defn- stock [holder resource amount]
  {:stock/id (id holder resource) :stock/resource resource :stock/amount (double amount)})

(defn- improvement [owner index type offers]
  (let [iid (id :improvement owner index)]
    (cond-> {:improvement/id iid :improvement/type type
             :improvement/offers
             (mapv (fn [[type amount]]
                     {:offer/id (id iid type) :offer/type type
                      :offer/amount amount :offer/utilization 0.0}) offers)}
      (= type :improvement.type/food-market)
      (assoc :improvement/stocks [(stock iid :resource/labour 25)]))))

(defn- ceil-div [n d] (quot (+ n (dec d)) d))

(defn- buildings [share]
  (vec (concat
         (repeat (ceil-div share 25)
                 [:improvement.type/apartment [[:offer/apartment.rental 1]]])
         (repeat (ceil-div share 4)
                 [:improvement.type/farm [[:offer/farm.job 10]]])
         (repeat (ceil-div share 20)
                 [:improvement.type/food-market
                  [[:offer/food-market.job 10] [:offer/food-market.offer 1]]])
         [[:improvement.type/park [[:offer/park.leisure 0]]]])))

(defn- citizen [index]
  (merge {:citizen/id (id :citizen index)
          :citizen/name (format "Citizen %04d" index)
          :citizen/savings 100.0 :citizen/age-ticks 29200
          :citizen/residency-ticks 100}
         (into {} (for [[attr spec] (:entity/citizen schema/schema)
                        :when (= :db.type/float (:dat/type spec))
                        :when (not= attr :citizen/savings)]
                    [attr 0.75]))))

(def attribute-specs (apply merge (vals schema/schema)))
(def identity-attrs
  (set (for [[attr spec] attribute-specs :when (:dat/unique spec)] attr)))
(def ref-attrs
  (set (for [[attr spec] attribute-specs :when (:dat/rel spec)] attr)))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                      (map (fn [[k v]] [k (canonical v)])) value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- digest [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(defn- entity-key [entity]
  (or (some (fn [attr] (when-let [v (get entity attr)] [attr v]))
            (sort-by str identity-attrs))
      (throw (ex-info "Fixture entity has no logical identity" {:entity entity}))))

(defn- scalar [attr value]
  ;; Datalevin's float attributes round to IEEE single precision.
  (if (= :db.type/float (:dat/type (get attribute-specs attr)))
    (double (float value)) (canonical value)))

(defn- logical-tx [tx]
  (letfn [(entity-datoms [entity]
            (let [key (entity-key entity)]
              (mapcat
                (fn [[attr value]]
                  (if (contains? ref-attrs attr)
                    (let [[cardinality] (:dat/rel (get attribute-specs attr))
                          values (if (= cardinality :dat.rel/many) value [value])]
                      (mapcat (fn [child]
                                (if (map? child)
                                  (cons [key attr (entity-key child)] (entity-datoms child))
                                  [[key attr child]])) values))
                    [[key attr (scalar attr value)]])) entity)))]
    (vec (sort-by pr-str (mapcat entity-datoms tx)))))

(defn validate-fixture!
  "Validate baseline recipe identities, relationships, capacities and funding. Returns fixture."
  [{:keys [population shift epoch tx manifest] :as fixture}]
  (let [island (first tx)
        players (:island/players island)
        lots (:island/lots island)
        citizens (:island/citizens island)
        deeds (mapcat :player/deeds players)
        improvements (keep :lot/improvement lots)
        offers (mapcat :improvement/offers improvements)
        capacity (fn [type] (* (count (filter #(= type (:offer/type %)) offers))
                              (:offerable/capacity (get blueprints/offerables type))))
        fail (fn [ok reason] (when-not ok (throw (ex-info reason {:population population :shift shift}))))
        datoms (logical-tx tx)
        entities (set (map first datoms))]
    (fail (and (some #{population} populations) (some #{shift} shifts)
               (= epoch (.indexOf shifts shift)) (= epoch (:island/epoch island)))
          "Unsupported fixture population or shift")
    (fail (= tx (:tx-data fixture)) "Fixture transaction aliases differ")
    (fail (= (:fingerprint fixture) (digest datoms)) "Fixture transaction fingerprint differs")
    (fail (= 2 (count players)) "Fixture requires exactly two owners")
    (fail (= population (count citizens)) "Incorrect fixture population")
    (fail (= 400 (count lots)) "Fixture requires 400 terrain lots")
    (fail (= (count deeds) (count improvements)) "Improvement ownership is incomplete")
    (fail (= (set (map #(vector :deed/id (:deed/id %)) deeds))
             (set (keep :lot/deed lots))) "Lot and owner deeds differ")
    (fail (= (count datoms) (count (set datoms))) "Duplicate fixture datoms")
    (doseq [[_ attr value] datoms :when (contains? ref-attrs attr)]
      (fail (contains? entities value) "Dangling fixture reference"))
    (fail (>= (capacity :offer/apartment.rental) population) "Insufficient housing")
    (fail (>= (capacity :offer/food-market.offer) population) "Insufficient food-sale capacity")
    (doseq [[player owner] (map vector players (:owners manifest))]
      (let [share (:citizens owner)
            owner-deeds (set (map :deed/id (:player/deeds player)))
            owned (filter #(contains? owner-deeds (second (:lot/deed %))) lots)
            owned-offers (mapcat #(get-in % [:lot/improvement :improvement/offers]) owned)
            cap (fn [type] (* (count (filter #(= type (:offer/type %)) owned-offers))
                             (:offerable/capacity (get blueprints/offerables type))))
            wage-budget (* 10 (+ (cap :offer/farm.job) (cap :offer/food-market.job)))
            charges (reduce + (map :deed/rate (:player/deeds player)))
            food (reduce + (map :stock/amount (filter #(= :resource/food (:stock/resource %))
                                                     (:player/stocks player))))
            labour (reduce + (map :stock/amount
                                 (mapcat #(get-in % [:lot/improvement :improvement/stocks]) owned)))]
        (fail (and (pos? (cap :offer/farm.job)) (pos? (cap :offer/food-market.job))
                   (>= (cap :offer/apartment.rental) share)
                   (>= (cap :offer/food-market.offer) share)) "Owner economy lacks capacity")
        (fail (>= food share) "Owner food inventory is insufficient")
        (fail (>= labour (* 0.05 share)) "Market labour inventory is insufficient")
        (fail (>= (:player/money-balance player) (+ wage-budget charges)) "Owner funding is insufficient")))
    (fail (every? #(>= (:citizen/savings %) 2) citizens) "Citizens cannot afford rent and food")
    fixture))

(defn fixture
  "Complete logical transaction and manifest for baseline population × shift."
  [population shift]
  (when-not (and (some #{population} populations) (some #{shift} shifts))
    (throw (ex-info "Unknown baseline population or shift" {:population population :shift shift})))
  (let [epoch (.indexOf shifts shift)
        share (quot population 2)
        recipe (buildings share)
        count-per-owner (count recipe)
        cs (mapv citizen (range population))
        players (mapv
                  (fn [owner]
                    {:player/id (id :player owner)
                     :player/money-balance (* 100000 population)
                     :player/private-stats {:stats.private/net-cashflow 0.0 :stats.private/stabilization-payment 0.0}
                     :player/stocks [(stock (id :player owner) :resource/food (* 8 population))]
                     :player/deeds (mapv (fn [index]
                                           {:deed/id (id :deed owner index)
                                            :deed/rate 1 :deed/rate-changed-at 0})
                                         (range count-per-owner))}) [0 1])
        lots (mapv
               (fn [index]
                 (let [owner (quot index count-per-owner)
                       local (mod index count-per-owner)]
                   (cond-> {:lot/id (id :lot index) :lot/x (mod index 20) :lot/y (quot index 20)
                            :lot/elevation 0.75 :lot/moisture 0.75}
                     (< owner 2)
                     (assoc :lot/deed [:deed/id (id :deed owner local)]
                            :lot/improvement (apply improvement owner local (nth recipe local))))))
               (range 400))
        previous {:sim.out/epoch (double (dec epoch))
                  :sim.out/shift :time-shift/night :sim.out/population (double population)
                  :sim.out/citizen-states
                  (into {} (map (fn [c] [(:citizen/id c) {:citizen-state/hungry? false
                                                       :citizen-state/unhoused? false
                                                       :citizen-state/savings 100.0}]) cs))
                  :sim.out/resources {:resource/shelter {:served-count (double population)
                                                        :unserved-count 0.0 :clearing-price 1.0}}}
        island-id (id :island)
        tx [{:island/id island-id :island/seed 1 :island/epoch epoch
             :island/government-money-balance (* 1000 population)
             :island/joy (long (* 0.25 population)) :island/public-stats previous
             :island/players players :island/citizens cs :island/lots lots}]
        manifest {:recipe-version recipe-version :scenario :baseline :population population
                  :shift shift :epoch epoch :terrain {:width 20 :height 20 :elevation 0.75 :moisture 0.75}
                  :rates {:rent 1 :food 1 :farm-wage 10 :market-wage 10 :deed 1 :park 0}
                  :citizen-savings 100 :government-cash (* 1000 population)
                  :owners (mapv (fn [owner]
                                  {:player-id (id :player owner) :citizens share
                                   :apartments (ceil-div share 25) :farms (ceil-div share 4)
                                   :markets (ceil-div share 20) :parks 1
                                   :housing-capacity (* 25 (ceil-div share 25))
                                   :farm-job-capacity (* 2 (ceil-div share 4))
                                   :market-job-capacity (ceil-div share 20)
                                   :food-sale-capacity (* 25 (ceil-div share 20))
                                   :market-labour (* 25 (ceil-div share 20))
                                   :food (* 8 population) :cash (* 100000 population)}) [0 1])}]
    (validate-fixture! {:scenario :baseline :population population :shift shift :epoch epoch
                        :island-id island-id :tx tx :tx-data tx :manifest manifest
                        :fingerprint (digest (logical-tx tx))})))

(defn- owned! [db]
  (when-not (::owned (meta db))
    (throw (ex-info "Not a fixture-owned database handle" {})))
  (when @(::closed (meta db))
    (throw (ex-info "Fixture database is closed" {})))
  db)

(defn open-db!
  "Create an isolated Datalevin database at a NEW explicitly supplied path.
  Existing paths are rejected atomically. Close does not delete retained database files."
  [path]
  (when-not (or (string? path) (instance? Path path) (instance? java.io.File path))
    (throw (ex-info "An explicit database path is required" {})))
  (let [p (.toAbsolutePath (.normalize (.toPath (clojure.java.io/file (str path)))))
        attrs (make-array FileAttribute 0)]
    (Files/createDirectories (.getParent p) attrs)
    (Files/createDirectory p attrs)
    (let [db (dat/init! :dat.db/datalevin schema/schema {:dir (str p)})]
      (alter-meta! db assoc ::owned (str p) ::closed (atom false))
      db)))

(defn close! [db]
  (when-not (::owned (meta db))
    (throw (ex-info "Not a fixture-owned database handle" {})))
  (when-not @(::closed (meta db))
    (dat/close! db)
    (reset! (::closed (meta db)) true))
  nil)

(defn fingerprint
  "Hash all stored logical datoms, including events, stocks and stats, independent of IDs/order.
  The fixture argument identifies the selected input; unknown/orphan entities are included."
  [db _fixture]
  (owned! db)
  (let [datoms (dat/q '[:find ?e ?a ?v :where [?e ?a ?v]] @db)
        identities (reduce (fn [m [e attr value]]
                             (if (contains? identity-attrs attr)
                               (assoc m e [attr value]) m)) {} datoms)
        logical-id (fn [e] (or (get identities e) [:unidentified e]))]
    (digest (vec (sort-by pr-str
                         (map (fn [[e attr value]]
                                [(logical-id e) attr
                                 (if (contains? ref-attrs attr)
                                   (logical-id value) (scalar attr value))]) datoms))))))

(defn validate-db!
  "Check the complete restored input, not just counts. Returns fixture."
  [db fixture]
  (let [actual (fingerprint db fixture)]
    (when-not (= (:fingerprint fixture) actual)
      (throw (ex-info "Restored fixture fingerprint differs" {:expected (:fingerprint fixture) :actual actual}))))
  fixture)

(def validate-restored! validate-db!)

(defn epoch [db fixture]
  (owned! db)
  (dat/q '[:find ?epoch . :in $ ?id :where [?e :island/id ?id] [?e :island/epoch ?epoch]]
         @db (:island-id fixture)))

(defn restore!
  "Retract EVERY entity in this owned database and restore complete fixture input.
  Keeps the wrapper and underlying connection alive. Checks are outside benchmark timestamps."
  [db fixture]
  (owned! db)
  (validate-fixture! fixture)
  (let [entities (dat/q '[:find [?e ...] :where [?e _ _]] @db)]
    (when (seq entities)
      (dat/transact! db (mapv #(vector :db/retractEntity %) entities))))
  ;; Datalevin resolves lookup refs before nested entities in the same tx exist.
  (dat/transact! db (vec (mapcat :player/deeds (:island/players (first (:tx fixture))))))
  (dat/transact! db (:tx-data fixture))
  (validate-db! db fixture))
