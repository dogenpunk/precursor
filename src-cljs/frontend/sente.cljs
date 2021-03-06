(ns frontend.sente
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer (<! >! put! chan)]
            [cljs-time.core :as time]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.models.chat :as chat-model]
            [frontend.rtc :as rtc]
            [frontend.rtc.stats :as rtc-stats]
            [frontend.state :as state]
            [frontend.stats :as stats]
            [frontend.subscribers :as subs]
            [frontend.talaria :as tal]
            [frontend.utils :as utils :include-macros true]
            [goog.labs.dom.PageVisibilityMonitor]
            [taoensso.sente :as sente])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(defn cb-success? [reply]
  (and (sente/cb-success? reply)
       (not (:tal/error reply))))

(defn send-msg-sente [sente-state message & [timeout-ms callback-fn :as rest]]
  (if (-> sente-state :state deref :open?)
    (apply (:send-fn sente-state) message rest)
    (let [watch-id (utils/squuid)]
      ;; TODO: handle this in the handle-message fn below
      (add-watch (:state sente-state) watch-id
                 (fn [key ref old new]
                   (when (:open? new)
                     (apply (:send-fn sente-state) message rest)
                     (remove-watch ref watch-id)))))))

(defn send-msg [ws-state message & [timeout-ms callback-fn :as rest]]
  (if (:send-fn ws-state)
    (send-msg-sente ws-state message timeout-ms callback-fn)
    (tal/queue-msg ws-state {:op (first message)
                             :data (second message)} timeout-ms callback-fn)))

(defn ch-send-msg
  "Like send-msg, but takes a channel to put the reply onto."
  [sente-state message timeout-ms channel]
  (let [callback-fn #(put! channel %)]
    (send-msg sente-state message timeout-ms callback-fn)
    channel))

(defn update-server-offset [sente-state]
  (let [start (goog/now)]
    (send-msg sente-state [:server/timestamp] 1000 (fn [reply]
                                                     (when (cb-success? reply)
                                                       (let [latency (- (goog/now) start)]
                                                         (datetime/update-server-offset (:date (second reply)) latency)))))))

(defn subscribe-to-document [sente-state comms document-id & {:keys [requested-color requested-remainder]}]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id
                                              :requested-color requested-color
                                              :requested-remainder requested-remainder}]
            (* 30 1000)
            (fn [reply]
              (if (cb-success? reply)
                (put! (:api comms) [(first reply) :success (assoc (second reply)
                                                                  :context {:document-id document-id})])
                (put! (:errors comms) [:subscribe-to-document-error {:document-id document-id
                                                                     :reply reply}])))))

(defn subscribe-to-team [sente-state team-uuid]
  (send-msg sente-state [:team/subscribe {:team/uuid team-uuid}]))

