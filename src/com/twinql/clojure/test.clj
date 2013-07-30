(ns com.twinql.clojure.test
  (:use [com.twinql.clojure.mql]))

(with-sandbox                    ; Use Sandbox API locations.
  (with-login ["user" "pass"]    ; Make a login request and keep the cookie.
    (println "Logged in?" (mql-logged-in?)))

  ;; Ordinary requests don't need authentication; only writes.
  ;(prn (take 3 (map :id (mql-search "Los Gatos")))))
  (println (take 3 (mql-search "Los Gatos"))))

;; Let's try to look up Blade Runner by name and attributes.
(first                ; Return the best result.
  (only-matching      ; Exclude any that don't match.
    (mql-reconcile { "/type/object/name" "Blade Runner",
      "/type/object/type" "/film/film",
      "/film/film/starring/actor" ["Harrison Ford", "Rutger Hauer"],
      "/film/film/starring/character" ["Rick Deckard", "Roy Batty"],
      "/film/film/director" {
        "name" "Ridley Scott",
        "id" "/guid/9202a8c04000641f8000000000032ded"
      },
      "/film/film/release_date_s" "1981"
      })))

;; Run a read query.
(mql-read { "name" "Blade Runner" "/type/object/type" [] })

;; Make more than one query at a time.
(mql-read [
  { "id" "/guid/9202a8c04000641f8000000000009e89"
    "name" nil }

  { "id" "/guid/9202a8c04000641f8000000000032ded"
    "/type/object/type" [] }])

;; Fetch status info.
(prn (mql-version))
(prn (mql-status))