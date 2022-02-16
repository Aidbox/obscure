(ns obscure.tg-bot
  (:require
   [morse.handlers :as h]
   [morse.api :as t]
   [chrono.core :as chrono]
   [chrono.now :as now]
   [cheshire.core :as json]
   [obscure.jobs.core]
   [obscure.jobs.tg-wrapper :as tg-wrapper]
   [org.httpkit.client :as http]
   [clojure.string :as str]))

(def base-url "https://api.telegram.org/bot")

(def commands
  {:start (fn [ctx data] )
   :run (fn [ctx data] )
   :schedule (fn [ctx data] )})

(defn check-access [{data :tg-req :as ctx}]
  (some #(= % (get-in data [:message :chat :id])) (vals (get-in ctx [:config :telegram :chats]))))

(defn get-token [ctx]
  (get-in ctx [:config :telegram :token]))

(defn send-text [ctx text]
  (println "send")
  (t/send-text (get-in ctx [:config :telegram :token])
               (::chat-id ctx)
               {:parse_mode "Markdown"}
               text))

(defn formatted-datetime [dt]
  (let [date
        (cond
          (chrono/eq?
           (now/utc-today)
           (select-keys dt [:year :month :day]))
          "Today   "

          :else
          (let [s (chrono/format dt ^:en [:day \space [:month :short]])]
            (str s (str/join (repeat (- 8 (count s)) " ")))))

        time (chrono/format dt ^:en [:hour \: :min])]
    (str date time)))

(defn formatted-schedule [ctx]
  (str
   "```\n"
   (str/join
    "\n"
    (map
     (fn [[job-id job-cfg]]
       (str (if (get-in ctx [:jobs :schedule job-id :next-run])
              (formatted-datetime (get-in ctx [:jobs :schedule job-id :next-run]))
              "Manual       ")
            " "
            (name job-id)))
     (get-in ctx [:config :jobs])))
   "\n```"))

(defn handler [ctx data]
  (try
    (let [_ (println (get-in data [:message :chat :id]))
          ctx (assoc ctx ::chat-id (get-in data [:message :chat :id]) :tg-req data)]
      (if (str/starts-with? (get-in data [:message :text]) "/start")
        (send-text ctx (format "Chat ID: `%s`" (get-in data [:message :chat :id])))

        (if (check-access ctx)
          (let [[cmd arg] (str/split (get-in data [:message :text]) #" " 2)
                cmd (first (str/split cmd #"@"))]
            (cond
              (= "/schedule" cmd)
              (send-text ctx (formatted-schedule ctx))

              (= "/run" cmd)
              (let [job-id (str/trim (or arg ""))
                    job (get-in ctx [:config :jobs (keyword job-id)])]
                (cond
                  (empty? job-id)
                  (send-text ctx
                             (str/join "\n" (map #(str "`/run " (name %) "`")
                                                 (keys (get-in ctx [:config :jobs])))))

                  (nil? job)
                  (send-text ctx
                             (str "No such job\n"
                                  (str/join "\n" (map #(str "`/run " (name %) "`")
                                                      (keys (get-in ctx [:config :jobs]))))))

                  :else
                  (do
                    (send-text ctx (str "Start job `" job-id "`"))
                    (let [result (obscure.jobs.core/run-job ctx job-id)]
                      (prn result)
                      (if (= "error" (:status result))
                        (send-text ctx (str "Failed job `" job-id "`\n"
                                            "```\n"
                                            "Detail: "
                                            (get-in result [:ctx :error])
                                            "\n```"))
                        (send-text ctx (str "Completed job `" job-id "`")))))))

              :else
              (send-text ctx (str "Unknown command " cmd))))
          (println "Unauthorized access:" {:chat_id (get-in data [:message :chat :id])
                                           :from (get-in data [:message :from :username])}))))
    (catch Exception e
      (println "ERROR:" e))))

(defn start [*ctx]
  (if (:tg-bot @*ctx)
    :already-started
    (do
      (assert (get-in @*ctx [:config :telegram :token]) "Telegram token must be specified")
      (let [resp (json/parse-string (:body @(http/get (str base-url (get-in @*ctx [:config :telegram :token]) "/getMe"))) keyword)]
        (if (:ok resp)
          (let [sleep-period {:sleep (* 1 60 1000)}] ;; INFO: min s ms
            (swap! *ctx assoc :tg-bot (tg-wrapper/start
                                       (get-in @*ctx [:config :telegram :token]) 
                                       #(handler @*ctx %)
                                       sleep-period))
            (println (str "Telegram bot @" (get-in resp [:result :username]) " started"))
            :started)
          (println (str "Can not start telegram bot: '" (:description resp) "'")))))))

(comment
  (def t "<your-token>")

  (def sleep-period {:sleep (* 15 1000)})

  (def instance
    (tg-wrapper/start
     t
     (fn [data] (handler nil data))
     sleep-period))

  (tg-wrapper/stop instance))

(defn stop [*ctx]
  (when (:tg-bot @*ctx)
    (tg-wrapper/stop (:tg-bot @*ctx))
    (swap! *ctx dissoc :tg-bot)))

(comment

  (:username (:result (json/parse-string (:body @(http/get (str base-url "889839387:AAHqAhda_czOMg2oAMMXu8jJj_PjkU9YQDY" "/getMe"))) keyword)))

  (json/parse-string (:body @(http/get (str base-url #_"8i89839387:AAHqAhda_czOMg2oAMMXu8jJj_PjkU9YQDY" "/getMe"))) keyword)

  (let [resp (json/parse-string (:body @(http/get (str base-url "889839387:AAHqAhda_czOMg2oAMMXu8jJj_PjkU9YQDY" "/getMe"))) keyword)]
    (if (:ok resp)
      (println (str "Telegram bot @" (get-in resp [:result :username]) " started"))
      (println (str "Can not start telegram bot: '" (:description resp) "'"))))

  (stop)

  (def token "168706187:AAGv_0tDV6vWQX7jq0oAk1pUE_SDr94EtcY")




  (def handler (p/start token bot-api))


  (p/stop handler)


  (require '[clojure.tools.macro :as macro])

  (defmacro defhandler
    "Define a Telegram handler function from a sequence of handlers.
  The name may optionally be followed by a doc-string and metadata map."
    [name & routes]
    (println (first routes))
    (let [[name routes] (macro/name-with-attributes name routes)]
      (println name routes)
      `(def ~name (h/handlers ~@routes))))



  (defhandler bot-api
    "test"
    (h/command-fn "start" (fn [{{id :id :as chat} :chat}]
                            (println "wow")))

    (h/command-fn "help" (fn [{{id :id :as chat} :chat :as msg}]
                           (println "help"))))

  (require '[clojure.repl])

  (def wow "wow" "wowo")

  (clojure.repl/doc bot-api)

  bot-api

  wow



  (macro/name-with-attributes "wow" '((w) (e)))

 )
