(ns frontend.datascript
  (:require [datascript :as d]))

(def ^:dynamic *debug-q* true)

(defn -q [q & args]
  (if *debug-q*
    (let [key (str q)
          _   (.time js/console key)
          res (apply d/q q args)
          _   (.timeEnd js/console key)]
      res)
    (apply d/q q args)))

(defn q1
  "Return first element of first tuple of result"
  [q & args]
  (->> (apply -q q args) ffirst))

(defn q1-by
  "Return single entity id by attribute existence or attribute value"
  ([db attr]
    (->> (-q '[:find ?e :in $ ?a :where [?e ?a]] db attr) ffirst))
  ([db attr value]
    (->> (-q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value) ffirst)))

(defn q1s
  "Return seq of first elements of each tuple"
  [q & args]
  (->> (apply -q q args) (map first)))

(defn qe
  "If queried entity id, return single entity of first result"
  [q db & sources]
  (->> (apply -q q db sources)
       ffirst
       (d/entity db)))

(defn qes
  "If queried entity ids, return all entities of result"
  [q db & sources]
  (->> (apply -q q db sources)
       (map #(d/entity db (first %)))))

(defn qe-by
  "Return single entity by attribute existence or specific value"
  ([db attr]
    (qe '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qe '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn qes-by
  "Return all entities by attribute existence or specific value"
  ([db attr]
    (qes '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qes '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn qmap
  "Convert returned 2-tuples to a map"
  [q & sources]
  (into {} (apply -q q sources)))

(defn touch+
  "By default, touch returns a map that can't be assoc'd. Fix it"
  [ent]
  ;; (into {}) makes the map assoc'able, but lacks a :db/id, which is annoying for later lookups.
  (into (select-keys ent [:db/id]) (d/touch ent)))

(defn touch-all
  "Runs the query that returns [[eid][eid]] and returns all entity maps.
   Uses the first DB to look up all entities"
  [query & query-args]
  (let [the-db (first query-args)]
    (for [[eid & _] (apply d/q query query-args)]
      (touch+ (d/entity the-db eid)))))

(defn make-initial-db []
  (let [schema {:aka {:db/cardinality :db.cardinality/many}}
        conn   (d/create-conn schema)]
    (d/transact! conn [{:db/id              -1
                        :layer/type         :rect
                        :layer/start-x      -10
                        :layer/start-y      -10
                        :layer/end-x        10
                        :layer/end-y        10
                        :layer/fill         "red"
                        :layer/stroke-width 2
                        :layer/stroke-color "blue"
                        :layer/name         "Radiohead"
                        :entity/type        :layer}])
    (d/transact! conn [{:layer/type         :rect
                        :layer/start-x      -10
                        :layer/start-y      -10
                        :layer/end-x        10
                        :layer/end-y        10
                        :layer/fill         "red"
                        :layer/stroke-width 2
                        :layer/stroke-color "blue"
                        :layer/name         "Lana del rey"
                        :layer/opacity      0.5
                        :entity/type        :layer}
                       {:layer/type         :rect
                        :layer/start-x      -10
                        :layer/start-y      -10
                        :layer/end-x        10
                        :layer/end-y        10
                        :layer/fill         "red"
                        :layer/stroke-width 2
                        :layer/stroke-color "blue"
                        :layer/name         "Jim morrison!"
                        :layer/opacity      1
                        :entity/type        :layer}])
    (print (d/q '[:find ?eid ?n ?sx ?sy ?ex ?ey
                  :where
                  [?eid :layer/type :rect]
                  [?eid :layer/name ?n]
                  [?eid :layer/start-x ?sx]
                  [?eid :layer/start-y ?sy]
                  [?eid :layer/end-x ?ex]
                  [?eid :layer/end-y ?ey]]
                @conn))
    (print (d/q '[:find ?eid ?a ?v
                  :where
                  [?eid :entity/type :layer]
                  [?eid ?a ?v]]
                @conn))
    (print (touch-all '[:find ?t :where [?t :layer/name]] @conn))))
