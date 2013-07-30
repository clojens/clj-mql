(ns com.twinql.clojure.mql
  (:refer-clojure)
  (:import
     (java.lang Exception)
     (java.net URI)
     (org.apache.http Header)
     (org.apache.http.client CookieStore)
     (org.apache.http.impl.client AbstractHttpClient))
   (:require [clojure.data.json :as json]
             [clj-http.client :as http]))
     ;[com.twinql.clojure.http :as http]))

;;;
;;; API URIs.
;;; If you need to, you can rebind these around your calls to use different locations.
;;; By default, SSL is only used for authenticated operations.
;;;

(def ^:dynamic *mql-login*     (new URI "https://api.freebase.com/api/account/login"))
(def ^:dynamic *mql-logged-in* (new URI "https://api.freebase.com/api/account/loggedin"))
(def ^:dynamic *mql-write*     (new URI "https://api.freebase.com/api/service/mqlwrite"))
(def ^:dynamic *mql-version*   (new URI "http://api.freebase.com/api/version"))
(def ^:dynamic *mql-status*    (new URI "http://api.freebase.com/api/status"))
(def ^:dynamic *mql-read*      (new URI "http://api.freebase.com/api/service/mqlread"))
(def ^:dynamic *mql-search*    (new URI "http://api.freebase.com/api/service/search"))
(def ^:dynamic *mql-reconcile* (new URI "http://data.labs.freebase.com/recon/query"))

(def ^:dynamic *s-mql-login*     (new URI "https://www.sandbox-freebase.com/api/account/login"))
(def ^:dynamic *s-mql-logged-in* (new URI "https://www.sandbox-freebase.com/api/account/loggedin"))
(def ^:dynamic *s-mql-write*     (new URI "https://www.sandbox-freebase.com/api/service/mqlwrite"))
(def ^:dynamic *s-mql-version*   (new URI "http://www.sandbox-freebase.com/api/version"))
(def ^:dynamic *s-mql-status*    (new URI "http://www.sandbox-freebase.com/api/status"))
(def ^:dynamic *s-mql-read*      (new URI "http://www.sandbox-freebase.com/api/service/mqlread"))
(def ^:dynamic *s-mql-search*    (new URI "http://www.sandbox-freebase.com/api/service/search"))
(def ^:dynamic *s-mql-reconcile* (new URI "http://data.labs.freebase.com/recon/query"))

(defmacro with-sandbox
  "Rebinds the API locations to point to the Sandbox."
  [& body]
  `(binding [*mql-version*   *s-mql-version*
             *mql-login*     *s-mql-login*
             *mql-logged-in* *s-mql-logged-in*
             *mql-status*    *s-mql-status*
             *mql-read*      *s-mql-read*
             *mql-write*     *s-mql-write*
             *mql-search*    *s-mql-search*
             *mql-reconcile* *s-mql-reconcile*]
     ~@body))

;;; This is bound by with-login.
(def ^:dynamic *cookie-store* nil)

;;;
;;; Generic utilities.
;;;

(defn- non-nil-values
  "Return the map with only those keys that map to non-nil values."
  [m]
  (into {} (filter (fn [[k v]] ((complement nil?) v)) m)))

(defn alter-map-by
  "Returns m with each value transformed by f."
  [m f]
  (let [ks (keys m)]
    (loop [o (transient m)
           k (first ks)
           ks (rest ks)]
      (if-not k
        (persistent! o)
        (recur (assoc! o k (f (get o k)))
               (first ks)
               (rest ks))))))

;;;
;;; Utility functions for request and response manipulation.
;;;

(defn- envelope
  ([p q]
     (merge (envelope q) p))
  ([q]
     {"query" q
      "escape" false}))

(defn ok? [res]
  (= "/api/status/ok" (:code res)))

(defn- check?
  "Checks both an HTTP status code and a JSON body."
  [code content]
  (and (and (>= code 200)
            (< code 300))
       (ok? content)))

(defn- error->exception
  ([res]
     (error->exception res nil))
  ([res q]
     (if res
       (throw (Exception.
               (str "Non-OK status from MQL query: code ["
                    (:code res)
                    "] -- response [" (prn-str res)

                    ;(seq (map :message (:messages res)))
                    "]"
                    (if q
                      (str ". Query was " (pr-str q)
                           ".")
                      ""))))
       (throw (Exception.
               (str "Empty response from MQL query."))))))

(declare %mql-read)

;; Extract the result from the body if the request was successful.
;; Otherwise, throw an exception.
;; If a cursor identifier was returned, produce a lazy sequence
;; to fetch the next batch.
(defn- process-query-result
  ([res q args]
     (when res
       (if (ok? res)
         (let [cursor (:cursor res)]
           (if (string? cursor)
             (lazy-seq
              (concat
               (:result res)
               (apply %mql-read
                      (assoc-in q ["query" "cursor"] cursor)
                      args)))
             ;; No? This must be the last one, or no cursor was requested.
             (:result res)))
         (error->exception res q))))
  ([res]
     (process-query-result res nil nil)))

;; Handle a collection of results, as returned by a 'queries' request.
;; TODO: support cursors here. No so easy, though.
(defn- process-multiple-query-results [res names]
  (when res
    (if (ok? res)
      ;; We want to preserve order, so we don't just use
      ;; select-keys.
      (map (comp process-query-result res keyword) names)
      (error->exception res))))

;;;
;;; Query manipulation.
;;;

;; An infinite sequence of query names.
(def query-names
  (map (fn [i] (str "q" i)) (iterate inc 1)))

(defn assoc!-when
  "Like assoc!, but skips keys with null values."
  ([m k v]
    (if (nil? v)
      m
      (assoc! m k v)))
  ([m k v & kvs]
     (if k
       (apply assoc!-when
              (assoc!-when m k v)
              kvs)
       m)))

(defn- envelope-parameters [p]
  (when p
    (let [{:keys [cursor escape lang as-of-time uniqueness-failure]} p]
      (persistent!
       (assoc!-when (transient {})
                    "cursor" cursor
                    "escape" escape
                    "lang" lang
                    "as_of_time" as-of-time
                    "uniqueness_failure" uniqueness-failure)))))



(defn- mql->query
  "Read and write allow for one or many queries as input. This function
  takes an arbitrary collection parameter and returns a map (whose
  values you should JSON-encode), whether it represents many or one, and
  a sequence of names."
  ([mql params]
     (let [p (envelope-parameters params)
           many? (sequential? mql)
           names (when many?
                   (take (count mql) query-names))]
       [(if many?
          {"queries"
           (zipmap names
                   (map (comp (partial envelope p) vector) mql))}
          {"query"
           (envelope p [mql])})
        many?
        names]))
  ([mql]
     (mql->query mql nil)))

;;;
;;; HTTP.
;;;

(defmacro with-http-bindings
  "Binds the keys from the result of the HTTP request, executing forms."
  [keys http-form & forms]
  `(let [{:keys ~keys} ~http-form]
     ~@forms))

