(ns obscure.jobs.core-test
  (:require [obscure.jobs.core :as sut]
            [matcho.core :as matcho]
            [clojure.test :refer :all]))


(defmethod sut/run-step ::debug [ctx step]
  {:status (or (:msg step) "ok")})

(defmethod sut/run-step ::debug-fail [ctx step]
  (if (:exeption step)
    {:error (or (:ex-msg step) "Unexpected error")}
    (throw (ex-info (or (:ex-msg step) "Unexpected error") {}))))

(defmethod sut/run-step ::fn [ctx step]
  ((:fn step) ctx step))

(deftest ghost-jobs


  ;; safe-run-step

  (matcho/match
   (sut/safe-run-step {} {:type ::debug})
   {:status "ok"})

  (matcho/match
   (sut/safe-run-step {} {:type ::debug
                          :msg "msg!"})
   {:status "msg!"})

  (matcho/match
   (sut/safe-run-step {} {:type ::debug-fail})
   {:error "Unexpected error"})

  (matcho/match
   (sut/safe-run-step {} {:type ::debug-fail
                          :ex-msg "Fail"})
   {:error "Fail"})



  ;; run-steps

  (matcho/match
   (sut/run-steps {} [{:type ::debug
                       :msg "msg!"}])
   {:status "success"
    :last-step "1"})

  (matcho/match
   (sut/run-steps {} [{:type ::debug
                       :msg "msg 1"}
                      {:type ::debug
                       :msg "msg 2"}])
   {:status "success"
    :last-step "2"
    :ctx {:status "msg 2"}
    :steps [{:step-num "1"
             :status "success"
             :output {:status "msg 1"}}
            {:step-num "2"
             :status "success"
             :output {:status "msg 2"}}]})

  (matcho/match
   (sut/run-steps {} [{:type ::debug
                       :msg "msg 1"}
                      {:type ::debug-fail}])
   {:status "error"
    :last-step "2"
    :ctx {:status "msg 1"}
    :steps [{:step-num "1"
             :status "success"
             :output {:status "msg 1"}}
            {:step-num "2"
             :status "error"
             :output {:error "Unexpected error"}}]})


  (matcho/match
   (sut/run-steps {} [{:type ::fn
                       :fn (fn [_ _] {:msg "a"})}
                      {:type ::debug-fail
                       :exeption false
                       :ex-msg "fail"
                       :on-error [{:type ::fn
                                   :fn (fn [_ _] {:msg "b"})}
                                  {:type ::fn
                                   :fn (fn [_ _] {:msg "c"})}]}])
   {:status "error"
    :last-step "2-2"
    :ctx {:msg "c"}
    :steps [{:step-num "1"
             :status "success"
             :output {:msg "a"}}
            {:step-num "2"
             :status "error"
             :output {:error "fail"}}
            {:step-num "2-1"
             :status "success"
             :output {:msg "b"}}
            {:step-num "2-2"
             :status "success"
             :output {:msg "c"}}]})


  (def my-atom (atom #{}))

  (matcho/match
   (sut/run-steps {} [{:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "1")
                             {:msg "ok"})}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "2")
                             {:msg "ok"})}
                      {:type ::debug-fail
                       :on-error [{:type ::fn
                                   :fn (fn [_ _]
                                         (swap! my-atom conj "3-1")
                                         {:msg "ok"})}]}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "4")
                             {:msg "ok"})}])
   {:status "error"
    :last-step "3-1"
    :ctx {:msg "ok"}
    :steps [{:step-num "1"
             :status "success"
             :output {:msg "ok"}}
            {:step-num "2"
             :status "success"
             :output {:msg "ok"}}
            {:step-num "3"
             :status "error"
             :output {:error "Unexpected error"}}
            {:step-num "3-1"
             :status "success"
             :output {:msg "ok"}}]})

  (is (= #{"1" "2" "3-1"} @my-atom))


  (def my-atom (atom #{}))

  (matcho/match
   (sut/run-steps {} [{:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "1")
                             {:msg-1 "hi"})}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "2")
                             {:msg-2 "nice"})}])
   {:status "success"
    :last-step "2"
    :ctx {:msg-1 "hi"
          :msg-2 "nice"}
    :steps [{:step-num "1"
             :status "success"
             :output {:msg-1 "hi"}}
            {:step-num "2"
             :status "success"
             :output {:msg-2 "nice"}}]})

  (is (= #{"1" "2"} @my-atom))

  (def my-atom (atom #{}))

  (matcho/match
   (sut/run-steps {} [{:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "1")
                             {::sut/status "success"})}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "2")
                             {::sut/status "success"})}
                      {:type ::debug-fail}])
   {:status "error"
    :last-step "3"})

  (is (= #{"1" "2"} @my-atom))


  (def my-atom (atom #{}))

  (matcho/match
   (sut/run-steps {} [{:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "1")
                             {::sut/status "success"})}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "2")
                             {::sut/status "success"})}
                      {:type ::debug-fail}
                      {:type ::fn
                       :fn (fn [_ _]
                             (swap! my-atom conj "3")
                             {::sut/status "success"})}])
   {:status "error" :last-step "3"})

  (is (= #{"1" "2"} @my-atom))

  (matcho/match
   (sut/run-steps {:config {:data "data"}} [{:type ::fn
                                             :fn (fn [ctx step] 42)}])
   {:status "error" :last-step "1"})

  (matcho/match
   (sut/run-steps {:config {:data "data"}} [{:type ::fn
                                             :fn (fn [ctx step] nil)}])
   {:status "success" :last-step "1"})

  (matcho/match (sut/run-steps {:config {:data "data"}}
                               [{:type ::fn
                                 :fn (fn [_ _] {:answer "yes"})}
                                {:type ::fn
                                 :fn (fn [ctx _] (when-not (= 42 (:answer (:job/ctx ctx)))
                                                   {:error "fail"}))}])
                {:status "error" :last-step "2" :ctx {:answer "yes"}})

  (matcho/match (sut/run-steps {:config {:data "data"}}
                               [{:type ::fn
                                 :fn (fn [_ _] {:answer 42})}
                                {:type ::fn
                                 :fn (fn [_ _] nil)}
                                {:type ::fn
                                 :fn (fn [ctx _] (when-not (= 42 (:answer (:job/ctx ctx)))
                                                   {:error "fail"
                                                    :detail ctx}))}])
                {:status "success" :ctx {:answer 42}})



  ;; run-job

  (matcho/match
   (sut/run-job
    {:config
     {:jobs
      {:my-job
       {:hash -120
        :steps [{:type ::debug :out "ok"}]}}}}
    :my-job)
   {:status "success"
    :last-step "1"})

  (matcho/match
   (sut/run-job
    {:config
     {:jobs
      {:my-job
       {:hash -120
        :steps [{:type ::debug :out "ok"}]}}}}
    :my-job)
   {:status "success"
    :last-step "1"})










  )

(deftest tg-step-test


  ;; (matcho/match
  ;;  (sut/run-steps {:config
  ;;                  {:telegram
  ;;                   {:token """
  ;;                    :chats {:default 0
  ;;                            :another 1}}} }
  ;;                 [{:type :tg
  ;;                   :silent "true"
  ;;                   :send "test 1"}
  ;;                  {:type :tg
  ;;                   :silent "true"
  ;;                   :update "update!"}])
  ;;  {:status "success"})

  ;; (matcho/match
  ;;  (sut/run-steps {:config
  ;;                  {:telegram
  ;;                   {:token ""
  ;;                    :chats {:default 0
  ;;                            :another 1}}} }
  ;;                 [{:type :tg
  ;;                   :silent "true"
  ;;                   :chat "another"
  ;;                   :send "test 2"}
  ;;                  {:type :tg
  ;;                   :silent "true"
  ;;                   :chat "another"
  ;;                   :update "update!"}])
  ;;  {:status "success"})

  )

(deftest bash-step-test

  ;; (def ctx {:k8s (k8s/default-ctx)
  ;;           :config {:instances
  ;;                    {:prod-pg {:kind "Pod"
  ;;                               :namespace "x12box"
  ;;                               :name "x12box-pg-0"
  ;;                               :container "postgres"}
  ;;                     :pg-deployment {:kind "Deployment"
  ;;                                     :namespace "x12box"
  ;;                                     :name "x12box"}}}})

  ;; (matcho/match
  ;;  (sut/run-steps ctx
  ;;                 [{:type "bash"
  ;;                   :instance "prod-pg"
  ;;                   :script "echo ok"}])
  ;;  {:status "success" :ctx {:stdout "ok" :exit-code 0}})


  ;; (matcho/match
  ;;  (sut/run-steps ctx
  ;;                 [{:type "bash"
  ;;                   :instance "prod-pg"
  ;;                   :script "echo fail &&  exit 1"}])
  ;;  {:status "error" :ctx {:stdout "fail" :exit-code 1}})


  ;; (matcho/match
  ;;  (sut/run-steps ctx
  ;;                 [{:type "bash"
  ;;                   :instance "pg-deployment"
  ;;                   :script "echo nice"}])
  ;;  {:status "success" :ctx {:stdout "nice" :exit-code 0}})


  )
