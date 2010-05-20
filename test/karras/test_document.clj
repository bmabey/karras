(ns karras.test-document
  (:require karras)
  (:use karras.sugar
        karras.document
        clojure.test))

(defaggregate Street
  :name
  :number)

(defaggregate Address
  :street {:type Street}
  :city
  :state
  :postal-code)

(defaggregate Phone
  :country-code {:type Integer :default 1}
  :number)

(defentity Person 
  :first-name
  :middle-initial
  :last-name
  :birthday {:type java.util.Date}
  :blood-alcohol-level {:type Float :default 0.0}
  :address {:type Address}
  :phones {:type java.util.List :of Phone})

(defentity Simple :value)

(defonce db (karras/mongo-db (karras/connect) :document-testing))

(use-fixtures :each (fn [t]
                      (karras/with-mongo-request db
                        (karras/drop-collection (collection-for Person))
                        (t))))

(deftest test-parse-fields
  (let [parsed? (fn [fields expected-parsed-fields]
                  (is (= (parse-fields fields) expected-parsed-fields)))]
    (testing "empty fields"
          (parsed? nil {}))
    (testing "no type specified"
      (parsed? [:no-type] {:no-type {:type String}}))
    (testing "type specified"
      (parsed? [:with-type {:type Integer}] {:with-type {:type Integer}}))
    (testing "mixed types and no types"
          (parsed? [:no-type
                    :with-type {:type Integer}]
                   {:no-type {:type String}
                    :with-type {:type Integer}})
          (parsed? [:with-type {:type Integer}
                    :no-type]
                   {:with-type {:type Integer}
                    :no-type {:type String}})))
  (are [fields] (thrown? IllegalArgumentException (parse-fields fields))
       ['not-a-keyword]
       [:keyword 'not-a-map-or-keyword]))

(deftest test-callback-protocol
  (are [callback e] (= e (callback e))
       before-create (Person.)
       before-destroy (Person.)
       before-save (Person.)
       before-update (Person.)
       before-validate (Person.)
       after-create (Person.)
       after-destroy (Person.)
       after-save (Person.)
       after-update (Person.)
       after-validate (Person.)))

(deftest test-docspec
  (is (not (nil? (docspec Address))))
  (is (not (nil? (docspec Phone))))
  (is (not (nil? (docspec Person)))))

(deftest test-docspec-in
  (is (= (docspec Address) (docspec-of Person :address)))
  (is (= (docspec java.util.List) (docspec-of Person :phones)))
  (is (= (docspec Phone) (docspec-of-item Person :phones)))
  (is (= (docspec Street) (docspec-of Person :address :street))))

(deftest test-docspec-in
  (is (= (docspec Address) (docspec-of Person :address)))
  (is (= (docspec java.util.List) (docspec-of Person :phones)))
  (is (= (docspec Phone) (docspec-of-item Person :phones)))
  (is (= (docspec Street) (docspec-of Person :address :street))))

(deftest test-collection-name
  (testing "default name"
    (is (= (:collection-name (docspec Simple)) "Simple")))
  (testing "override name"
    (alter-entity-type! Person :collection-name "people")
    (is (= (:collection-name (docspec Person)) "people"))))

(deftest test-make
  (let [phone (make Phone {:number "555-555-1212"})]
    (is (= Phone (class phone))))
  (let [address (make Address {:city "Nashville"
                               :street {:number "123"
                                        :name "Main St."}})]
    (is (= Address (class address)))
    (is (= Street (class (:street address)))))
  (let [person (make Person {:first-name "John"
                             :last-name "Smith"
                             :birthday (karras/date 1976 7 4)
                             :phones [{:number "123" :country-code 2}]
                             :address {:city "Nashville"
                                       :street {:number "123" :name "Main St."}}})]
    (is (= Address (class (-> person :address))))
    (is (= Street (class (-> person :address :street))))
    (is (= Phone (class (-> person :phones first))))))



(deftest test-crud
  (let [person (create Person {:first-name "John"
                               :last-name "Smith"
                               :birthday (karras/date 1976 7 4)
                               :phones [{:number "123" :country-code 2}]
                               :address {:city "Nashville"
                                         :street {:number "123" :name "Main St."}}})]
    (is (= karras.test-document.Person (class person)))
    (is (not (nil? (:_id person))))
    (is (= (karras/collection :people) (collection-for Person)))
    (is (= (karras/collection :people) (collection-for person)))
    (is (= person (fetch-one Person
                             (where (eq :_id (:_id person))))))
    (is (= person (first (fetch-all Person))))
    (is (= person (first (fetch Person (where (eq :last-name "Smith"))))))
    (dotimes [x 5]
      (create Person {:first-name "John" :last-name (str "Smith" (inc x))}))
    (is (= "John" (first (distinct-values Person :first-name))))
    (is (= 6 (count-instances Person)))
    (delete person)
    (is (= 5 (count-instances Person)))
    (delete-all Person (where (eq :last-name "Smith1")))
    (is (= 4 (count-instances Person)))
    (delete-all Person)
    (is (= 0 (count-instances Person)))))