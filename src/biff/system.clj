(ns biff.system
  (:require
    [biff.util :as bu]
    [biff.util.http :as bu-http]
    [biff.util.static :as bu-static]
    [biff.auth :as auth]
    [biff.schema :as schema]
    [byte-transforms :as bt]
    [clojure.set :as set]
    [crux.api :as crux]
    [taoensso.sente :as sente]
    [ring.middleware.session.cookie :as cookie]
    [ring.middleware.anti-forgery :as anti-forgery]
    [taoensso.sente.server-adapters.immutant :refer [get-sch-adapter]]
    [biff.util.crux :as bu-crux]
    [taoensso.timbre :refer [log spy]]
    [trident.util :as u]))

(defn set-defaults [sys instance-ns]
  (let [sys (merge sys (bu/select-ns-as sys instance-ns 'biff))
        {:biff/keys [dev app-ns host rules]
         :keys [biff.auth/send-email biff.web/port biff.static/root]
         :or {port 8080 app-ns instance-ns}} sys
        root (or root (str "www/" host))]
    (assert (some? host))
    (merge
      {:biff.crux/topology :jdbc
       :biff.crux/storage-dir (str "data/" app-ns "/crux-db")
       :biff.crux.jdbc/dbname app-ns
       :biff.web/port 8080
       :biff/base-url (if (= host "localhost")
                        (str "http://localhost:" port)
                        (str "https://" host))
       :biff.static/root root
       :biff.static/resource-root (str "www/" app-ns)
       :biff.handler/secure-defaults true
       :biff.handler/not-found-path (str root "/404.html")
       :biff.handler/roots (if dev
                             ["www-dev" root]
                             [root])}
      sys
      {:biff/rules (merge rules schema/rules)}
      (when dev
        {:biff.crux/topology :standalone
         :biff.handler/secure-defaults false}))))

(defn start-crux [sys]
  (let [opts (-> sys
               (bu/select-ns-as 'biff.crux 'crux)
               (set/rename-keys {:crux/topology :topology
                                 :crux/storage-dir :storage-dir}))
        node (bu-crux/start-node opts)]
    (-> sys
      (assoc :biff/node node)
      (update :trident.system/stop conj #(.close node)))))

(defn start-sente [{:keys [biff/event-handler] :as sys}]
  (let [{:keys [ch-recv send-fn connected-uids
                ajax-post-fn ajax-get-or-ws-handshake-fn]}
        (sente/make-channel-socket! (get-sch-adapter) {:user-id-fn :client-id})
        sente-route ["/api/chsk" {:get ajax-get-or-ws-handshake-fn
                                  :post ajax-post-fn
                                  :middleware [anti-forgery/wrap-anti-forgery]
                                  :name ::chsk}]]
    (-> sys
      (update :biff/routes conj sente-route)
      (assoc
        :biff/send-event send-fn
        :biff.sente/ch-recv ch-recv
        :biff.sente/connected-uids connected-uids))))

(defn start-tx-pipe [{:keys [biff/node biff.sente/connected-uids] :as sys}]
  (let [last-tx-id (bu-crux/with-tx-log [log {:node node}]
                     (atom (:crux.tx/tx-id (last log))))
        subscriptions (atom {})
        sys (assoc sys :biff.crux/subscriptions subscriptions)
        notify-tx-opts (-> sys
                         (merge (bu/select-ns-as sys 'biff nil))
                         (assoc :last-tx-id last-tx-id))
        {:keys [f close]} (bu/pipe-fn
                            (fn [opts]
                              (bu/fix-stdout
                                (log :debug "submitting tx" (:tx opts))
                                (try
                                  (update opts :tx #(crux/submit-tx node %))
                                  (catch Throwable e
                                    (log :error e "Error while submitting tx")
                                    nil))))
                            #(bu/fix-stdout
                               (when (some? %)
                                 (log :debug "tx submitted" (:tx %))
                                 (bu-crux/notify-tx (merge % notify-tx-opts)))))]
    (add-watch connected-uids ::rm-subs
      (fn [_ _ old-uids new-uids]
        (let [disconnected (set/difference (:any old-uids) (:any new-uids))]
          (when (not-empty disconnected)
            (apply swap! subscriptions dissoc disconnected)))))
    (-> sys
      (assoc :biff/submit-tx f)
      (update :trident.system/close conj close))))

(defn wrap-event-handler [handler]
  (fn [{:keys [?reply-fn] :as event}]
    (bu/fix-stdout
      (let [response (try
                       (handler event)
                       (catch Exception e
                         (.printStackTrace e)
                         (bu/anom :fault)))]
        (when ?reply-fn
          (?reply-fn response))))))

(defn start-event-router [{:keys [biff.sente/ch-recv biff/event-handler] :as sys}]
  (update sys :trident.system/stop conj
    (sente/start-server-chsk-router! ch-recv
      (-> event-handler
        bu-crux/wrap-sub
        bu-crux/wrap-tx
        (bu/wrap-env sys)
        wrap-event-handler))))

(defn set-auth-route [sys]
  (update sys :biff/routes conj (auth/route sys)))

(defn set-handler [{:biff/keys [routes host node]
                    :biff.handler/keys [roots
                                        secure-defaults
                                        not-found-path] :as sys}]
  (let [cookie-key (bt/decode (auth/get-key (assoc sys
                                              :k :cookie-key
                                              :biff/db (crux/db node)))
                     :base64)
        session-store (cookie/cookie-store {:key cookie-key})
        handler (bu-http/make-handler
                  {:roots roots
                   :session-store session-store
                   :secure-defaults secure-defaults
                   :not-found-path not-found-path
                   :routes [(into ["" {:middleware [[bu/wrap-env sys]]}]
                              routes)]})]
    (update sys :biff.web/host->handler assoc host handler)))

(defn write-static-resources
  [{:biff.static/keys [root resource-root]
    :keys [biff/static-pages] :as sys}]
  (bu-static/export-rum static-pages root)
  (bu-static/copy-resources resource-root root))

(defn start-biff [sys instance-ns]
  (let [new-sys (-> sys
                  (set-defaults instance-ns)
                  start-crux
                  start-sente
                  start-tx-pipe
                  start-event-router
                  set-auth-route
                  set-handler)]
    (write-static-resources new-sys)
    (-> sys
      (merge (select-keys new-sys [:trident.system/stop
                                   :biff.web/host->handler]))
      (merge (bu/select-ns-as new-sys 'biff instance-ns)))))