(defn subscribe-to-issues [sente-state comms issue-db]
  (send-msg sente-state [:issue/subscribe {}]
            10000
            (fn [reply]
              (if (cb-success? reply)
                (let [ents (:entities reply)
                      uuids (atom #{})]
                  (walk/postwalk (fn [x]
                                   (when (map? x)
                                     (when-let [u (:issue/author x)]
                                       (swap! uuids conj u))
                                     (when-let [u (:comment/author x)]
                                       (swap! uuids conj u))
                                     (when-let [u (:vote/cust x)]
                                       (swap! uuids conj u)))
                                   x)
                                 ents)
                  (put! (:controls comms) [:new-cust-uuids {:uuids @uuids}])
                  (d/transact! issue-db ents {:server-update true}))
                (put! (:errors comms) [:subscribe-to-issues-error])))))

(defn fetch-subscribers [sente-state document-id]
  (send-msg sente-state [:frontend/fetch-subscribers {:document-id document-id}] 10000
            (fn [data]
              (put! (:ch-recv sente-state) [:chsk/recv [:frontend/fetch-subscribers data]]))))

(defmulti handle-message (fn [app-state message data]
                           (utils/mlog "handle-message" message data)
                           message))

(defmethod handle-message :default [app-state message data]
  (utils/mlog "ws message" (pr-str message) (pr-str data)))

(defmethod handle-message :datomic/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

(defmethod handle-message :team/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:team-db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

;; Hack to deal with datascript's design decisions: https://github.com/tonsky/datascript/issues/76
(defn sort-datoms [datoms]
  (let [c (fn [d1 d2]
            (compare (count (set/intersection #{:comment/parent :issue/votes :issue/comments} (set (keys d1))))
                     (count (set/intersection #{:comment/parent :issue/votes :issue/comments} (set (keys d2))))))]
    (sort c datoms)))

(defmethod handle-message :issue/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:issue-db @app-state)
                 (let [adds (->> datoms
                              (filter :added)
                              (group-by :e)
                              (map (fn [[e datoms]]
                                     (merge {:db/id e}
                                            (reduce (fn [acc datom]
                                                      (assoc acc (:a datom) (:v datom)))
                                                    {} datoms))))
                              sort-datoms)
                       retracts (remove :added datoms)]
                   (concat adds
                           (map ds/datom->transaction retracts)))
                 {:server-update true})))

(defmethod handle-message :frontend/subscriber-joined [app-state message data]
  (swap! app-state subs/add-subscriber-data (:client-id data) data))

(defmethod handle-message :frontend/subscriber-left [app-state message data]
  (let [client-id (:client-id data)]
    (swap! app-state subs/remove-subscriber client-id)
    (rtc/cleanup-conns :consumer client-id)
    (rtc/cleanup-conns :producer client-id)))

(defmethod handle-message :frontend/mouse-move [app-state message data]
  (swap! app-state subs/maybe-add-subscriber-data (:client-id data) data))

(defmethod handle-message :frontend/db-entities [app-state message data]
  (when (= (:document/id data) (:document/id @app-state))
    (d/transact! (:db @app-state)
                 (:entities data)
                 {:server-update true})))

(defmethod handle-message :team/db-entities [app-state message data]
  (when (= (:team/uuid data) (get-in @app-state [:team :team/uuid]))
    (d/transact! (:team-db @app-state)
                 (:entities data)
                 {:server-update true})
    (when (= :plan (:entity-type data))
      (put! (get-in @app-state [:comms :controls]) [:plan-entities-stored {:team/uuid (:team/uuid data)}]))))

(defmethod handle-message :issue/db-entities [app-state message data]
  (d/transact! (:issue-db @app-state)
               (:entities data)
               {:server-update true}))

(defmethod handle-message :frontend/custs [app-state message data]
  (swap! app-state update-in [:cust-data :uuid->cust] merge (:uuid->cust data)))

(defmethod handle-message :frontend/invite-response [app-state message data]
  (let [doc-id (:document/id data)
        response (:response data)]
    (swap! app-state update-in (state/invite-responses-path doc-id) conj response)))

(defmethod handle-message :frontend/subscribers [app-state message {:keys [subscribers] :as data}]
  (when (= (:document/id data) (:document/id @app-state))
    (swap! app-state #(reduce (fn [state [client-id subscriber-data]]
                                (subs/add-subscriber-data state client-id subscriber-data))
                              % subscribers))))

(defmethod handle-message :frontend/error [app-state message data]
  (put! (get-in @app-state [:comms :errors]) [(:error-key data) data])
  (utils/inspect data))

(defmethod handle-message :frontend/stats [app-state message data]
  (send-msg (:sente @app-state)
            [:frontend/stats
             {:stats (stats/gather-stats @app-state)}]))

(defmethod handle-message :frontend/refresh [app-state message data]
  (let [refresh-url (-> (url/url js/window.location)
                      (update-in [:query] merge {"x" (get-in @app-state [:camera :x])
                                                 "y" (get-in @app-state [:camera :y])
                                                 "z" (get-in @app-state [:camera :zf])})
                      str)]
    (if (or (.isHidden (goog.labs.dom.PageVisibilityMonitor.))
            (:force-refresh data))
      (set! js/window.location refresh-url)
      (chat-model/create-bot-chat (:db @app-state) @app-state [:span "We've just released some upgrades! Please "
                                                               [:a {:href refresh-url
                                                                    :target "_self"}
                                                                "click to refresh"]
                                                               " now to avoid losing any work."]))))

(defmethod handle-message :rtc/signal [app-state message data]
  (rtc/handle-signal
   (assoc data
          :comms (:comms @app-state)
          :send-msg  (fn [d]
                       (send-msg (:sente @app-state)
                                 [:rtc/signal (merge d
                                                     (select-keys @app-state [:document/id]))])))))

(defmethod handle-message :rtc/diagnostics [app-state message data]
  (send-msg (:sente @app-state) [:rtc/diagnostics (rtc-stats/gather-stats rtc/conns rtc/stream)]))

(defmethod handle-message :chsk/state [app-state message data]
  (let [state @app-state]
    (when (and (:open? data)
               (not (:first-open? data))
               (:document/id state))
      ;; TODO: This seems like a bad place for this. Can we share the same code that
      ;;       we use for subscribing from the nav channel in the first place?
      (subscribe-to-document
       (:sente state) (:comms state) (:document/id state)
       :requested-color (get-in state [:subscribers :info (:client-id state) :color])
       :requested-remainder (get-in state [:subscribers :info (:client-id state) :frontend-id-seed :remainder])))))


(defn do-something [app-state sente-state]
  (let [tap (async/chan (async/sliding-buffer 10))
        mult (:ch-recv-mult sente-state)]
    (async/tap mult tap)
    (go-loop []
      (when-let [{[type data] :event :as stuff} (<! tap)]
        (swap! (:state sente-state) assoc :last-message {:time (time/now)
                                                         :type type})
        (case type
          :chsk/recv (utils/swallow-errors
                      (let [[message message-data] data]
                        (handle-message app-state message message-data)))

          ;; :chsk/state is sent when the ws is opened or closed
          :chsk/state (utils/swallow-errors
                       (handle-message app-state type data))

          nil)
        (recur)))))

(defn handle-tal-msg [app-state msg]
  (handle-message app-state (:op msg) (:data msg)))

(defn init [app-state]
  (if (:use-talaria? @app-state)
    (let [tal-state (:tal @app-state)]
      (tal/start-recv-queue tal-state (partial handle-tal-msg app-state))
      (subs/add-recording-watcher app-state (fn [d] (send-msg tal-state [:rtc/signal (merge d {:document/id (:document/id @app-state)})]))))
    (let [{:keys [chsk ch-recv send-fn state] :as sente-state}
          (sente/make-channel-socket! "/chsk" {:type :auto
                                               :chsk-url-fn (fn [& args]
                                                              (str (apply sente/default-chsk-url-fn args) "?tab-id=" (:tab-id @app-state)))})]
      (swap! app-state assoc :sente (assoc sente-state :ch-recv-mult (async/mult ch-recv)))
      (subs/add-recording-watcher app-state (fn [d] (send-msg (:sente @app-state) [:rtc/signal (merge d {:document/id (:document/id @app-state)})])))
      (do-something app-state (:sente @app-state)))))