(defmacro with-http-bindings-exception
  "Binds the keys from the result of the HTTP request, executing forms.
  If the code is not success, throw an exception."
  [keys http-form & forms]
  `(with-http-bindings ~keys ~http-form
     (if (and (>= ~'code 200)
              (< ~'code 300))
       (do
         ~@forms)
       (throw (new Exception (str "HTTP failure: " ~'code))))))

;;;
;;; MQL operations.
;;;

(defn mql-login
  "Returns the login response and on success, false on failure.
  Also returns the cookie store from the client."
  [user pass]
  (with-http-bindings
    [code content #^AbstractHttpClient client]  ; To get to cookies.
    (http/post *mql-login*
               :query {"username" user "password" pass}
               :as :json
               :cookie-store *cookie-store*)
    (if (check? code content)
      [content (.getCookieStore client)]
      [false nil])))

(defn mql-logged-in?
  "Return whether the user (identified by the current cookie store)
  is logged in."
  ([cookie-store]
   (with-http-bindings
     [code content]
     (http/get *mql-logged-in* :as :json :cookie-store cookie-store)
     (check? code content)))
  ([]
   (mql-logged-in? *cookie-store*)))

(defmacro with-login [[user pass] & body]
  `(let [[response# cookie-store#] (mql-login ~user ~pass)]
     (if (and response#
              (ok? response#))
       ;; Great!
       (binding [*cookie-store* cookie-store#]
         ~@body)
       (throw (new Exception "Login failure.")))))

(defn %mql-read [q many? names headers parameters cookie-store]
  (with-http-bindings-exception
    [code content]
    ;; Have to do this every time so we can rewrite the
    ;; cursor part.
    (let [query (alter-map-by q json/json-str)]
      (http/get *mql-read*
                :headers headers
                :parameters parameters
                :query query
                :as :json
                :cookie-store cookie-store))

    (if many?
      (process-multiple-query-results content names)
      (process-query-result content q
                            [many? names headers parameters cookie-store]))))

(defn mql-read
  "Send a MQL query to Freebase. Optionally provide a dictionary of
  arguments suitable to http/get, and a boolean for debug output.
  If a sequence of queries is provided, they are batched and run together;
  the output is as if this function had been mapped over the sequence of
  queries, but execution is more efficient."
  ([mql & args]

     ;; Destructure a bunch of stuff, then jump off to a function that
     ;; can indirectly recurse with an updated cursor parameter from
     ;; fetched results.
     (let [{:keys [http-options debug? envelope-parameters]}
           (apply array-map args)
           [q many? names] (mql->query mql envelope-parameters)]
       (%mql-read q many? names
                  (:headers http-options)
                  (:parameters http-options)
                  *cookie-store*)))

  ([mql]
   (mql-read mql {})))

(defn mql-write
  "Send a MQL write request to Freebase. Requires authentication."
  ([mql http-options]
   (let [[q many? names] (mql->query mql)]
     (with-http-bindings-exception
       [code content]
       ;; TODO: When the response arrives, parse the Set-Cookie header to
       ;; extract the mwLastWriteTime cookie and save it for use with
       ;; subsequent read requests. Should work fine with the cookie store...
       (http/post *mql-write*
                  :headers (assoc (:headers http-options) "X-Metaweb-Request" "x")
                  :parameters (:parameters http-options)
                  :query q
                  :as :json
                  :cookie-store *cookie-store*)

       (if many?
         (process-multiple-query-results content names)
         (process-query-result content)))))

  ([mql]
   (mql-write mql {})))

(defn mql-version
  "Returns a map. Useful keys are :graph (graphd version), :me, :cdb, :relevance."
  []
  (:content (http/get *mql-version* :as :json)))

(defn mql-status
  []
  (:content (http/get *mql-status* :as :json)))

(defn only-matching
  "Filter MQL reconciliation API results to only include matching results."
  [results]
  (filter :match results))

(defn mql-reconcile
  [query & args]
  (let [{:keys [limit
                start
                jsonp
                http-options]} (apply hash-map args)

        query (non-nil-values
                {"q" (json/json-str query)
                 "limit" limit
                 "start" start
                 "jsonp" jsonp})]

    (with-http-bindings-exception
      [code content]
      (http/get *mql-reconcile*
                :headers (:headers http-options)
                :parameters (:parameters http-options)
                :query query
                :as :json
                :cookie-store *cookie-store*)

      content)))

(defn mql-search
  "Perform a MQL search operation.
  Arguments are query (a string), then any of the following keys:
  :format
  :prefixed
  :limit
  :start
  :type
  :type-strict
  :domain
  :domain-strict
  :escape
  :http-options, a dictionary treated as the arguments to http/get."
  [query & args]
  (let [{:keys [format      ; json, id, guid
                prefixed    ; boolean
                limit       ; positive integer, 20 by default
                start       ; default 0
                type        ; string, MQL ID
                type-strict ; string 'any'
                domain
                domain-strict
                escape      ; 'html'

                http-options]} (apply hash-map args)

        query (non-nil-values
                {"query" query
                 "format" (or format "json")
                 "prefixed" prefixed
                 "limit" limit
                 "start" start
                 "type" type
                 "type_strict" type-strict
                 "domain" domain
                 "domain_strict" domain-strict
                 "escape" escape})]

    (with-http-bindings-exception
      [code content]
      (http/get *mql-search*
                :headers (:headers http-options)
                :parameters (:parameters http-options)
                :query query
                :as :json
                :cookie-store *cookie-store*)

      (if (or (nil? format)
              (= format :json)
              (= format "json"))
        (process-query-result content)
        content))))

(comment
  (doseq [m (mql/mql-search "Los Gatos")]
    ;; Everything is a topic...
    (let [topic (first
                  (filter
                     #(not (= "Topic" %))
                     (map :name (:type m))))]
      (println
        (str (:id m) (if topic (str ": a " topic) "")
             " named “" (or (:name m)
                            (:alias m)) "”"))))

  (doseq [m (mql/mql-search "Los Gatos" :type "/location/citytown")]
    ;; Everything is a topic.. filter them out..
    (let [topic (first
                  (filter
                     #(not (= "Topic" %))
                     (map :name (:type m))))]
      (println
        (str (:id m) (if topic (str ": a " topic) "")
             " named “" (or (:name m)
                            (:alias m)) "”"))))


  (first
    (mql/only-matching
      (mql/mql-reconcile
       {
            "/type/object/name" "Blade Runner",
            "/type/object/type" "/film/film",
            "/film/film/starring/actor" ["Harrison Ford", "Rutger Hauer"],
            "/film/film/starring/character" ["Rick Deckard", "Roy Batty"],
            "/film/film/director"
            {
                "name" "Ridley Scott",
                "id" "/guid/9202a8c04000641f8000000000032ded"
            },
            "/film/film/release_date_s" "1981"
        })))

(with-sandbox
  (with-login ["user" "pass"]
    (mql-write {"create" "unless_exists"
                "id" nil
                "name" "Test topic, please ignore"
                "type" ["/common/topic"]})))
  )
