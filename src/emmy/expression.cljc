#_"SPDX-License-Identifier: GPL-3.0"

(ns emmy.expression
  "This namespace contains a number of functions and utilities for manipulating
  and comparing raw symbolic expression trees.

  Also included is an implementation of a [[Literal]] type that forms the basis
  for [[emmy.abstract.number/literal-number]]."
  (:refer-clojure :exclude [sorted? compare sort])
  (:require [clojure.core :as core]
            [clojure.pprint :as pp]
            [clojure.walk :as w]
            [emmy.generic :as g]
            [emmy.util :as u]
            [emmy.value :as v])
  #?(:clj
     (:import (clojure.lang IObj))))

(def ^{:doc "These keywords reference 'abstract' types that stand in for some
  concrete data type in the system."}
  abstract-types
  #{::numeric
    ::vector
    ::abstract-down
    ::abstract-matrix})

;; A Literal is a container type for literal expressions, abstract structures
;; that stand in for some other type. The canonical example is a symbolic
;; expression built out of Lisp data structures.
;;
;; Currently we only support these, but this type will be able to handle the
;; other abstract structures referenced in [[abstract-types]].

(deftype Literal [type expression m]
  v/Numerical
  (numerical? [_] (= type ::numeric))

  v/Value
  (zero? [_]
    (and (v/number? expression)
         (v/zero? expression)))

  (one? [_]
    (and (v/number? expression)
         (v/one? expression)))

  (identity? [_]
    (and (v/number? expression)
         (v/one? expression)))

  (zero-like [_] 0)
  (one-like [_] 1)
  (identity-like [_] 1)
  (exact? [_]
    (and (v/number? expression)
         (v/exact? expression)))
  (freeze [_] (v/freeze expression))
  (kind [_] type)

  Object
  (toString [_] (pr-str expression))
  #?(:cljs
     (valueOf [this]
              (cond (number? expression)   expression
                    (v/number? expression) (.valueOf expression)
                    :else this)))
  #?(:clj
     (equals [_ b]
             (if (instance? Literal b)
               (let [b ^Literal b]
                 (and (= type (.-type b))
                      (v/= expression (.-expression b))
                      (= m (.-m b))))
               (v/= expression b))))

  #?@(:clj
      [IObj
       (meta [_] m)
       (withMeta [_ meta] (Literal. type expression meta))

       Comparable
       (compareTo [_ b]
                  (if (instance? Literal b)
                    (v/compare expression (.-expression ^Literal b))
                    (v/compare expression b)))]

      :cljs
      [IMeta
       (-meta [_] m)

       IWithMeta
       (-with-meta [_ meta] (Literal. type expression meta))

       IEquiv
       (-equiv [_ b]
               (if (instance? Literal b)
                 (let [b ^Literal b]
                   (and (= type (.-type b))
                        (v/= expression (.-expression b))
                        (= m (.-m b))))
                 (v/= expression b)))

       IComparable
       (-compare [_ b]
                 (if (instance? Literal b)
                   (-compare expression (.-expression ^Literal b))
                   (-compare expression b)))

       IPrintWithWriter
       (-pr-writer
        [_ writer _]
        (-write writer (str expression)))]))

#?(:clj
   (defmethod print-method Literal [^Literal s w]
     (print-method (.-expression s) w)))

(defn make-literal
  "Constructs a [[Literal]] instance with the supplied type and an empty metadata
  map out of the literal form `expr`."
  [type expr]
  (->Literal type expr nil))

(defn literal-apply
  "Similar to [[make-literal]], but accepts:

  - some operation
  - the arguments to which it applies

  Similar to `clojure.core/apply`.

  For example:

  ```clojure
  (literal-apply ::numeric 'cos [1 2 3])
  ;;=> (cos 1 2 3)
  ```"
  [type op args]
  (make-literal type (cons op (seq args))))

(defn literal?
  "Returns true if `x` is a [[Literal]] instance, false otherwise."
  [x]
  (instance? Literal x))

(defn abstract?
  "Returns true if `x` is both a [[Literal]] and has a type specified
  in [[abstract-types]], false otherwise."
  [x]
  (and (literal? x)
       (contains? abstract-types
                  (.-type ^Literal x))))

(defn literal-type
  "If `x` is a [[Literal]] instance, returns its type. Else, returns nil."
  [x]
  (when (literal? x)
    (.-type ^Literal x)))

(defn fmap
  "Returns a [[Literal]] generated by applying `f` to the expression part of
  `e`.

  [[literal-type]] and [[meta]] will return the same thing for `e` and the
  return value."
  [f ^Literal e]
  (->Literal (.-type e)
             (f (.-expression e))
             (.-m e)))

