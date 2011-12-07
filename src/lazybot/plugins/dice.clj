(ns lazybot.plugins.dice
  (:require [clojure.string :as string])
  (:use lazybot.registry))

(defn random-die
  [sides]
  (inc (rand-int sides)))

(defn random-dice
  ([sides] (random-die sides))
  ([sides number] (repeatedly number #(random-die sides))))

(defn parse-integer
  [s]
  (try (Integer/parseInt s)
       (catch NumberFormatException e 1)))

(defplugin
  (:cmd
   "Random dice"
   #{"dice"}
   (fn [{:keys [bot nick args irc] :as com-m}]
     (let [matches (re-find #"(\d+)d(\d+)" (first args))
           number (parse-integer (get matches 1))
           sides (parse-integer (get matches 2))
           dice (random-dice sides number)]
       (if (coll? dice)
         (send-message com-m (str "Dice result: "(string/join ", " dice) " for a total of " (reduce + dice)))
         (send-message com-m (str "Dice result: " dice))))))) 
