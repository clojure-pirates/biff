(ns biff.pack)
;(ns ^:biff biff.pack
;  (:require
;    [clj-http.client :as http]
;    [clojure.string :as str]
;    [biff.core :as core]
;    [biff.util :as bu :refer [defmemo]]
;    [biff.util.static :as bu-static]
;    [biff.util.http :as bu-http]
;    [rum.core :as rum :refer [defc]]
;    [trident.util :as u])
;  (:import [java.lang.management ManagementFactory]))
;
;(defmemo get-latest-sha (* 1000 60)
;  [{:keys [repo-name branch]}]
;  (->>
;    (http/get (str  "https://api.github.com/repos/" repo-name "/git/refs/heads/" branch)
;      {:as :json})
;    :body
;    :object
;    :sha))
;
;(defn norm-repo [{:keys [html_url description default_branch full_name stargazers_count]}]
;  {:url html_url
;   :description description
;   :branch default_branch
;   :repo-name full_name
;   :stars stargazers_count})
;
;(defmemo all-packages (* 1000 60)
;  []
;  (->>
;    (http/get "https://api.github.com/search/repositories"
;      {:query-params {:q "topic:clj-biff"}
;       :as :json
;       :headers {"Accept" "application/vnd.github.mercy-preview+json"}})
;    :body
;    :items
;    (map norm-repo)))
;
;(defmemo get-repo (* 1000 60)
;  [repo-name]
;  (-> (http/get (str "https://api.github.com/repos/" repo-name)
;        {:as :json})
;    :body
;    norm-repo))
;
;(defn assoc-latest-sha [repo]
;  (assoc repo :latest-sha (get-latest-sha repo)))
;
;(defn installed-packages []
;  (let [repo-name->url (->> core/config
;                         :plugins
;                         vals
;                         (map (juxt ::repo ::app-url))
;                         (into {}))
;        assoc-url #(assoc % :app-url (repo-name->url (:repo-name %)))]
;  (->> (bu/deps)
;    :deps
;    vals
;    (filter ::user-package)
;    (map (fn [{:keys [git/url] :as package}]
;           (-> url
;             (str/replace #"^https://github.com/" "")
;             get-repo
;             (merge package)
;             assoc-latest-sha
;             assoc-url))))))
;
;(defn available-packages []
;  (let [installed-urls (->> (installed-packages)
;                         (map :url)
;                         set)]
;    (->> (all-packages)
;      (remove (comp installed-urls :url)))))
;
;(defn need-restart? []
;  (> (-> (bu/deps)
;       :biff/config
;       (::last-update #inst "1970")
;       .getTime)
;    (.getStartTime (ManagementFactory/getRuntimeMXBean))))
;
;(defc table [{:keys [title]} contents]
;  (when (not-empty contents)
;    (list
;      [:h5 title]
;      [:table.table.table-striped
;       [:tbody
;        (for [row contents]
;          [:tr
;           (for [col row]
;             [:td {:style {:vertical-align "middle"}}
;              col])])]])))
;
;(defc hidden [k v]
;  [:input {:name k :value v :type "hidden"}])
;
;(defc installed-packages-table []
;  (table {:title "Installed packages"}
;    (for [{:keys [sha latest-sha url description branch repo-name stars app-url]}
;          (sort-by :repo-name (installed-packages))]
;      [[:a {:href url :target "_blank"} repo-name]
;       [:div description]
;       [:.d-flex
;        (when app-url
;          [:a.btn.btn-primary.mr-2.btn-sm {:href app-url} "Open"])
;        [:form.mb-0 {:method "post"}
;         (bu-static/csrf)
;         (hidden "action" "uninstall")
;         (hidden "repo-name" repo-name)
;         [:button.btn.btn-secondary.btn-sm {:type "submit"} "Uninstall"]]
;        (when (not= sha latest-sha)
;          [:form.mb-0.ml-2 {:method "post"}
;           (bu-static/csrf)
;           (hidden "action" "update")
;           (hidden "repo-name" repo-name)
;           (hidden "latest-sha" latest-sha)
;           [:button.btn.btn-primary.btn-sm {:type "submit"} "Update"]])]])))
;
;(defc available-packages-table []
;  (table {:title "Available packages"}
;    (for [{:keys [url description branch repo-name stars]}
;          (sort-by :stars (available-packages))]
;      [[:a {:href url :target "_blank"} repo-name]
;       [:div description]
;       [:form.mb-0 {:method "post"}
;        (bu-static/csrf)
;        (hidden "action" "install")
;        (hidden "repo-name" repo-name)
;        (hidden "branch" branch)
;        [:button.btn.btn-primary {:type "submit"} "Install"]]])))
;
;(defc pack-page [req]
;  [:html bu-static/html-opts
;   (bu-static/head "Biff Pack")
;   [:body bu-static/body-opts
;    (bu-static/navbar
;      [:a.text-secondary {:href "/biff/auth/change-password"}
;       "Change password"]
;      [:.mr-3]
;      [:form.form-inline.mb-0 {:method "post" :action "/biff/auth/logout"}
;       (bu-static/csrf)
;       [:button.btn.btn-outline-secondary.btn-sm
;        (bu-static/unsafe {:type "submit"} "Sign&nbsp;out")]])
;    [:.container-fluid.mt-3
;     (when (need-restart?)
;       [:.mb-3
;        [:div "You must restart Biff for changes to take effect."]
;        [:form.mb-0 {:method "post" :action "/biff/pack/restart"}
;         (bu-static/csrf)
;         [:button.btn.btn-danger.btn-sm {:type "submit"} "Restart now"]]])
;     (installed-packages-table)
;     (available-packages-table)]]])
;
;(defn update-pkgs! [f & args]
;  (apply bu/update-deps!
;    (comp #(assoc-in % [:biff/config ::last-update] (java.util.Date.)) f)
;    args))
;
;(defn handle-action [{{:keys [action repo-name branch latest-sha] :as params} :params}]
;  (let [pkg-name (symbol (str "github-" repo-name))]
;    (case action
;      "install" (update-pkgs! assoc-in [:deps pkg-name]
;                  {:git/url (str "https://github.com/" repo-name)
;                   :sha (get-latest-sha params)
;                   ::user-package true})
;      "uninstall" (update-pkgs! update :deps dissoc pkg-name)
;      "update" (update-pkgs! assoc-in [:deps pkg-name :sha] latest-sha))))
;
;(defc restart-page [_]
;  [:html bu-static/html-opts
;   (bu-static/head {:title "Restarting Biff"}
;     [:script {:src "/biff/pack/js/restart.js"}])
;   [:body bu-static/body-opts
;    (bu-static/navbar)
;    [:.container-fluid.mt-3
;     [:.d-flex.flex-column.align-items-center
;      [:.spinner-border.text-primary {:role "status"}
;       [:span.sr-only "Loading..."]]
;      [:p.mt-3 "Waiting for Biff to restart. If nothing happens within 90 seconds, try "
;       [:a {:href "/" :target "_blank"} "opening Biff manually"] "."]]]]])
;
;(defn restart-biff [_]
;  (future
;    (Thread/sleep 500)
;    (shutdown-agents)
;    (System/exit 0))
;  (bu-static/render restart-page nil))
;
;(defn ping [_]
;  {:status 200
;   :body ""
;   :headers {"Content-Type" "text/plain"}})
;
;(def config
;  {:biff/route
;   ["/biff/pack"
;    ["/ping" {:get ping
;              :name ::ping}]
;    ["" {:middleware [bu-http/wrap-authorize-admin]}
;     ["" {:get #(bu-static/render pack-page %)
;          :post #(bu-static/render pack-page (doto % handle-action))
;          :name ::pack}]
;     ["/restart" {:post restart-biff
;                  :name ::restart}]]]})