(defn expression-of
  "If the supplied argument is a [[Literal]] (or a symbol, interpreted elsewhere
  as a numerical literal expression), returns the wrapped expression (or the
  symbol).

  Else, returns `expr`."
  [expr]
  (if (literal? expr)
    (.-expression ^Literal expr)
    expr))

;; ## Expression Walking

(defn variables-in
  "Return the set of 'variables' (e.g. symbols) found in `expr`.

  `expr` is either a symbol, a [[Literal]] instance or some sequence
  representing a symbolic expression."
  [expr]
  (cond (symbol? expr) #{expr}
        (literal? expr) (recur (expression-of expr))
        :else (let [xs (rest (tree-seq sequential? seq expr))]
                (into #{} (filter symbol?) xs))))

(defn evaluate
  "Walk the unwrapped expression `expr` in postorder, replacing symbols found
  there with their values in the `sym->var` mapping, if present.

  `sym->f` is used for elements in function application position (first of a
  sequence)."
  [expr sym->var sym->f]
  (letfn [(walk [node]
            (cond (symbol? node) (sym->var node node)
                  (sequential? node)
                  (let [[f-sym & args] node]
                    (if-let [f (sym->f f-sym)]
                      ;; NOTE: without `mapv` (ie, with `map` and a lazy
                      ;; sequence), we were getting heisenbugs in the rational
                      ;; function simplifier, and `mismatched-arity` notes.
                      (apply f (mapv walk args))
                      (u/illegal (str "Missing fn for symbol - " f-sym))))
                  :else node))]
    (walk
     (expression-of expr))))

(defn substitute
  "Returns a form similar to `expr`, with all instances of `old` replaced by
  `new`. Substitution occurs
  in [postwalk](https://clojuredocs.org/clojure.walk/postwalk) order.

  NOTE that this now works for expressions too."
  ([expr old new]
   (substitute expr {old new}))
  ([expr s-map]
   (if (literal? expr)
     (fmap #(substitute % s-map) expr)
     (w/postwalk-replace s-map expr))))

(defn compare
  "Comparator for expressions. The rule is that types have the following ordering:

  - empty sequence is < anything (except another empty seq)
  - real < symbol < string < sequence
  - sequences compare element-by-element

  Any types _not_ in this list compare with the other type using hashes."
  [l r]
  (let [lseq?    (sequential? l)
        rseq?    (sequential? r)
        rsym?    (symbol? r)
        rstr?    (string? r)
        l-empty? (and lseq? (empty? l))
        r-empty? (and rseq? (empty? r))
        raw-comp (delay (core/compare (hash l) (hash r)))]

    (cond (and l-empty? r-empty?) 0
          l-empty?                -1
          r-empty?                1
          (v/real? l) (cond (v/real? r) (core/compare l r)
                            (or rsym? rstr? rseq?)
                            -1
                            :else @raw-comp)
          (v/real? r) 1

          (symbol? l) (cond rsym? (core/compare l r)
                            (or rstr? rseq?) -1
                            :else @raw-comp)
          rsym? 1

          (string? l) (cond rstr? (core/compare l r)
                            rseq? -1
                            :else @raw-comp)
          rstr? 1

          lseq? (if rseq?
                  (let [n1 (count l)
                        n2 (count r)]
                    (cond (< n1 n2) -1
                          (< n2 n1) 1
                          :else (let [head-compare (compare
                                                    (first l) (first r))]
                                  (if (zero? head-compare)
                                    (recur (rest l) (rest r))
                                    head-compare))))
                  @raw-comp)
          rseq? 1

          :else @raw-comp)))

(defn sorted? [xs]
  (or (not (sequential? xs))
      (every? (fn [[l r]]
                (<= (compare l r) 0))
              (partition 2 1 xs))))

(defn sort [xs]
  (if (sequential? xs)
    (core/sort compare xs)
    xs))

;; ## Printing

(defn expression->stream
  "Renders an expression through the simplifier and onto the stream."
  ([expr stream]
   (-> (g/simplify expr)
       (pp/write :stream stream)))
  ([expr stream options]
   (let [opt-seq (->> (assoc options :stream stream)
                      (apply concat))
         simple (g/simplify expr)]
     (apply pp/write simple opt-seq))))

(defn expression->string
  "Returns a string representation of a frozen, simplified version of the supplied
  expression `expr`."
  [expr]
  (pr-str
   (v/freeze (g/simplify expr))))

(defn print-expression [expr]
  (pp/pprint
   (v/freeze (g/simplify expr))))

(def pe print-expression)