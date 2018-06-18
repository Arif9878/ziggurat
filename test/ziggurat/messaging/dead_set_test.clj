(ns ziggurat.messaging.dead-set-test
  (:require [clojure.test :refer :all]
            [ziggurat.fixtures :as fix]
            [ziggurat.messaging.dead-set :as ds]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.util.rabbitmq :as rmq]))

(use-fixtures :once fix/init-rabbit-mq)

(deftest replay-test
  (testing "puts message from dead set to instant queue"
    (fix/with-clear-data
      (let [count-of-messages 10
            message           {:foo "bar"}
            pushed-message    (doseq [counter (range count-of-messages)]
                                (producer/publish-to-dead-queue message))]
        (ds/replay count-of-messages)
        (doseq [n (range count-of-messages)]
          (is (= message (rmq/get-msg-from-instant-queue))))
        (is (not (rmq/get-msg-from-instant-queue)))))))