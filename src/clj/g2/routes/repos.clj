(ns g2.routes.repos
  (:require
    [g2.db.core :refer [*db*] :as db]
    [g2.git.github :as git]
    [ring.util.http-response :as response]
    [g2.utils.projects :as p-util]
    [g2.routes.tags :as tags]
    [g2.services.repos-service :as repos-service]
    [conman.core :refer [with-transaction]]
    [clojure.tools.logging :as log]
    [g2.utils.entity :as entity]
    [g2.services.generic-service :as generic-service]))

(defn convert-db-to-api-object
  [db-repo]
  (-> db-repo
      (dissoc :default-tags)))

(defn repo-get
  "Fetch 1 repo"
  [repo_id]
  (let [repo (db/get-repo {:repo_id repo_id})]
    (if-not (nil? repo)
      (response/ok repo)
      (response/not-found {:msg "Repository not found"}))))

; not used for the moment
#_(defn link-repo-to-project [id pid]
    (do
      (log/debug "Link repo" id "to project" pid)
      (p-util/is-project
        pid
        (let [repo (db/get-repo {:repo_id id})]
          (if (nil? repo)
            (response/not-found)
            (with-transaction
              [*db*]
              (do
                (db/link-repo-to-project! {:project_id pid, :repo_id id})
                (response/no-content))))))))

#_(defn unlink-repo-from-project [id pid]
    (response/not-implemented))

(defn repos-get [request]
  (->> (db/get-repos)
       (map (fn [repo]
             (-> repo
                 (assoc :image (format "https://zeus.gent/assets/images/Logos_%s.svg" (:name repo)))
                 (assoc :newIssueUrl "coming soon")
                 (assoc :newPullUrl "coming soon"))))
       response/ok))

(defn route-handler-global []
  ["/repositories"
   {:swagger {:tags ["repository"]}}
   ["" {:get {:summary   "Get the list of code repositories in our backend."
              :responses {200 {}}
              :handler   repos-get}}]
   ["/sync" {:swagger {:tags ["sync"]}
             :post    {:summary   "Synchronise the data from all repositories with our database."
                       :responses {200 {:description "TODO"}
                                   403 {:description "TODO"}
                                   404 {:description "TODO"}}
                       :handler   (fn [_] (git/sync-repositories) (response/ok))}}]
   #_["/branches"
      [""]
      ["/:branch_id"]]
   #_["/labels"
      [""]
      ["/:label_id"]]
   [":repo_id/projects/:project_id" {:delete {:summary    "Unlink a given project id to a given repository"
                                              :responses  {200 {}
                                                           404 {:description "The repository or project with the specified id does not exist."}}
                                              :parameters {:path {:repo_id    int?
                                                                  :project_id int?}}
                                              :handler    #(response/not-implemented)}
                                     :post   {:summary    "Link a given project id to a given repository"
                                              :responses  {200 {}
                                                           404 {:description "The repository or project with the specified id does not exist."}}
                                              :parameters {:path {:repo_id    int?
                                                                  :project_id int?}}
                                              :handler    #(response/not-implemented)}}]])

(defn route-handler-per-project []
  ["/repositories"
   {:swagger {:tags ["repository"]}}
   ["" {:get {:summary    "Get the repositories of a project"
              :responses  {200 {}
                           404 {:description "The project with the specified id does not exist."}}
              :parameters {:path {:id int?}}
              :handler    #(response/ok (generic-service/get-project-entities (get-in % [:path-params :id]) "repos"))}}]])
