(ns obscure.jobs.tg-wrapper
  (:require
   [cheshire.core :as json]
   [org.httpkit.client :as http]))


(defn task
  [token handler]
  (prn "Task started...")
  (let [m @(http/get (str "https://api.telegram.org/bot" token "/getUpdates"))
        m* (json/parse-string (:body m) true)]
    (mapv #(handler %) (:result m*)))
  (prn "Task finished."))


(defn make-task
  [flag settings token handler]
  (let [{:keys [sleep]} settings]
    (future
      (while @flag
        (try
          (task token handler)
          (catch Throwable e
            (prn "Exception occured" {:src "make-task in tg_wrapper.clj"
                                      :exception e}))
          (finally
            (Thread/sleep sleep)))))))


(defn start
  [token handler sleep-period]
  (let [flag (atom true)
        task (make-task flag sleep-period token handler)]
    {:flag flag
     :task task}))


(defn stop
  [state-and-options]
  (let [{:keys [flag task]} state-and-options]
    (reset! flag false)
    (while (not (realized? task))
      (prn "Waiting for the task to complete")
      (Thread/sleep 3000))))


(comment
  @(http/get "https://api.telegram.org/bot<your-token>/getMe")

  (def m
    {:opts {:method :get,
            :url "https://api.telegram.org/bot<your-token>/getMe"},
     :body "{\"ok\":true,
        \"result\":{\"id\":5114841682,\"is_bot\":true,\"first_name\":\"...\",\"username\":\"..._bot\",\"can_join_groups\":true,\"can_read_all_group_messages\":false,\"supports_inline_queries\":false}}",
     :headers {:access-control-expose-headers "Content-Length,Content-Type,Date,Server,Connection", :date "Tue, 15 Feb 2022 12:39:58 GMT", :server "nginx/1.18.0", :content-length "187", :strict-transport-security "max-age=31536000; includeSubDomains; preload", :content-type "application/json", :access-control-allow-methods "GET, POST, OPTIONS", :access-control-allow-origin "*", :connection "keep-alive"},
     :status 200})


  (json/parse-string (:body m) true)

  {:ok true,
   :result {:id 5114841682,
            :is_bot true,
            :first_name "...",
            :username "...",
            :can_join_groups true,
            :can_read_all_group_messages false,
            :supports_inline_queries false}}


  (def m1 @(http/get "https://api.telegram.org/bot<your-token>/getUpdates"))
  (json/parse-string (:body m1) true)

  {:ok true,
   :result [{:update_id 130552143,
             :message {:message_id 2,
                       :from {:id 140718758,
                              :is_bot false,
                              :first_name "Ivan",
                              :username "...",
                              :language_code "en"},
                       :chat {:id 140718758,
                              :first_name "Ivan",
                              :username "...",
                              :type "private"},
                       :date 1644929035,
                       :text "genki?"}}]}


  {:data {:update_id 130552142,
          :message {:message_id 1,
                    :from {:id 140718758,
                           :is_bot false,
                           :first_name "Ivan",
                           :username "...",
                           :language_code "en"},
                    :chat {:id 140718758,
                           :first_name "Ivan",
                           :username "...",
                           :type "private"},
                    :date 1644928110,
                    :text "/start",
                    :entities [{:offset 0, :length 6, :type "bot_command"}]}}})