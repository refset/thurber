(ns demo.game-stats-test
  (:require [clojure.test :refer :all]
            [thurber :as th]
            [test-support]
            [game.game-stats])
  (:import (org.apache.beam.sdk.testing PAssert)))

(def ^:private user-scores
  [["Robot-2" (int 66)]
   ["Robot-1" (int 116)]
   ["user7_AndroidGreenKookaburra" (int 23)]
   ["user7_AndroidGreenKookaburra" (int 1)]
   ["user19_BisqueBilby" (int 14)]
   ["user13_ApricotQuokka" (int 15)]
   ["user18_BananaEmu" (int 25)]
   ["user6_AmberEchidna" (int 8)]
   ["user2_AmberQuokka" (int 6)]
   ["user0_MagentaKangaroo" (int 4)]
   ["user0_MagentaKangaroo" (int 3)]
   ["user2_AmberCockatoo" (int 13)]
   ["user7_AlmondWallaby" (int 15)]
   ["user6_AmberNumbat" (int 11)]
   ["user6_AmberQuokka" (int 4)]])

(deftest test-calculate-spammy-users
  (let [output
        (-> (test-support/create-test-pipeline)
          (th/apply!
            (th/create user-scores)
            #'th/clj->kv)
          (#'game.game-stats/->spammy-users))]
    (-> (th/apply! output #'th/kv->clj)
      (PAssert/that)
      (.containsInAnyOrder ^Iterable [["Robot-2" (int 66)]
                                      ["Robot-1" (int 116)]]))
    (test-support/run-test-pipeline! output)))