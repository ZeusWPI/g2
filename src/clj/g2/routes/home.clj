(ns g2.routes.home
  (:require [g2.layout :as layout]
            [g2.git.github :as git]
            [g2.zeus :as zeus-auth]
            [g2.github-auth :as github-auth]
            [g2.db.core :refer [*db*] :as db]
            [g2.oauth :as oauth]
            [g2.routes.issues :as issues]
            [compojure.core :refer [defroutes GET DELETE]]
            [ring.util.http-response :as response]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]))

(defn home-page [request]
  (let [repo-providers (db/get-all-repo-providers)]
    (layout/render request "home.html" {:repo-providers repo-providers
                                        :user           (-> (get-in request [:session :user]))})))

(defn repo-resource [request]
  (response/ok {:repos (map (fn [repo]
                              (assoc repo :image (str "https://zeus.gent/assets/images/Logos_"
                                                      (:name repo) ".svg")))
                            (db/get-repos))}))

(defn repo-get
  "Fetch 1 repo"
  [repo_id]
  (let [repo (db/get-repo {:repo_id repo_id})]
    (if-not (nil? repo)
      (response/ok repo)
      (response/not-found {:msg "Repository not found"}))))

(defn parse-repo-ids [repo_ids_string]
  (log/debug repo_ids_string)
  (log/debug "type: " (type repo_ids_string))
  (if (nil? repo_ids_string)
    nil
    (map #(Integer/parseInt %)
         (string/split repo_ids_string #","))))

(defn projects-get [request]
  (let [projects (db/get-projects)]
    (log/debug "projects: " projects)
    (let [n (map (fn [project] (log/debug "project: " project) (assoc project :repo_ids (parse-repo-ids (:repo_ids project)))) projects)]
      (log/debug "n-projects: " n)
      (response/ok n))))

(defn project-get [project_id]
  (log/debug "Get project" project_id)
  (let [project (db/get-project {:project_id project_id})]
    (if-not (nil? project)
      (response/ok project)
      (response/not-found))))

(defn project-create [name description]
  (do
    (log/debug "Create project: " name " " description)
    (let [insert_id (db/create-project! {:name name, :description description})]
      (response/ok {:new_project_id (:generated_key insert_id)}))))

(defn project-delete [id]
  (do
    (db/delete-project! {:id id})
    (log/debug "Delete project" id)
    (response/no-content)))

(defn link-repo-to-project [id pid]
  (do
    (db/link-repo-to-project! {:project_id pid, :repo_id id})
    (response/no-content)))

(defroutes home-routes-old
           (GET "/oauth/github" [auth-goal] (github-auth/login-github (keyword auth-goal)))
           (GET "/oauth/github-callback/:auth-goal" [& params :as req] (github-auth/login-github-callback req)))

(defn home-routes []
  [["/" {:get {:handler home-page}}]
   ["/user" {:get {:summary   "Get the current user data from the session."
                   ; :parameters
                   :responses {200 {:body {:name string? :email string?}}
                               401 {:description "You need to be logged in to get the current user."
                                    :body        {:message string?}}}
                   :handler   (fn [req]
                                (log/info "session: " (:session req))
                                (if-let [session (:session req)]
                                  (response/ok (get-in session [:user]))
                                  (response/unauthorized {:message "User not found. Are you logged in?"})))}}]
   ["/repository"
    {:swagger {:tags ["repository"]}}
    ["" {:get {:summary "Get the list of code repositories in our backend."
               :handler repo-resource}}]
    ["/sync" {:swagger {:tags ["sync"]}
              :post    {:summary "Synchronise the data from all repositories with our database."
                        :handler (fn [_] (git/sync-repositories) (response/ok))}}]
    ["/:id"
     ["" {:get {:summary    "Get a specific repository."
                :responses {200 {}
                            404 {:description "The repository with the specified id does not exist."}}
                :parameters {:path {:id int?}}
                :handler    (fn [req] (let [id (get-in req [:path-params :id])] (repo-get id)))}}]
     ["/link/:pid" {:put {:summary    "Connect a repository to a project"
                          :parameters {:path {:pid int?, :id int?}}
                          :handler    (fn [{{:keys [id pid]} :path-params}] (link-repo-to-project id pid))}}]
     ["/branches"
      [""]
      ["/:branch_id"]]
     ["/labels"
      [""]
      ["/:label_id"]]]]
   ["/project"
    {:swagger {:tags ["project"]}}
    ["" {:get  {:summary "Get all projects"
                :handler projects-get}
         :post {:summary    "Create a new project"
                :parameters {:body {:name string?, :description string?}}
                :handler    (fn [{{{:keys [name description]} :body} :parameters}] (project-create name description))}}]

    ["/:id"
     ["" {:get    {:summary    "Get a specific project"
                   :responses {200 {}
                               404 {:description "The project with the specified id does not exist."}}
                   :parameters {:path {:id int?}}
                   :handler    (fn [req] (let [id (get-in req [:path-params :id])] (project-get id)))}
          :delete {:summary    "Delete a project"
                   :parameters {:path {:id int?}}
                   :handler    (fn [req] (let [id (get-in req [:path-params :id])] (project-delete id)))}}]
     (issues/route-handler-per-project)]]
   (issues/route-handler-global)
   ["/repo-providers"
    {:get {:summary "Get the list of repository providers configured (like for ex. github or gitlab)"
           :handler (fn [_] (response/ok (db/get-all-repo-providers)))}}]
   ["/hooks"
    ["/:id"
     {:delete {:summary    "Delete a git hook."
               :parameters {:path {:id int?}}
               :handler    (fn [req]
                             (log/info "DELETE HOOK: " (pprint req))
                             (response/ok))}}]]             ;TODO test this
   ["/oauth"
    {:swagger {:tags ["oauth"]}}
    ["/github" {:summary "Log into the application using github"
                :get     {:handler (fn [auth-goal] (github-auth/login-github (keyword auth-goal)))}}]
    ["/github-callback/:auth-goal" {:summary "WIP. Authenticate with github in some specific way..."
                                    :get     {:handler (fn [req]
                                                         (github-auth/login-github-callback req))}}]
    ["/zeus" {:get {:summary "Log into the application using zeus oauth."
                    :handler zeus-auth/login-zeus}}]
    ["/oauth-callback" {:summary "A callback for the oauth login flow."
                        :get     {#_:parameters #_{:query {:code  string?
                                                           :error string?}}
                                  :handler zeus-auth/login-zeus-callback}}]]])
