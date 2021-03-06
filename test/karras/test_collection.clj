(ns karras.test-collection
  (:use karras.core
        karras.collection :reload-all
        karras.sugar
        clojure.test
        [com.reasonr.scriptjure :only [js]]
        midje.semi-sweet))

(defonce indexing-tests-db (mongo-db :integration-tests))
(defonce people (collection indexing-tests-db :people))

(def sample-people [{:first-name "Bill"  :last-name "Smith"   :age 21}
                    {:first-name "Sally" :last-name "Jones"   :age 18}
                    {:first-name "Jim"   :last-name "Johnson" :age 16}
                    {:first-name "Jane"  :last-name "Johnson" :age 16}])

(doseq [p sample-people]
  (eval `(declare ~(symbol (:first-name p)))))

(defn person-by-name [n]
  (fetch-one people {:first-name n}))

(use-fixtures :each
              (fn [t]
                (drop-collection people)
                (apply insert people sample-people)
                (binding [Bill (person-by-name "Bill")
                          Sally (person-by-name "Sally")
                          Jim (person-by-name "Jim")
                          Jane (person-by-name "Jane")]
                  (write-concern-strict (collection-db people))
                  (in-request (collection-db people) (t)))))

(deftest fetching-tests
  (testing "count-docs"
    (expect (count-docs people) => 4))
  (testing "fetching"
    (expect (count (fetch-all people)) => 4)
    (expect (count (fetch people (where (gte :age 18)))) => 2)
    (expect (fetch-one people (where (eq :first-name "Bill"))) => Bill)
    (expect (fetch-by-id people (-> Bill :_id)) => Bill)
    (expect (fetch-by-id people (-> Bill :_id str)) => Bill))
  (testing "distinct-values"
    (expect (distinct-values people :age) => #{21 18 16})))

(deftest grouping-tests
  (testing "group by a key"
    (expect (group people [:age]) => (in-any-order [{:age 21.0 :values [Bill]}
                                                    {:age 18.0 :values [Sally]}
                                                    {:age 16.0 :values [Jim Jane]}])))
  (testing "group by multiple keys"
    (expect (group people [:age :last-name]) => (in-any-order [{:age 21.0 :last-name "Smith"   :values [Bill]}
                                                               {:age 18.0 :last-name "Jones"   :values [Sally]}
                                                               {:age 16.0 :last-name "Johnson" :values [Jim Jane]}]))))
  (testing "group and count"
    (expect
     (group people
            [:last-name]
            nil
            {:count 0}
            "function (o,out) { out.count++ }")
     => (in-any-order [{:last-name "Johnson" :count 2.0 }
                       {:last-name "Smith" :count 1.0 }
                       {:last-name "Jones" :count 1.0 }])))
  (testing "group and finalize"
    (expect 
     (group people
            [:last-name]
            nil
            {:age_sum 0 :count 0}
            "function (o,out) { out.count++; out.age_sum += o.age; }"
            "function (out) {out.avg_age = out.age_sum / out.count}")
     => (in-any-order [{:last-name "Johnson" :age_sum 32.0 :count 2.0 :avg_age 16.0}
                       {:last-name "Smith" :age_sum 21.0 :count 1.0 :avg_age 21.0}
                       {:last-name "Jones" :age_sum 18.0 :count 1.0 :avg_age 18.0}])))

(deftest deleting-tests
  (testing "delete by document"
    (delete people Jim Jane)
    (expect (fetch-all people) => (in-any-order [Bill Sally])))
  (testing "delete by where clause"
    (delete people (where (gte :age 17)))
    (expect (fetch-all people) => empty?)))

(deftest saving-tests
  (save people (merge Jim {:weight 180}))
  (expect (count-docs people) => 4)
  (expect (:weight (fetch-one people {:first-name "Jim"})) => 180)
  (testing "meta-data is preserved"
    (let [doc-with-meta (with-meta Jim {:some :data})
          saved-doc (save people doc-with-meta)]
      (expect (meta saved-doc) => (meta doc-with-meta)))))

(deftest updating-tests
  (update people {:first-name "Jim"} (merge Jim {:weight 180}))
  (expect (count-docs people) => 4)
  (expect (:weight (fetch-one people {:first-name "Jim"})) => 180))

(deftest update-all-tests
  (expect (count-docs people) => 4)
  (update-all people {:last-name "Johnson"} (modify (set-fields {:age 17})))
  (expect (count-docs people) => 4)
  (let [johnsons (fetch people {:last-name "Johnson"})]
    (expect (count johnsons) => 2)
    (doseq [j johnsons]
      (expect (:age j) => 17))))

(deftest find-and-modify-tests
  (testing "return unmodified document"
    (expect (find-and-modify people
                             (where (eq :age 18))
                             (modify (set-fields {:voter true})))
            => Sally))
  (testing "return modified document"
    (expect (find-and-modify people
                             (where (eq :age 18))
                             (modify (set-fields {:voter false}))
                             :return-new true)
            => (merge Sally {:voter false})))
  (testing "sorting"
    (expect (find-and-modify people
                             (where (eq :age 16))
                             (modify (set-fields {:driver true}))
                             :sort [(asc :last-name) (asc :first-name)])
            => Jane)))

(deftest find-and-remove-tests
  (testing "return removed  document"
    (expect (find-and-remove people (where (eq :age 18)))
            => Sally)))

(deftest map-reduce-tests
  (testing "simple counting"
    (let [not-nil? (comp not nil?)
          results (map-reduce people
                       "function() {emit(this.last_name, 1)}"
                       "function(k,vals) {
                           var sum=0;
                           for(var i in vals) sum += vals[i];
                           return sum;
                        }")]
      (expect (:ok results) => 1.0)
      (expect (:counts results) => {:output 1, :emit 4, :input 4})
      (expect (:timing results) => not-nil?)
      (expect (:timeMillis results) => not-nil?)
      (expect (:result results) => not-nil?)
      (expect (fetch-map-reduce-values results) => [{:value 4.0}])
      (expect (fetch-map-reduce-values results (where (eq :values 3))) => empty?)))
  (testing "simple counting map-reduce-fetch-all"
    (expect (map-reduce-fetch-all people
                                  "function() {emit(this.last_name, 1)}"
                                  "function(k,vals) {
                                            var sum=0;
                                            for(var i in vals) sum += vals[i];
                                            return sum;
                                         }")
            => [{:value 4.0}]))
  (testing "simple counting with scriptjure"
    (let [expected {:ok 1.0,
                    :counts {:output 1, :emit 4, :input 4}}
          results (map-reduce people
                              (js (fn [] (emit this.last_name 1)))
                              (js (fn [k vals]
                                    (var sum 0)
                                    (doseq [i vals]
                                      (set! sum (+ sum (aget vals i))))
                                    (return sum))))]
      (expect (:ok results) => 1.0)
      (expect (:counts results) => {:output 1, :emit 4, :input 4})
      (expect (fetch-map-reduce-values results) => [{:value 4.0}]))))

(deftest indexing-tests
  (expect (count (list-indexes people)) => 1) ;; _id is always indexed
    
  (ensure-index people (asc :age))
  (expect (list-indexes people) => [{:key {:_id 1}, :ns "integration-tests.people", :name "_id_"}
                                    {:key {:age 1}, :ns "integration-tests.people", :name "age_1"}])
    
  (drop-index people (asc :age))
  (expect (count (list-indexes people)) => 1)
  
  (ensure-unique-index people "unique-first-name" (asc :first-name))
  (expect (list-indexes people)
          => [{:key {:_id 1}, :ns "integration-tests.people", :name "_id_"}
              {:key {:first-name 1}, :unique true, :ns "integration-tests.people",
               :name "unique-first-name"}]))

