(ns lazybot.plugins.clojure
  (:use clojure.stacktrace
        (clojail testers core)
        lazybot.registry
        clojure.tools.logging
        [lazybot.utilities :only [on-thread verify trim-string]]
        [lazybot.plugins.shorturl :only [is-gd]]
        [lazybot.gist :only [trim-with-gist gist]]
        [useful.fn :only [fix]])
  (:require [clojure.string :as string :only [replace]]
            [clojure.walk :as walk]
            [cd-client.core :as cd]
            ; these requires are for findfn
            [clojure.string :as s]
            clojure.set
            clojure.repl)
  (:import java.io.StringWriter
           java.util.concurrent.TimeoutException
           java.util.regex.Pattern
           clojure.lang.LispReader$ReaderException))

(defn doc* [v]
  (if (symbol? v)
    (str "Special: " v "; " (:doc (#'clojure.repl/special-doc v)))
    (let [[arglists macro docs]
          (-> v
              meta
              ((juxt :arglists
                     :macro
                     :doc)))
          docs (and docs (string/replace docs #"\s+" " "))]
      (str (and macro "Macro ") arglists "; " docs))))

(def sb
  (sandbox secure-tester
           :transform pr-str
           :init '(defmacro doc [s]
                    (if (special-symbol? s)
                      (lazybot.plugins.clojure/doc* s)
                      `(doc* (var ~s))))))

(def cap 300)

(defn trim [bot-name user expression s]
  (trim-with-gist
    cap
    "result.clj"
    (str "<" user "> " expression "\n<" bot-name "> => ")
    s))

(defn get-line-url [s]
  (let [s-meta (try (-> s symbol resolve meta) (catch Exception _ nil))
        ns-str (str (:ns s-meta))]
    (when-let [line (:line s-meta)]
      (is-gd
       (if-not (= "clojure.core" ns-str)
         (str "https://github.com/clojure/clojure-contrib/tree/1.2.x/src/main/clojure/"
              (:file s-meta) "#L" line)
         (str "https://github.com/clojure/clojure/blob/1.2.x/src/clj/clojure/core.clj#L" line))))))

(defn no-box [code bindings]
  (thunk-timeout #(with-bindings bindings (eval code)) 10000))

(defn execute-text [box? bot-name user txt pre]
  (try
    (with-open [writer (StringWriter.)]
      (let [bindings {#'*out* writer}
            res (if box?
                  (sb (safe-read txt) bindings)
                  (pr-str (no-box (read-string txt) bindings)))
            replaced (string/replace (str writer) "\n" " ")
            result (str replaced (when (= last \space) " ") res)]
        (str (or pre "\u21D2 ") (trim bot-name user txt result))))
   (catch TimeoutException _ "Execution Timed Out!")
   (catch Exception e (str (root-cause e)))))

(def tasks (atom [0]))

(defn first-object [s]
  (when (seq s)
    (try
      (-> (safe-read s)
          (fix coll? pr-str, nil))
      (catch Exception _))))

(defmulti find-eval-request
  "Search a target string for eval requests.
Return a seq of strings to be evaluated. Usually this will be either nil or a one-element list, but it's possible for users to request evaluation of multiple forms with embedded specifiers, in which case it will be longer."
  {:arglists '([matcher target])}
  (fn [x & _] (class x)))

(defmethod find-eval-request String
  ([search target]
     (when (.startsWith target search)
       [(apply str (drop (count search) target))])))

(defmethod find-eval-request Pattern
  ([pattern target]
     (->> (re-seq pattern target)
          (keep (comp first-object second))
          seq)))

(defn- eval-config-settings [bot]
  (let [config-setting (-> @bot :config (get :eval-prefixes
                                             {:defaults #{}}))]
    (if (vector? config-setting)
      {:defaults (set config-setting)}      ; backwards compatible
      config-setting)))

(defn- default-prefixes [bot]
  (:defaults (eval-config-settings bot)))

;; Make sure Pattern objects show up first
(defn- pattern-comparator [a b]
  (let [ac (class a)
        bc (class b)]
    (cond
     (= (= ac Pattern) ; both patterns, or both strings
        (= bc Pattern))
     (compare (str a) (str b)) ; so compare by value

     (= ac Pattern) -1 ; make the pattern come first
     :else 1)))

(defn- eval-exceptions [bot channel]
  (set (get (eval-config-settings bot)
            channel
            [])))

(defn- what-to-eval [bot channel message]
  (let [candidates (default-prefixes bot)
        exceptions (eval-exceptions bot channel)
        patterns (sort pattern-comparator
                       (remove exceptions candidates))]
    (first (keep #(find-eval-request % message)
                 patterns))))

(def max-embedded-forms 3)

(defn- eval-forms [box? bot-name user pre [form1 form2 :as forms]]
  (take max-embedded-forms
        (if-not form2
          [(execute-text box? bot-name user form1 pre)]
          (for [form forms]
            (str (trim-string 40 (constantly "... ") form)
                 " " (execute-text box? bot-name user form pre))))))


(def findfn-ns-set
  (map the-ns '#{clojure.core clojure.set clojure.string}))

(defn fn-name [var]
  (apply symbol (map str
                     ((juxt (comp ns-name :ns)
                            :name)
                      (meta var)))))

(defn filter-vars [testfn]
  (for [f (remove (comp secure-tester :name meta)
                  (mapcat (comp vals ns-publics) findfn-ns-set))
        :when (try
               (thunk-timeout
                #(binding [*out* (java.io.StringWriter.)]
                   (testfn f))
                50 :ms eagerly-consume)
               (catch Throwable _# nil))]
    (fn-name f)))

(defn find-fn [out & in]
  (debug (str "out:[" out "], in[" in "]"))
  (filter-vars
   (fn [f]
     (= out
        (apply
         (if (-> f meta :macro)
           (fn [& args]
             (eval `(~f ~@args)))
           f)
         in)))))

(defn find-arg [out & in]
  (debug (str "out:[" out "], in[" in "]"))
  (filter-vars
   (fn [f]
     (when-not (-> f meta :macro)
       (= out
          (eval `(let [~'% ~f]
                   (~@in))))))))

(defn read-findfn-args
  "From an input string like \"in1 in2 in3 out\", return a vector of [out
  in1 in2 in3], for use in findfn."
  [argstr]
  (apply concat
         ((juxt (comp list last) butlast)
          (with-in-str argstr
            (let [sentinel (Object.)]
              (doall
               (take-while (complement #{sentinel})
                           (repeatedly
                            #(try
                               (safe-read)
                               (catch LispReader$ReaderException _
                                 sentinel))))))))))

(defn findfn-pluginfn [f argstr]
  (try
    (let [argvec (vec (walk/postwalk-replace {'% ''%} (read-findfn-args argstr)))
          _ (sb argvec)       ; a lame hack to get sandbox
                              ; guarantees on eval-ing the user's args
          user-args (eval argvec)]
      (->> user-args (apply f) vec str trim-with-gist))
    (catch Throwable e
      (.getMessage e))))

(defplugin
  (:hook
   :on-message
   (fn [{:keys [com bot nick channel message] :as com-m}]
     (let [config (-> @bot :config)
           pre (:print-prefix config)
           box? (:box config)]
       (if-let [evalp (:eval-prefixes config)]
         (when-let [sexps (what-to-eval bot channel message)]
           (if-not (second (swap! tasks (fn [[pending]]
                                          (if (< pending 3)
                                            [(inc pending) true]
                                            [pending false]))))
             (send-message com-m "Too much is happening at once. Wait until other operations cease.")
             (on-thread
              (try
                (doseq [msg (eval-forms (if (nil? box?) true box?)
                                        (:name @com) nick
                                        pre
                                        sexps)]
                  (send-message com-m msg))
                (finally (swap! tasks (fn [[pending]]
                                        [(dec pending)])))))))
         (throw (Exception. "Dude, you didn't set :eval-prefixes. I can't configure myself!"))))))

  (:cmd
   "Link to the source code of a Clojure function or macro."
   #{"source"}
   (fn [{:keys [args] :as com-m}]
     (send-message com-m
                   (if-let [line-url (get-line-url (first args))]
                     (str (first args)  " is " line-url)
                     "Source not found."))))

  (:cmd
   "Finds the clojure fns which, given your input, produce your output."
   #{"findfn"}
   (fn [{:keys [args] :as com-m}]
     (send-message com-m (findfn-pluginfn find-fn (string/join " " args)))))

  (:cmd
   "(findarg map % [1 2 3] [2 3 4]) ;=> inc"
   #{"findarg"}
   (fn [{:keys [args] :as com-m}]
     (send-message com-m (findfn-pluginfn find-arg (string/join " " args)))))

  (:cmd
   "Search clojuredocs for something."
   #{"cd"}
   (fn [{:keys [args] :as com-m}]
     (if-let [results (take 3 (apply cd/search args))]
       (doseq [{:keys [url ns name]} results]
         (send-message com-m (format "%s/%s: %s" ns name url)))
       (send-message com-m "No results found."))))

  (:cmd
   "Find an example usage of something on clojuredocs."
   #{"examples"}
   (fn [{:keys [args] :as com-m}]
     (send-message com-m
                   (if-let [results (:examples (apply cd/examples-core args))]
                     (gist "examples.clj" (s/join "\n\n" (for [{:keys [body]} results]
                                                           (cd/remove-markdown body))))
                     "No results found.")))))
