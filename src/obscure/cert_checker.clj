(ns obscure.cert-checker
  (:import [java.net URL])
  (:require [chrono.crono :as crono]
            [obscure.jobs.core]
            [clojure.string :as str]))


(defn get-cn [cert]
    (let [namings (str/split (.getName (.getSubjectDN cert)) #",")
          sert-name (or (first (filter #(str/starts-with? % "CN=") namings)) "")]
      (second (str/split sert-name #"=" 2))))

(defn check-cert [url]
  (try
    (let [url (new URL url)
          conn (.openConnection url)
          _ (.connect conn)
          certs (seq (.getServerCertificates conn))]
      (mapv (fn [cert] {:cn (get-cn cert)
                        :expire-in-days (quot (- (.getTime (.getNotAfter cert))
                                                 (.getTime (new java.util.Date)))
                                              (* 1000 60 60 24))})
            certs))
    (catch Exception e
      {:error (str "Something went wrong with checking certificate for " url)
       :msg (.getMessage e)})))

(defmethod obscure.jobs.core/run-step :ssl-cert-checker [ctx step]
  (prn :ssl-cert-checker step)
  (let [left-days (or (:fail-when-expire-in step) 10)
        result (check-cert (str "https://" (:domain step)))]
    (cond
      (:error result)
      result

      (>= left-days (:expire-in-days (first result)))
      {:error (str "SSL cert expire in " (:expire-in-days (first result)) " days for https://" (:domain step))}

      :else
      nil)))

(comment

  (check-cert "https://health-samurai.io")


  )
