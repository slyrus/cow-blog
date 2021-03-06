(ns blog.middleware
  "This namespace houses middleware of all sorts."
  (:require (blog [config :as config]
                  [layout :as layout]
                  [flash :as flash]
                  [util :as util]
                  [error :as error]
                  [time :as time]
                  [db :as db])
            (clojure [stacktrace :as trace])
            (clojure.contrib [string :as s])
            (ring.util [response :as response])
            (sandbar [stateful-session :as session])
            (hiccup [core :as hiccup])))

(defn catching-errors
  "Run a handler, and if there's an exception, show a short or long
  message (depending on value of config/DEBUG) in HTML form in the browser."
  [handler]
  (let [newlines-to-br #(s/replace-re #"\n" "<br/>" %)
        pr-map #(vector :ul
                        (for [[k v] (sort %)]
                          [:li [:strong k] " " (hiccup/escape-html (pr-str v))]))]
    (fn [request]
      (try (handler request)
           (catch Exception e
             {:status 500
              :headers {"Content-Type" "text/html"}
              :body (hiccup/html
                     (if config/DEBUG
                       [:div [:h2 "ERROR!"]
                        [:h3 "Request:"]
                        [:pre [:div (pr-map request)]]
                        [:h3 "Stacktrace"]
                        [:div [:pre
                               (newlines-to-br
                                (with-out-str
                                  (trace/print-cause-trace e)))]]
                        ]
                       [:div [:h2 "Oops!"]
                        "The server just crashed.  <strong>WHAT DID YOU DO?!</strong>"]))})))))

(defn wrap-layout
  "Run a handler, and wrap the result in some standard HTML
  to render the site layout/theme.  Also add Content-Type header
  and status code 200 if not specified."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (let [status (:status response 200)
            new-body (layout/wrap-in-layout (:title response)
                                            (:body response)
                                            (session/session-get :user)
                                            (when (= 200 status)
                                              (session/get-flash-value! :message))
                                            (when (= 200 status)
                                              (session/get-flash-value! :error)))]
        (assoc response
          :headers (merge (response :headers)
                          {"Content-Type" "text/html;charset=UTF-8"})
          :status (or (response :status) 200)
          :body new-body)))))

(defn wrap-expires-header
  "Run a handler, and wrap the result in Cache-Control and Expires
  headers, which greatly increase performance for serving static files."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (update-in response [:headers]
                 conj {"Cache-Control" "max-age=3600;must-revalidate"
                       "Expires" (time/datestr :http (time/expire-date))}))))

(defn wrap-admin
  "If the user is logged in, display the page.  Otherwise skip this route
  (probably ends up giving a 404 error)."
  [handler]
  (fn [request]
    (if-let [user (session/session-get :user)]
      (handler request))))

(declare PAGE-NUMBER)

(defn wrap-page-number
  "Bind PAGE-NUMBER to the current value of the 'p' query param."
  [handler]
  (fn [request]
    (let [{{page-number "p"} :query-params} request]
      (binding [PAGE-NUMBER (or (try (util/safe-int page-number) (catch Exception _ 1)) 1)]
        (handler request)))))

(declare USER)

(defn wrap-user
  "Bind USER to the current value of the ':user' session key."
  [handler]
  (fn [request]
    (binding [USER (session/session-get :user)]
      (handler request))))

(defn wrap-db
  "Run handler with an open DB handle."
  [handler]
  (fn [request]
    (db/with-db (handler request))))
