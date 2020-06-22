(ns obscure.core
  (:require [k8s]
            [clj-yaml.core :as yaml]
            [clojure.java.shell :as shell]
            [obscure.jobs.core]
            [obscure.tg-bot]
            [obscure.cert-checker]
            [clojure.string :as str]
            [chrono.core :as chrono]
            [chrono.now :as now])
  (:gen-class))

(defn env
  ([v default]
   (if-let [env-value (-> v (name)
                          (str/upper-case)
                          (str/replace #"-" "_")
                          (System/getenv))]
     env-value
     default))
  ([v]
   (if-let [env-value (-> v (name)
                          (str/upper-case)
                          (str/replace #"-" "_")
                          (System/getenv))]
     env-value
     (throw (ex-info (str v " must be specified") {})))))

(defn start [cfg]
  (let [*ctx (atom {:config cfg})
        tg-bot-specified (get-in cfg [:telegram :token])]
    (let [obscure-data (get cfg :data-dir)]
      (if (or (nil? obscure-data) (not (zero? (:exit (shell/sh "mkdir" "-p" obscure-data)))))
        (throw (ex-info (str "Can not create dir '" obscure-data "'") {}))
        (println "Created obscure-data dir" obscure-data)))
    (swap! *ctx assoc :k8s (k8s/sa-ctx (get-in cfg [:k8s :base]) (get-in cfg [:k8s :token])))
    (if tg-bot-specified (obscure.tg-bot/start *ctx))
    (obscure.jobs.core/start *ctx)
    *ctx))


(defn stop [*ctx]
  (obscure.tg-bot/stop *ctx)
  (obscure.jobs.core/stop *ctx))


(defn -main [& args]
  (println "Starting obscure")
  (let [obscure-data (env :obscure-data "/tmp/obscure")
        obscure-config (yaml/parse-string (slurp (env :obscure-config)))
        cfg (-> obscure-config
                (assoc-in [:k8s :base] (or (env :kube-base nil) (env :kubernetes-service-host)))
                (assoc-in [:k8s :token] (or (env :kube-token nil) (slurp "/var/run/secrets/kubernetes.io/serviceaccount/token")))
                (assoc :data-dir obscure-data)
                (assoc-in [:telegram :token] (env :telegram-token nil)))]
    (start cfg)))


(comment

  (def ctx (start
            {:k8s (let [k8s-ctx (k8s/default-ctx)]
                    {:token (subs (get-in k8s-ctx [:req-ops :headers "Authorization"]) 7)
                     :base (:base k8s-ctx)})
             ;; :telegram {}
             :data-dir "/tmp/obscure"
             :jobs {:my-job {:when {:every :min
                                    :at (->> (range 6)
                                             (map #(* % 10))
                                             (map (fn [sec] {:sec sec})))}
                             :hash 123 :steps [{:type ::debug :out "10 sec left"}]}
                    :my-second-job {:when {:every :hour
                                           :at (->> (range 12)
                                                    (map #(* % 5))
                                                    (map (fn [sec] {:min sec})))}
                                    :hash 124 :steps [{:type ::debug :out "5 min left"}]}
                    :ssl {:steps [{:type :ssl-cert-checker
                                   :domain "health-samurai.io"
                                   ;; :fail-when-expire-in 10
                                   :on-error [{:type :tg
                                               :send "SSL check failed for phyl-stage.aidbox.io"}]}]}
                    :disabled-job {:steps [{:type :tg
                                            :send "Hello"}]}}}))

  (stop ctx)




  )
