(ns cctx.git
  (:require [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as git-query]
            [clojure.string :as str]))

(defn git-status-clean? [repo-path]
  (let [repo (git/load-repo repo-path)
        status (git/git-status repo)]
    (empty? status)))

(defn branch-exists? [repo-path branch-name]
  (let [repo (git/load-repo repo-path)]
    (some #(= branch-name (.getName %))
          (git/list-branches repo))))

(defn get-current-branch [repo-path]
  (let [repo (git/load-repo repo-path)]
    (.getName (.getBranch (.getRepository repo)))))

(defn create-or-checkout-branch [repo-path branch-name]
  (let [repo (git/load-repo repo-path)]
    (if (branch-exists? repo-path branch-name)
      (git/checkout repo branch-name)
      (git/git-checkout repo branch-name :create true))))

(defn add-files [repo-path files]
  (let [repo (git/load-repo repo-path)]
    (git/git-add repo files)))

(defn stash-changes [repo-path message]
  (let [repo (git/load-repo repo-path)]
    (try
      (git/git-stash-create repo message)
      {:status :success :message "Changes stashed successfully."}
      (catch Exception e
        {:status :warning :message (str "Failed to stash changes: " (.getMessage e))}))))

(defn checkout-branch [repo-path branch-name]
  (let [repo (git/load-repo repo-path)]
    (git/git-checkout repo branch-name)))
