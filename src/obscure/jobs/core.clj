(ns obscure.jobs.core
  (:require [morse.api :as t]
            [k8s]
            [chrono.core :as chrono]
            [chrono.now]
            [chrono.crono :as crono]
            [clojure.string :as str]
            [chrono.now :as now]))


(defmulti run-step (fn [ctx {type :type}] (keyword type)))

(defmethod run-step :default [ctx {type :type :as step}]
  (prn :default step)
  {:error (str "Unknown step type " type)})


(defmethod run-step :tg [ctx step]
  (prn :tg step)
  (let [token (get-in ctx [:config :telegram :token])
        chat (keyword (:chat step "default"))
        chatid (or (:obscure.tg-bot/chat-id ctx) (get-in ctx [:config :telegram :chats chat]))]
    (cond
      (:send step)
      (let [resp (t/send-text token
                              chatid
                              {:parse_mode "Markdown"
                               :disable_notification (= "true" (str (:silent step)))}
                              (:send step))]
        {:tg-msg resp})

      (:update step)
      (let [msg-id (get-in ctx [:job/ctx :tg-msg :result :message_id])]
        (cond
          (nil? msg-id)
          {:error "No msg-id for update telegram message"
           :detail {:msg (:tg-msg ctx)}}

          (not=
           chatid
           (get-in ctx [:job/ctx :tg-msg :result :chat :id]))
          {:error "Can't update msg in another chat"
           :detail {:msg (:tg-msg ctx)
                    :chatid chatid}}

          :else
          {:tg-msg (t/edit-text token
                              chatid
                              msg-id
                              {:parse_mode "Markdown"
                               :disable_notification (= "true" (str (:silent step)))}
                              (:update step))})))))


(defmethod run-step :sleep [_ {sec :sec}]
  (println "sleep for" sec "seconds")
  (Thread/sleep (* 1000 sec)))

(comment



  (do
    (def msg
      (run-steps {:config
                  {:telegram
                   {:token "168706187:AAGv_0tDV6vWQX7jq0oAk1pUE_SDr94EtcY"
                    :chats {:default -1001252614964
                            :another -328876518}}} }
                 [{:type :tg
                   :silent "true"
                   :chat "another"
                   :send "text"}
                  #_{:type :sleep
                   :sec 1}
                  {:type :tg
                   :silent "true"
                   :chat "another"
                   :update "update!"}]))
    msg)



  (::msg msg)


  (t/edit-text token
               chatid
               1273
               {:parse_mode "Markdown"
                :disable_notification true}
               "update!")

  msg

  (get-in msg [:result :message_id])



  )

(defn resolve-pod [k8s-ctx {:keys [kind namespace name container]}]
  (try
    (assert namespace "namespace must be specified")
    (assert name "name must be specified")
    (cond
      (= "Pod" kind)
      (let [_ (println{:kind "Pod"
                       :apiVersion "v1"
                       :metadata {:name name
                                  :namespace namespace}})
            pod (k8s/get-resource k8s-ctx {:kind "Pod"
                                           :apiVersion "v1"
                                           :metadata {:name name
                                                      :namespace namespace}})]
        (println pod)
        (assert (= namespace (get-in pod [:metadata :namespace]))
                (str "Pod from another namespace [expected=" namespace
                     ", got=" (get-in pod [:metadata :namespace])"]"))
        (let [name (get-in pod [:metadata :name])
              container (or container (get-in pod [:spec :containers 0 :name]))]
          (assert (contains? (set (map :name (get-in pod [:spec :containers]))) container)
                  (str "No container " container " in pod " namespace  "/" name))
          {:namespace namespace
           :pod-name name
           :container container}))

      (= "Deployment" kind)
      (let [deploy (k8s/get-resource k8s-ctx {:kind "Deployment"
                                              :apiVersion "extensions/v1beta1"
                                              :metadata {:name name
                                                         :namespace namespace}})
            _ (assert (some? deploy) (str "Deployment " namespace "/" name " not found"))
            pod (get-in (k8s/get-resources k8s-ctx {:kind "Pod"
                                                    :apiVersion "v1"
                                                    :metadata {:labels (get-in deploy [:spec :selector :matchLabels])
                                                               :namespace namespace}})
                        [:items 0])]
        (assert (= namespace (get-in pod [:metadata :namespace]))
                (str "Pod from another namespace [expected=" namespace
                     ", got=" (get-in pod [:metadata :namespace])"]"))
        (let [name (get-in pod [:metadata :name])
              container (or container (get-in pod [:spec :containers 0 :name]))]
          (assert (contains? (set (map :name (get-in pod [:spec :containers]))) container)
                  (str "No container " container " in pod " namespace  "/" name))
          {:namespace namespace
           :pod-name name
           :container container})))
    (catch Exception e
      (println "ERROR:" e)
      (throw e))))

