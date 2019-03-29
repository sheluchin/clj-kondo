(ns clj-kondo.impl.linters
  {:no-doc true}
  (:require
   [clj-kondo.impl.macroexpand :refer [expand-all]]
   [clj-kondo.impl.utils :refer [node->line parse-string
                                 parse-string-all some-call
                                 tag]]
   [clj-kondo.impl.vars :refer [analyze-arities]]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;;;; inline def

(defn inline-def* [expr in-def?]
  (let [current-def? (some-call expr def defn defn- deftest defmacro)
        new-in-def? (and (not (contains? '#{:syntax-quote :quote}
                                         (tag expr)))
                         (or in-def? current-def?))]
    (if (and in-def? current-def?)
      [expr]
      (when (:children expr)
        (mapcat #(inline-def* % new-in-def?) (:children expr))))))

(defn inline-def [filename parsed-expressions]
  (map #(node->line filename % :warning :inline-def "inline def")
       (inline-def* parsed-expressions false)))

;;;; obsolete let

(defn obsolete-let* [{:keys [:children] :as expr}
                     parent-let?]
  (let [current-let? (some-call expr let)]
    (cond (and current-let? parent-let?)
          [expr]
          current-let?
          (let [;; skip let keywords and bindings
                children (nnext children)]
            (concat (obsolete-let* (first children) current-let?)
                    (mapcat #(obsolete-let* % false) (rest children))))
          :else (mapcat #(obsolete-let* % false) children))))

(defn obsolete-let [filename parsed-expressions]
  (map #(node->line filename % :warning :nested-let "obsolete let")
       (obsolete-let* parsed-expressions false)))

;;;; obsolete do

(defn obsolete-do* [{:keys [:children] :as expr}
                    parent-do?]
  (let [implicit-do? (some-call expr fn defn defn-
                            let loop binding with-open
                            doseq try)
        current-do? (some-call expr do)]
    (cond (and current-do? (or parent-do?
                               (and (not= :unquote-splicing
                                          (tag (second children)))
                                    (<= (count children) 2))))
          [expr]
          :else (mapcat #(obsolete-do* % (or implicit-do? current-do?)) children))))

(defn obsolete-do [filename parsed-expressions]
  (map #(node->line filename % :warning :obsolete-do "obsolete do")
       (obsolete-do* parsed-expressions false)))

;;;; processing of string input

(defn process-input
  [input filename language]
  (let [;; workaround for https://github.com/xsc/rewrite-clj/issues/75
        input (-> input
                  (str/replace "##Inf" "::Inf")
                  (str/replace "##-Inf" "::-Inf")
                  (str/replace "##NaN" "::NaN")
                  ;; workaround for https://github.com/borkdude/clj-kondo/issues/11
                  (str/replace #_"#:a{#::a {:a b}}"
                               #"#(::?)(.*?)\{" (fn [[_ colons name]]
                                                  (str colons name "{"))))
        parsed-expressions (parse-string-all input)
        parsed-expressions (expand-all parsed-expressions)
        ids (inline-def filename parsed-expressions)
        nls (obsolete-let filename parsed-expressions)
        ods (obsolete-do filename parsed-expressions)
        {:keys [:calls :defns]} (analyze-arities filename language parsed-expressions)]
    {:findings (concat ids nls ods)
     :calls calls
     :defns defns
     :lang language}))

;;;; scratch

(comment
  ;; TODO: fix/optimize cache format
  ;; TODO: clean up code
  ;; TODO: distribute binaries
  )