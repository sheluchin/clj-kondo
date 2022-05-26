(ns clj-kondo.hooks-api
  (:require
   [clj-kondo.impl.cache :as cache]
   [clj-kondo.impl.findings :as findings]
   [clj-kondo.impl.rewrite-clj.node :as node]
   [clj-kondo.impl.rewrite-clj.parser :as parser]
   [clj-kondo.impl.utils :as utils]))

(defn- mark-generate [node]
  (assoc node :clj-kondo.impl/generated true))

(defn parse-string [s]
  (parser/parse-string s))

(defn keyword-node? [n]
  (utils/keyword-node? n))

(def keyword-node
  (comp mark-generate utils/keyword-node))

(defn string-node? [n]
  (instance? clj_kondo.impl.rewrite_clj.node.string.StringNode n))

(def string-node
  (comp mark-generate utils/string-node))

(defn token-node? [n]
  (instance? clj_kondo.impl.rewrite_clj.node.token.TokenNode n))

(def token-node
  (comp mark-generate utils/token-node))

(defn vector-node? [n]
  (and (instance? clj_kondo.impl.rewrite_clj.node.seq.SeqNode n)
       (identical? :vector (utils/tag n))))

(def vector-node (comp mark-generate utils/vector-node))

(def list-node? utils/list-node?)

(def list-node (comp mark-generate utils/list-node))

(defn map-node? [n]
  (and (instance? clj_kondo.impl.rewrite_clj.node.seq.SeqNode n)
       (identical? :map (utils/tag n))))

(def map-node (comp mark-generate utils/map-node))

(defn sexpr [expr]
  (node/sexpr expr))

(defn reg-finding! [m]
  (let [ctx utils/*ctx*
        filename (:filename ctx)]
    (findings/reg-finding! ctx (assoc m :filename filename))))

(defn reg-keyword!
  [k reg-by]
  (utils/assoc-some k :reg reg-by))

(defn coerce [s-expr]
  (node/coerce s-expr))

(defn- var-definitions
  "Project cached analysis as a subset of public var-definitions."
  [analysis]
  (let [selected-keys [:ns :name
                       :fixed-arities :varargs-min-arity
                       :private :macro]]
    (->> (dissoc analysis :filename :source)
         (utils/map-vals #(select-keys % selected-keys)))))

(defn- ns-analysis*
  "Adapt from-cache-1 to provide a uniform return format.
  Unifies the format of cached information provided for each source
  language."
  [lang ns-sym]
  (if (= lang :cljc)
    (->> (dissoc
          (cache/from-cache-1 (:cache-dir utils/*ctx*) :cljc ns-sym)
          :filename
          :source)
         (utils/map-vals var-definitions))
    (some->> (cache/from-cache-1 (:cache-dir utils/*ctx*) lang ns-sym)
             var-definitions
             (hash-map lang))))

(defn ns-analysis
  "Return any cached analysis for the namespace identified by ns-sym.
  Returns a map keyed by language keyword with values being maps of var
  definitions keyed by defined symbol. The value for each symbol is a
  subset of the values provide by the top level :analysis option."
  ([ns-sym] (ns-analysis ns-sym {}))
  ([ns-sym {:keys [lang]}]
   (if lang
     (ns-analysis* lang ns-sym)
     (reduce
      merge
      {}
      (map #(ns-analysis* % ns-sym) [:cljc :clj :cljs])))))