(defmethod run-step :bash [ctx step]
  (prn :bash step)
  (if-let [{:keys [namespace pod-name container] :as instance}
           (resolve-pod (:k8s ctx) (get-in ctx [:config :instances (keyword (:instance step))]))]
    (cond
      (nil? (:script step))
      {:error "Script not specified"}

      :else
      (do
        (prn :run-script instance)
        (let [result (k8s/exec-bash (:k8s ctx) namespace pod-name container (:script step))]
          (cond-> (assoc result :instance instance)
            (not (zero? (:exit-code result)))
            (assoc :error (str "Exit code " (:exit-code result)))))))
    {:error (str "Instance " (:instance step) " not found")}))


(comment


  (run-steps {:k8s (k8s/default-ctx)
              :config {:instances {:prod-pg {:type "pod"
                                             :namespace "x12box1"
                                             :pod-name "x12box-pg-0"
                                             :container "postgres"}}}}
             [{:type "bash"
               :instance "prod-pg"
               :script "echo ok"}])


  )

(defn safe-run-step [ctx step]
  (try
    (run-step ctx step)
    (catch Exception e
      (println "ERROR:" e)
      {:error (.getMessage e)})))

(defn run-steps [ctx steps]
  (let [result (reduce
                (fn [ctx step]
                  ;; (println (::step-prefix ctx))
                  (cond
                    (= "error" (::status ctx))
                    ctx

                    :else
                    (let [step-result (safe-run-step ctx step)
                          step-num (str (::step-prefix ctx) (::step-num step))]
                      (if (some? (:error step-result))
                        (if (some? (:on-error step))
                          (assoc (run-steps (-> ctx
                                                (update ::steps conj {:step-num step-num
                                                                      :status "error"
                                                                      :output step-result})
                                                (update ::step-prefix #(str % (::step-num step) "-")))
                                            (:on-error step))
                                 ::status "error"
                                 ::unwraped? true)

                          (-> ctx
                              (update :job/ctx merge step-result)
                              (update ::steps conj {:step-num step-num
                                                    :status "error"
                                                    :output step-result})
                              (assoc ::status "error"
                                     :msg (:error step-result)
                                     :detail (:detail step-result)
                                     ::last-step (str (::step-prefix ctx) (::step-num step)))))
                        (try
                          (-> ctx
                              (update :job/ctx merge step-result)
                              (update ::steps conj {:step-num step-num
                                                    :status "success"
                                                    :output step-result})
                              (assoc ::status "success"
                                     ::last-step step-num))
                          (catch Exception e
                            (println "ERROR:" e)
                            {::status "error"
                             :msg (.getMessage e)
                             ::last-step (str (::step-prefix ctx) (::step-num step))}))))))
                (assoc ctx ::steps (or (::steps ctx) []))
                (map-indexed #(assoc %2 ::step-num (inc %1)) steps))]
    (if (::unwraped? result)
      (-> result
          (dissoc ::unwraped? ::status)
          (assoc :status (::status result)))
      {:status (::status result)
       :last-step (::last-step result)
       :ctx (:job/ctx result)
       :steps (::steps result)})))



(defn run-job [ctx job-id]
  "Runs job and store result to history table"
  (println "run job" job-id)
  (let [job (get-in ctx [:config :jobs (keyword job-id)])]
    (cond
      (nil? job)
      {:status "error"
       :ctx {:error (str "Not found job " job-id)}}

      :else
      (let [result (assoc (run-steps ctx (:steps job))
                          :job-id job-id
                          :job-hash (:hash job))]
        ;; save result
        (println result)
        result
        ))))

(defn reschedule-jobs [*ctx]
  (let [ctx @*ctx
        jobs-schedule
        (reduce (fn [jobs [k v]]
                  (cond-> jobs
                    (and (not (:disabled v)) (:when v))
                    (assoc k {:next-run (crono/next-time (:when v))})))
                {}
                (get-in ctx [:config :jobs]))]
    (swap! *ctx assoc-in [:jobs :schedule] jobs-schedule)))


;; FIXME: should start asynchronously
(defn job-watcher [*ctx]
  (while (= :active (get @*ctx ::job-watcher-status))
    (let [ctx @*ctx]
      (doseq [[job-id cfg] (get-in ctx [:jobs :schedule])]
        (try
          (when (chrono/>= (chrono.now/utc) (:next-run cfg))
            (run-job ctx job-id)
            (swap! *ctx assoc-in [:jobs :schedule job-id :next-run] (crono/next-time (get-in ctx [:config :jobs job-id :when]))))
          (catch Exception e
            (println "ERROR:" e)))))
    (Thread/sleep 1000))
  (swap! *ctx dissoc ::job-watcher-status))

(defn start [*ctx]
  (if (= :active (get @*ctx ::job-watcher-active?))
    :already-started

    (do
      (reschedule-jobs *ctx)
      (swap! *ctx
             assoc
             ::job-watcher (future (job-watcher *ctx))
             ::job-watcher-status :active)
      :started)))

(defn stop [*ctx]
  (when (::job-watcher-status @*ctx)
    (swap! *ctx assoc ::job-watcher-status :stopping))
  (while (= :stopping (::job-watcher-status @*ctx))
    (println "Waiting until job watcher stops")
    (Thread/sleep 500))
  (println "Job watcher stopped")
  :stopped)




(comment

  (job-watcher ctx)

  (::job-watcher-status @ctx)


  (->> (range 12) (map #(* % 5) ) (map (fn [sec] {:sec sec})))

  (def ctx (atom {:config {:jobs {:my-job {:when {:every :min
                                                  :at (->> (range 6)
                                                           (map #(* % 10))
                                                           (map (fn [sec] {:sec sec})))}
                                           :hash 123 :steps [{:type ::debug :out "10 sec left"}]}
                                  :my-second-job {:when {:every :min
                                                         :at (->> (range 12)
                                                                  (map #(* % 5))
                                                                  (map (fn [sec] {:sec sec})))}
                                                  :hash 124 :steps [{:type ::debug :out "5 sec left"}]}
                                  :disabled-job {:disabled true :steps []}}}}))

  (ch)

  (crono/now? (chrono.now/utc) {:every :hour})





  (watch)


  (run-job {:config {:jobs {:my-job {:hash -120 :steps [{:type ::debug :out "ok"}]}}}}
           :my-job)





  (defn transform-text [ctx s]
    (let [[f & rst] (str/split s #"\{\{")]
      (str/join
       (map (fn [s] (let [[f & rst] (str/split s #"}}")]
                      (str/join (conj rst (get-in ctx (map keyword (str/split (str/trim f) #"\.")))))))
            rst))))

  (= "vlad answered 42"
     (transform-text
      {:answer 42
       :user {:name "vlad"}}
      "{{user.name }} answered {{ answer}}"))

  (= "vlad"
     (transform-text
      {:answer 42
       :user {:name "vlad"}}
      "{{user.name}}"))


  ;; send: ```{{bash.stdout}} ```

  ()

  (get-in
   {:bash [{:stdout "ok" :exit-code 0}]}
   (map keyword (str/split (str/trim "  bash.0.stdout  ") #"\.")))


  (def token "168706187:AAGv_0tDV6vWQX7jq0oAk1pUE_SDr94EtcY")

  (def chatid -1001252614964)










  (= {:status "ok"}
     (run-step {} {:type "debug"}))

  (=
   (run-step {} {:type "test"
                 :script "echo ok"}))


  (def steps [{:type "tg"
               :send "Hello"}
              {:type "bash"
               :script "echo hello"}])




  {:kind "Deployment"
   :namespace "x12box"
   :name "x12box-pg"
   :container "postgres"}

  (k8s/exec-bash (k8s/default-ctx) "echo nice")


  ;; /run job-id
  ;; /schedule
  ;; /reschedule job-id
  ;; /re-schedule-all



  )





(comment

  (def d
    (resolve-pod (k8s/default-ctx)
                 {:kind "Deployment"
                  :namespace "x12box"
                  :container "cluster"
                  :name "x12box"}))


  d


  (get-in d [:spec :selector :matchLabels])

  (reduce (fn [qs [k v]] ()))

  (=
   (str "labelSelector="
        (str/join "%2C"
                  (map (fn [[k v]] (str (name k) "%3D" v))
                       {:app "obscure"
                        :env "prod"})))

   "labelSelector=app%3Dobscure%2Cenv%3Dprod")

  (count
   (:items
    (k8s/get-resources (k8s/default-ctx) {:kind "Pod"
                                          :apiVersion "v1"
                                          :metadata {:labels {:app "x12box"}
                                                     :namespace "x12box"}})))


  (k8s/get-resource (k8s/default-ctx) {:kind "Pod"
                                       :apiVersion "v1"
                                       :metadata {:name "x12box-pg-0"
                                                  :namespace "x12box"}})




  (resolve-pod (k8s/default-ctx)
               {:kind "Pod"
                :namespace "x12box"
                :name "x12box-pg-0"})


  (def p
    (resolve-pod (k8s/default-ctx)
                 {:kind "Pod"
                  :namespace "x12box"
                  :name "x12box-pg-0"
                  :container "postgres"}))

  ;; (let [container "pg"]
  ;;   (let [container (or container (get-in p [:data :spec :containers 0 :name]))]
  ;;     (assert (contains? (set (map :name (get-in p [:data :spec :containers]))) container)
  ;;             (str "No container " container " in pod" (get-in p [:data :metadata :namespace]) "/" (get-in p [:data :metadata :name])))
  ;;     container))







  p)
