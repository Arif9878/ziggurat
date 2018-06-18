(ns ziggurat.messaging.consumer-test
  (:require [clojure.test :refer :all])
  (:require [langohr.channel :as lch]
            [ziggurat.config :refer [ziggurat-config]]
            [ziggurat.fixtures :as fix]
            [ziggurat.messaging.connection :refer [connection]]
            [ziggurat.messaging.consumer :refer [get-dead-set-messages start-subscriber* close]]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.retry :as retry]))

(use-fixtures :once fix/init-rabbit-mq)

(defn- gen-msg []
  {:gen-key (apply str (take 10 (repeatedly #(char (+ (rand 26) 65)))))})

(deftest get-dead-set-messages-test
  (testing "when ack is enabled, get the dead set messages and remove from dead set"
    (fix/with-clear-data
      (let [count-of-messages 10
            message           (gen-msg)
            pushed-message    (doseq [counter (range count-of-messages)]
                                (producer/publish-to-dead-queue message))
            dead-set-messages (get-dead-set-messages count-of-messages true)]
        (is (= (repeat count-of-messages message) dead-set-messages))
        (is (empty? (get-dead-set-messages count-of-messages true))))))

  (testing "when ack is disabled, get the dead set messages and not remove from dead set"
    (fix/with-clear-data
      (let [count-of-messages 10
            message           (gen-msg)
            pushed-message    (doseq [counter (range count-of-messages)]
                                (producer/publish-to-dead-queue message))
            dead-set-messages (get-dead-set-messages count-of-messages false)]
        (is (= (repeat count-of-messages message) dead-set-messages))
        (is (= (repeat count-of-messages message) (get-dead-set-messages count-of-messages false)))))))

(defn- mock-mapper-fn [{:keys [retry-counter-atom
                               retry-limit
                               skip-promise
                               success-promise] :as opts}]
  (fn [message]
    (cond (< @retry-counter-atom (or retry-limit 5))
          (do (when retry-counter-atom (swap! retry-counter-atom inc))
              :retry)

          (= (:msg message) "skip")
          (do (when skip-promise (deliver skip-promise true))
              :skip)

          :else
          (do (when success-promise (deliver success-promise true))
              :success))))

(defn- block-and-retry-until [success-fn]
  (try
    (retry/with-retry {:count 5 :wait 1000} (success-fn))
    (catch Throwable e
      (println (.getMessage e)))))

(deftest test-retries
  (testing "when retry is enabled the mapper-fn should be retried until return success"
    (fix/with-clear-data
      (let [retry-counter       (atom 0)
            success-promise     (promise)
            msg                 (gen-msg)
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly 5))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-subscriber* rmq-ch (mock-mapper-fn {:retry-counter-atom retry-counter
                                                     :retry-limit        2
                                                     :success-promise    success-promise}))

          (producer/publish-to-delay-queue msg)

          (when-let [promise-success? (deref success-promise 5000 :timeout)]
            (is (not (= :timeout promise-success?)))
            (is (= true promise-success?))
            (is (= 2 @retry-counter)))

          (close rmq-ch)))))

  (testing "when retry is enabled the mapper-fn should not be retried if it returns skip"
    (fix/with-clear-data
      (let [retry-counter       (atom 0)
            skip-promise        (promise)
            msg                 (assoc (gen-msg) :msg "skip")
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly 5))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-subscriber* rmq-ch (mock-mapper-fn {:retry-counter-atom retry-counter
                                                     :skip-promise       skip-promise
                                                     :retry-limit        -1}))

          (producer/publish-to-delay-queue msg)

          (when-let [promise-success? (deref skip-promise 5000 :timeout)]
            (is (not (= :timeout promise-success?)))
            (is (= true promise-success?))
            (is (= 0 @retry-counter)))

          (close rmq-ch)))))

  (testing "when retry is enabled the mapper-fn should be retried with the maximum specified times"
    (fix/with-clear-data
      (let [retry-counter       (atom 0)
            retries             5
            no-of-msgs          2
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly retries))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-subscriber* rmq-ch (mock-mapper-fn {:retry-counter-atom retry-counter
                                                     :retry-limit        (* no-of-msgs 10)}))

          (dotimes [_ no-of-msgs]
            (producer/retry (gen-msg)))

          (block-and-retry-until (fn []
                                   (let [dead-set-msgs (count (get-dead-set-messages no-of-msgs false))]
                                     (if (< dead-set-msgs no-of-msgs)
                                       (throw (ex-info "Dead set messages were never populated"
                                                       {:dead-set-msgs dead-set-msgs}))))))

          (is (= (* (inc retries) no-of-msgs) @retry-counter))
          (is (= no-of-msgs (count (get-dead-set-messages no-of-msgs false)))))

        (close rmq-ch)))))