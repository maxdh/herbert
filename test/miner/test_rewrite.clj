(ns miner.test-rewrite
  (:use clojure.test)
  (:require miner.herbert.predicates
            [miner.herbert :as h :exclude [conforms? conform]]
            [miner.herbert.canonical :as c]
            clojure.string))


(defn conforms? [schema val]
  (h/conforms? (c/rewrite schema) val))

(defn conform [schema val]
  (h/conform (c/rewrite schema) val))

(deftest basics []
  (is (conforms? 'int 10))
  (is (conforms? 'str "foo"))
  (is (conforms? 'sym 'foo))
  (is (conforms? 'kw :foo))
  (is (conforms? 'float 1.23))
  (is (not (conforms? 'sym :foo))))

(deftest numbers []
  (is (conforms? 'even 10))
  (is (not (conforms? 'even 'foo)))
  (is (conforms? '(and int pos (not neg) (not odd) (not zero)) 10))
  (is (not (conforms? '(or int pos neg odd zero) 'foo)))
  (is (conforms? '[(* (or neg zero))] [-1 0.0 0 -100.0 0])))

(deftest nested []
  (is (conforms? '[int sym str kw] '(10 foo "foo" :foo)))
  (is (not (conforms? '[int sym str kw] '(10 :a foo "foo" :foo))))
  (is (conforms? '[(* kw int)] '(:a 10 :b 20 :c 30)))
  (is (conforms? '[(+ kw int sym)] '(:a 10 foo :b 20 bar))))

(deftest complicated []
  (are [val result] (= (conforms? '[{:a [(odd* 20)]} sym (mod 4)] val) result)
       '[{:a [1]} foo 4] true
       '[{:a [1 3 5]} foo 24] true
       '[{:a (1 2 3)} foo 4] false
       '[{:a []} foo 84] true
       '[{:a [:b 1 2 3]} foo 4] false
       '[{:b [1 2 3]} foo 4] false
       '[{:a [11 21 33]} foo 8] false))

(deftest kw-maps []
  (are [val result] (= (conforms? '{:a int :b sym :c? str} val) result)
       {:a 1 :b 'foo :c "foo"}   true
       {:a 1 :b 'foo}   true
       {:a 1 :b 'foo :d 'bar}   true  
       {:a 1 :b 'foo :c 'bar}   false
       {:a :kw :b 'foo}   false
       {:b 'foo :c "foo"} false)
  (are [val result] (= (conforms? '{:a int :b sym :c? nil} val) result)
       ;; :c is essentiall disallowed, technically can only have nil value
       {:a 1 :b 'foo :c "foo"}   false
       {:a 1 :b 'foo}   true
       {:a 1 :b 'foo :d 'bar}   true  
       {:a 1 :b 'foo :c 'bar}   false
       {:a 1 :b 'foo :c nil}  true))

(deftest other-keys []
  (are [val result] (= (conforms? '{a int b sym "c" str} val) result)
       '{a 1 b foo "c" "foo"}   true
       '{a 1 b foo "c" 'bar}   false
       '{a :kw b foo "c" "foo"}   false))

(deftest quoted-kws []
  (are [val result] (= (conforms? '{':a? int ':b sym} val) result)
       {:a? 1 :b 'foo}   true
       {:a 1 :b 'foo}   false
       {:a? 1 :b :foo}   false
       {:a? 1 :b 'foo :d 'bar}   true  
       {:a 'foo :a? 1 :b 'foo :c nil}  true))

(deftest sets []
  (are [val result] (= (conforms? '#{:a :b :c} val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   false)
  (are [val result] (= (conforms? '#{int :a :b :c} val) result)
       #{:a 10 :b :c}   true
       #{:d :c :a :b}   false
       #{10 20}   false
       #{:b 10 :c 20 :a}   true)
  (are [val result] (= (conforms? '#{int sym} val) result)
       #{10 :a :b 'foo}   true
       #{:d :c :a :b 10} false
       #{'foo 10}   true)
  (are [val result] (= (conforms? '#{kw*} val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} true
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '#{kw+} val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} false
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '#{kw?} val) result)
       #{:a :b :c}   false
       #{:d :c :a :b}   false
       #{:a}   true
       #{} true
       #{10} false
       #{:a 'foo} false))

(deftest sets2 []
  (are [val result] (= (conforms? '(set :a :b :c) val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   false)
  (are [val result] (= (conforms? '(set int :a :b :c) val) result)
       #{:a 10 :b :c}   true
       #{:d :c :a :b}   false
       #{10 20}   false
       #{:b 10 :c 20 :a}   true)
  (are [val result] (= (conforms? '(set int sym) val) result)
       #{10 :a :b 'foo}   true
       #{:d :c :a :b 10} false
       #{'foo 10}   true)
  (are [val result] (= (conforms? '(set kw*) val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} true
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '(set (* kw)) val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} true
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '(set kw+) val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} false
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '(set (+ kw)) val) result)
       #{:a :b :c}   true
       #{:d :c :a :b}   true
       #{:d :c :a}   true
       #{} false
       #{:d 10 :a} false
       #{:a :b :c 'foo} false)
  (are [val result] (= (conforms? '(set kw?) val) result)
       #{:a :b :c}   false
       #{:d :c :a :b}   false
       #{:a}   true
       #{} true
       #{10} false
       #{:a 'foo} false)
  (are [val result] (= (conforms? '(set (? kw)) val) result)
       #{:a :b :c}   false
       #{:d :c :a :b}   false
       #{:a}   true
       #{} true
       #{10} false
       #{:a 'foo} false))

;; Note (or xxx+ yyy+) works but (or xxx* yyy*) can fail since zero xxx matches and yyy doesn't
;; get the chance after that.  Remember, no backtracking.  The + lets it work as expected.
(deftest or-plus []
  (are [val] (conforms? '[int kw (or sym+ str+)] val)
       '[10 :a foo bar baz]
       '[10 :a "foo" "bar" "baz"])
  (are [val] (conforms? '[int kw (or str+ sym+)] val)
       '[10 :a foo bar baz]
       '[10 :a "foo" "bar" "baz"]))


(deftest binding-with-when []
  (is (conforms? '[(:= n int) (:= m int) (when (= (* 2 n) m)) ] [2 4]))
  (is (conforms? '[(:= n int) [(:= ms kw*)] (when (= (count ms) n))] '[3 [:a :b :c]]))
  (is (not (conforms? '[(:= n int) (:= m int) (when (= (* 3 n) m)) ] [2 4])))
  (is (conforms? '(& (:= ns (and [int+] (step 3))) (when (== (count ns) 4))) [2 5 8 11]))
  ;; better equivalent
  (is (conforms? '(and [int+] (step 3) (cnt 4)) [2 5 8 11])))

(deftest binding-with-implied-when []
  (is (conforms? '[(:= n int) (:= m int) (>= (* 2 n) m) ] [2 4]))
  (is (conforms? '[(:= n int) [(:= ms kw*)] (<= (count ms) n)] '[3 [:a :b :c]]))
  (is (not (conforms? '[(:= n int) (:= m int) (== (* 3 n) m) ] [2 4])))
  (is (conforms? '(& (:= ns (and [int+] (step 3))) (== (count ns) 4)) [2 5 8 11])))

(deftest and-or []
  (is (conforms? '[(or int kw) (and int even)] [:a 4]))
  (is (conforms? '[(or kw sym) (and int odd)] [:a 7])))

(deftest not-constraints []
  (is (conforms? '[(not sym)] [:a]))
  (is (conforms? '[(or int kw sym) (not int)] [:a :a]))
  (is (conforms? '[(or int kw sym) (and num (not even))] ['a 6.1])))

(deftest strings []
  (is (conforms? 'str "foobar"))
  (is (conforms? '(str "f.*r") "foobar"))
  (is (not (conforms? '(str "f.*r") "xfoobar"))))

(deftest nested-map-when []
  (is (conforms? '(& {:a (:= a int) :b {:bb (:= bb int)}} (when (== a bb))) 
                 {:a 10 :b {:bb 10}}))
  (is (not (conforms? '(& {:a (:= a int) :b {:bb (:= bb int)}} (when (== a bb))) 
                      {:a 11 :b {:bb 10}}))))

(deftest solo-constraints-for-equality []
  (is (conforms? '(& {:a (:= a int) :b {:bb (a)}}) {:a 10 :b {:bb 10}}))
  (is (conforms? '(& {:a (:= a int) :b {:bb a}}) {:a 10 :b {:bb 10}}))
  (is (conforms? '(& {:a (:= a int) :b {:bb [a+]}}) {:a 10 :b {:bb [10 10 10]}}))
  (is (not (conforms? '(& {:a (:= a int) :b {:bb [a+]}}) {:a 10 :b {:bb 10}})))
  (is (not (conforms? '(& {:a (:= a int) :b {:bb a}}) {:a 10 :b {:bb 11}}))))

(deftest solo-count []
  (is (conforms? '(& {:a (:= a int) :b (:= b sym) :c? (:= c [b+])} (when (= (count c) a))) 
             '{:a 2 :b foo :c [foo foo]})))

(deftest bind-args []
  (is (conforms? '[(:= a int) (:= b int) (:= c int a b)] [3 7 5])))

(deftest underbar-bind-args []
  ;; underbar _ should be ignored as a binding name
  (is (conforms? '[(:= _ int) (:= _ sym "f.*") (:= _ (str ".*r"))] '[42 foo "bar"])))

(deftest as-bind-args []
  (is (conforms? '[(:= a int) (:= b (int 2 15)) (:= c (int a b))] [3 7 5]))
  (is (not (conforms? '[(:= a int) (:= b (int 2 15)) (:= c (int a b))] [3 4 5])))
  (is (conforms? '[(:= _ int) (:= _ (sym "f.*")) (:= _ (str ".*r"))] '[42 foo "bar"]))
  (is (not (conforms? '[(:= _ int) (:= _ (sym "f.*")) (:= _ (str ".*r"))] '[42 "foo" bar]))))

(deftest quoted-syms []
  (is (conforms? '[int 'int sym] '[42 int foo]))
  (is (not (conforms? '[int 'int sym] '[42 13 foo]))))

(deftest regex-forms []
  (is (conforms? '(kw ":foo/.*") :foo/bar))
  (is (not (conforms? '(kw ":foo/.*") :foo)))
  (is (conforms? '(str "foo/.*") "foo/bar"))
  (is (conforms? '(str "fo+.ar") "fooooobar"))
  (is (conforms? '(sym "user/.*") 'user/foobar))
  (is (not (conforms? '(sym "user/.*") :user/foobar))))

(deftest non-literal-mapkv
  (is (conforms? '(keys kw int) {:a 42}))
  (is (not (conforms? '(keys kw int) {'a 42})))
  (is (not (conforms? '(keys kw int) {:a 'b52})))
  (is (conforms? '(keys (or sym kw) (or sym int)) {:a 'b52}))
  (is (conforms? '(keys (or sym kw) (or sym int)) {'b 'b52}))
  (is (conforms? '(keys (or sym kw) (or sym int)) {'b 52}))
  (is (conforms? '(keys sym) {'b 52}))
  (is (conforms? '(keys) {'b 52}))
  (is (conforms? 'keys {'b 52}))
  (is (not (conforms? '(keys (or sym kw) (or sym int)) {:a :b52}))))
  