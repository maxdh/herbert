(ns miner.herbert
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [squarepeg.core :as sp]))


(defn unimplemented [x]
  (println "Unimplemented " x))

(defn literal? [con]
  (or (keyword? con) (number? con) (string? con) (false? con) (true? con) (nil? con)))

(defn str-last-char [^String s]
  (when-not (str/blank? s)
    (.charAt s (dec (.length s)))))

;; works with strings and symbols
(defn last-char [s]
  (str-last-char (name s)))

(defn strip-last [x]
  ;; x is symbol or keyword, typically used to strip \?
  (let [xname (name x)
        name1 (subs xname 0 (dec (count xname)))
        ns (namespace x)]
  (if (keyword? x) 
    (keyword ns name1)
    (symbol ns name1))))


(defrecord TaggedValue [tag value])

(defn taggedValue? [x]
  (instance? TaggedValue x))

;; define a type
(def single-test-fn
  {'int integer?
   'num number?
   'float float?
   'list seq?
   'literal literal?
   'char char?
   'str string?
   nil nil?
   true true?
   false false?
   'bool (some-fn true? false?)
   'sym symbol?
   'kw keyword?
   'empty empty?
   'any (constantly true)
   } )

(def coll-test-fn
  {'vec vector?
   'seq sequential?
   'coll coll?
   'keys map?
   'map map?
   'set set?
   } )

(def other-test-fn
  {'tag taggedValue?
   })

(def test-fn (merge single-test-fn coll-test-fn other-test-fn))


(defn guard-spec-fn [spec]
  (unimplemented 'guard-spec-fn)
  (constantly true))

(defn single-item-spec? [spec]
  (boolean (single-test-fn spec)))

(defn single-spec-fn [spec]
  (every-pred (single-test-fn spec) (guard-spec-fn spec)))

(def single-spec-types (keys single-test-fn))


(defn tcon-pred [tcon]
  (get test-fn tcon))

;; need to use eval
;; http://stackoverflow.com/questions/1824932/clojure-how-to-create-a-function-at-runtime

;; FIXME -- this is dangerous is you allow user-defined guards.  They could (launch-missiles)!
;; We should define allowed functions and audit the guard code.
(defn runtime-fn [arg expr]
  (eval `(fn [~arg] ~expr)))

(defn tpred [name con pred]
  (let [arg (case name (_ nil :when) '% name)
        form (if (nil? con)
               (list pred arg)
               (list 'and (list pred arg) con))]
    (runtime-fn arg form)))


(defn tcon-symbol-quantifier [sym]
  (let [ch (last-char sym)]
    (case ch
      \+ :one-or-more
      \* :zero-or-more
      \? :optional
      nil)))

;; FIXME could use strip-last, or combine and make work with keywords
(defn simple-sym [sym]
  (let [sname (name sym)
        lch (str-last-char sname)]
    (case lch
      (\+ \* \?) (symbol (subs sname 0 (dec (.length sname))))
      sym)))

(defn simple-sym? [sym]
  (= sym (simple-sym sym)))

(defn quantified-sym? [sym]
  (not= sym (simple-sym sym)))


;; Unused by maybe better
(defn expand-sym [sym]
  (let [sname (name sym)
        lch (str-last-char sname)]
    (case lch
      (\+ \* \?) (list (symbol (str lch)) (symbol (subs sname 0 (dec (.length sname)))))
      sym)))

(declare tconstraint)

(defn tcon-symbol-constraint [sym]
  (let [lch (last-char sym)
        sym (simple-sym sym)
        brule (sp/mkpr (tcon-pred sym))]
    (case lch
      \+ (sp/mk1om brule)
      \* (sp/mkzom brule)
      \? (sp/mkopt brule)
      brule)))


(defn tcon-list-type [lexpr]
  (let [[tcon name con] lexpr
        lch (last-char tcon)
        tcon (simple-sym tcon)
        pred (tcon-pred tcon)
        brule (sp/mkpr (tpred name con pred))]
    (case lch
      \+ (sp/mk1om brule)
      \* (sp/mkzom brule)
      \? (sp/mkopt brule)
      brule)))

(defn tcon-quoted-sym [sym]
  ;; no special interpretation of symbol
  (sp/mkpr (tcon-pred sym)))

;; SEM FIXME -- drop support for (N t) -- just spell it out N times [t t t] or make a repeat op
;; SEM FIXME -- should use total cycle, not element count

;; n is the total number of desired items,
;; cs might have to be repeated to fill
(defn tcon-nseq 
  ([n cs]
     (case n
       0 (sp/mkseq)
       1 (tconstraint (first cs))
       (apply sp/mkseq (take n (cycle (map tconstraint cs))))))
  ([lo hi cs]
     (let [tcons (cycle (map tconstraint cs))]
       (apply sp/mkseq (concat (take lo tcons)
                               (take (- hi lo) (map sp/mkopt (drop lo tcons))))))))

(defn tcon-seq [cs]
  (apply sp/mkseq (map tconstraint cs)))


;; SEM FIXME -- maybe a little shakey on merging bindings and memo stuff
(defn mkand
  "Create a rule that matches all of rules at the same time for a single input. 
Returns result of first rule."
  ([]
     #(sp/succeed nil [] %1 %2 %4))
  ([rule] rule)
  ([rule1 rule2]
     (fn [input bindings context memo]
       (let [r1 (rule1 input bindings context memo)]
         (if (sp/failure? r1)
           r1
           (let [r2 (rule2 input (:b r1) context (:m r1))]
             (if (sp/failure? r2)
               r2
               ;; maybe should use not identical?
               (if (not= (:r r1) (:r r2))
                 (sp/fail "Subrules matched differently" (merge (:m r1) (:m r2)))
                 r1)))))))
  ([rule1 rule2 & rules]
     (reduce mkand (mkand rule1 rule2) rules)))


(defn tcon-seq-constraint [vexpr]
  (sp/mksub (apply sp/mkseq (conj (mapv tconstraint vexpr) sp/end))))

(defn tcon-list-constraint [lexpr]
  (let [op (first lexpr)]
    (case op
      or (apply sp/mkalt (map tconstraint (rest lexpr)))
      and (apply mkand (map tconstraint (rest lexpr)))
      not (sp/mknot (tconstraint (second lexpr)))
      quote (tcon-quoted-sym (second lexpr))
      * (sp/mkzom (tcon-seq (rest lexpr)))
      + (sp/mk1om (tcon-seq (rest lexpr))) 
      ? (sp/mkopt (tcon-seq (rest lexpr)))  
      = (tcon-seq (rest lexpr))
      seq  (tcon-seq-constraint (rest lexpr))
      vec (mkand (list (sp/mkpr vector?) (tcon-seq-constraint (rest lexpr))))
      list (mkand (list (sp/mkpr list?) (tcon-seq-constraint (rest lexpr))))

      ;; else
      (cond (integer? op) (tcon-nseq op (rest lexpr))
            (vector? op) (tcon-nseq (first op) (second op) (rest lexpr))
            :else (tcon-list-type lexpr)))))

(defn testing-list-constraint [lexpr]
  (let [[tcon name con] lexpr
        pred (tcon-pred tcon)]
    (println "tcon = " tcon (type tcon))
    (println "name = " name (type name))
    (println "con = " con (type con))
    (println "pred = " pred (type pred))
    (flush)
    (tpred name con pred)))

;; SEM untested and unused
(defn mkguard
  "Create a rule that matches all of rules in order. Returns result of first rule.
The others are guard rules that should not consume any input."
  ([]
     #(sp/succeed nil [] %1 %2 %4))
  ([rule] rule)
  ([rule1 rule2]
     (fn [input bindings context memo]
       (let [r1 (rule1 input bindings context memo)]
         (if (sp/failure? r1)
           r1
           (let [r2 (rule2 input (:b r1) context (:m r1))]
             (if (sp/failure? r2)
               r2
               r1))))))
  ([rule1 rule2 & rules]
     (reduce mkguard (mkguard rule1 rule2) rules)))



;; kw cons are encoded into the rules
(defn mkmap [rules]
  (fn [input bindings context memo]
    (let [m (first input)]
      (if (and (seq input) (map? m)
               (every? (fn [rule] (sp/success? (rule (list m) bindings context memo)))
                       rules))
        (sp/succeed m [m] (rest input) bindings memo)
        (sp/fail "Input failed to match required map." memo)))))


(defn has-keys? [m ks]
  (and (map? m)
       (every? (partial contains? m) ks)))

(defn optional-key? [kw]
  (and (keyword? kw)
       (= (last-char kw) \?)))

(defn simple-key [kw]
  (if (optional-key? kw)
    (strip-last kw)
    kw))

(defn optional-keys [m]
  (filter optional-key? (keys m)))

(defn required-keys [m]
  (remove optional-key? (keys m)))

(defn test-constraint? [con val]
  (sp/success? ((tconstraint con) (list val) {} {} {})))


(defn tcon-map-entry [[kw con]]
  ;; FIXME -- only handles kw literals and optional :kw? for keys
  ;; doesn't carry context or results for individual key/val matches
  ;; Note: each rule expect full map as input, but only looks at one key
  (let [sk (simple-key kw)
        rule (tconstraint con)]
    (if (optional-key? kw)
      (sp/mkpr (fn [m]
                 (or (not (contains? m sk))
                     (sp/success? (rule (list (get m sk)) {} {} {})))))
      (sp/mkpr (fn [m]
                 (and (contains? m sk)
                      (sp/success? (rule (list (get m sk)) {} {} {}))))))))

(defn tcon-map-constraint [mexpr]
  (mkmap (map tcon-map-entry mexpr)))

(defn tcon-set-sym [sym]
  (let [simple (simple-sym sym)
        rule (tconstraint simple)]
    (case (last-char sym)
      \* (sp/mkpr (fn [s] (every? #(sp/success? (rule (list %) {} {} {})) s)))
      \+ (sp/mkpr (fn [s] (and (seq s) (every? #(sp/success? (rule (list %) {} {} {})) s))))
      \? (sp/mkpr (fn [s] (or (empty? s) 
                              (and (empty? (rest s))
                                   (sp/success? (rule (seq s) {} {} {}))))))
      ;; else simple
      (sp/mkpr (fn [s] (some #(sp/success? (rule (list %) {} {} {})) s))))))

(defn tcon-set-list [lst]
  (let [[op con unexpected] lst
        quantified (case op (* + ?) true false)
        rule  (if quantified (tconstraint con) (tconstraint lst))]
    (when (and quantified unexpected)
      (throw (ex-info "Unexpectedly more" {:con lst})))
    (case op
      * (sp/mkpr (fn [s] (every? #(sp/success? (rule (list %) {} {} {})) s)))
      + (sp/mkpr (fn [s] (some #(sp/success? (rule (list %) {} {} {})) s)))
      ? (sp/mkpr (fn [s] (or (empty? s) 
                              (and (== (count s) 1)
                                   (sp/success? (rule (seq s) {} {} {}))))))
      ;; else quantified
      (sp/mkpr (fn [s] (some #(sp/success? (rule (list %) {} {} {})) s))))))


(defn tcon-set-element [con]
  (cond (symbol? con) (tcon-set-sym con)
        (list? con) (tcon-set-list con)
        (literal? con) (throw (ex-info "Literals should be handled separately" {:con con}))
        :else (throw (ex-info "I didn't think of that" {:con con}))))


(defn tcon-set-constraint [sexpr]
  (let [nonlits (remove literal? sexpr)
        litset (if (seq nonlits) (set (filter literal? sexpr)) sexpr)]
    (apply mkand (sp/mkpr #(set/subset? litset %)) (map tcon-set-element nonlits))))
           


(defn tconstraint 
  ([expr]
     (cond (symbol? expr) (tcon-symbol-constraint expr)
           (list? expr) (tcon-list-constraint expr)
           (vector? expr) (tcon-seq-constraint expr)
           (set? expr) (tcon-set-constraint expr)
           (map? expr) (tcon-map-constraint expr) 
           (string? expr) (sp/mklit expr)
           (keyword? expr) (sp/mklit expr)
           (nil? expr) (sp/mkpr nil?)
           (false? expr) (sp/mkpr false?)
           (true? expr) (sp/mkpr true?)
           (number? expr) (sp/mklit expr)
             :else (throw (ex-info "Unknown constraint form" {:con expr}))))
     
  ([expr expr2]
     (sp/mkseq (tconstraint expr) (tconstraint expr2)))
  ([expr expr2 & more]
     (apply sp/mkseq (tconstraint expr) (tconstraint expr2) (map tconstraint more))))


(defn confn [con]
  (let [cfn (tconstraint con)]
    (fn ff
      ([item] (ff item {} {} {}))
      ([item context] (ff item context {} {}))
      ([item context bindings memo] (cfn (list item) context bindings memo)))))

(defn conformitor [con]
  (if (fn? con) con #(sp/success? ((confn con) %))))

;; Too Clever?  Single arg creates predicate (for reuse).  Second arg immediately tests.
;; Con can be a fn already (presumed to be a predicate), or a "constraint expression" which
;; is compiled into a predicate.
(defn conforms? 
  ([con] (conformitor con))
  ([con x] ((conformitor con) x)))
