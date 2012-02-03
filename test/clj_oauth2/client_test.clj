(ns clj-oauth2.client-test
  (:use [lazytest.describe]
        [lazytest.expect :only (expect)]
        [clojure.data.json :only [json-str]]
        [clojure.pprint :only [pprint]])
  (:require [clj-oauth2.client :as base]
            [ring.adapter.jetty :as ring]
            [uri.core :as uri]
            [clojure.contrib.string :as str])
  (:import [clj_oauth2 OAuth2Exception OAuth2StateMismatchException]
           [org.apache.commons.codec.binary Base64]))


(defn throws?
  [c f & [e]]
  (try (f) false
       (catch Throwable t
         (if (instance? c t)
           (do (when e (e t)) true)
           (throw t)))))



(def endpoint
  {:client-id "foo"
   :client-secret "bar"
   :access-query-param :access_token
   :scope ["foo" "bar"]})

(def access-token
  {:access-token "sesame"
   :query-param :access_token
   :token-type "bearer"
   :expires-in 120
   :refresh-token "new-foo"})


(def endpoint-auth-code
  (assoc endpoint
    :redirect-uri "http://my.host/cb"
    :grant-type "authorization_code"
    :authorization-uri "http://localhost:18080/auth"
    :access-token-uri "http://localhost:18080/token-auth-code"))

(def endpoint-resource-owner
  (assoc endpoint
    :grant-type "password"
    :username "foo"
    :password "bar"))

(defn parse-base64-auth-header [req]
  (let [header (get-in req [:headers "authorization"] "")
        [scheme param] (rest (re-matches #"\s*(\w+)\s+(.+)" header))]
    (when-let [scheme (and scheme param (.toLowerCase scheme))]
      [scheme (String. (Base64/decodeBase64 param) "UTF-8")])))

(defn parse-basic-auth-header [req]
  (let [[scheme param] (parse-base64-auth-header req)]
    (and scheme param
         (= "basic" scheme)
         (str/split #":" 2 param))))

(defn handle-protected-resource [req grant & [deny]]
  (let [query (uri/form-url-decode (:query-string req))
        [scheme param] (parse-base64-auth-header req)
        bearer-token (and (= scheme "bearer") param)]
    (if (= (or bearer-token (:access_token query)) (:access-token access-token))
      {:status 200 :body grant}
      {:status 400 :body (or deny "nope")})))

(defn client-authenticated? [req endpoint]
  (let [body (:body req)
        [client-id client-secret]
        (or (parse-basic-auth-header req)
            [(:client_id body) (:client_secret body)])]
    (and (= client-id (:client-id endpoint))
         (= client-secret (:client-secret endpoint)))))

;; shamelessly copied from clj-http tests
(defn handler [req]
  ;; (pprint req)
  ;; (println)
  ;; (println)
  (let [req (assoc req :query-params
                   (and (:query-string req)
                        (uri/form-url-decode (:query-string req))))]
    (condp = [(:request-method req) (:uri req)]
      [:post "/token-auth-code"]
      (let [body (uri/form-url-decode (slurp (:body req)))
            req (assoc req :body body)]
        (if (and (= (:code body) "abracadabra")
                 (= (:grant_type body) "authorization_code")
                 (client-authenticated? req endpoint-auth-code)
                 (= (:redirect_uri body) (:redirect-uri endpoint-auth-code)))
          {:status 200
           :headers {"content-type" (str "application/"
                                         (if (contains? (:query-params req) :formurlenc)
                                           "x-www-form-urlencoded"
                                           "json")
                                         "; charset=UTF-8")}
           :body ((if (contains? (:query-params req) :formurlenc)
                    uri/form-url-encode
                    json-str)
                  (let [{:keys [access-token
                                token-type
                                expires-in
                                refresh-token]}
                        access-token]
                    {:access_token access-token
                     :token_type token-type
                     :expires_in expires-in
                     :refresh_token refresh-token}))}
          {:status 400 :body "error"}))
      [:post "/token-error"]
      {:status 400
       :headers {"content-type" "application/json"}
       :body (json-str {:error "unauthorized_client"
                        :error_description "not good"})}
      [:get "/some-resource"]
      (handle-protected-resource req "that's gold jerry!")
      [:get "/query-echo"]
      (handle-protected-resource req (:query-string req))
      [:get "/get"]
      (handle-protected-resource req "get")
      [:post "/post"]
      (handle-protected-resource req "post")
      [:put "/put"]
      (handle-protected-resource req "put")
      [:delete "/delete"]
      (handle-protected-resource req "delete")
      [:head "/head"]
      (handle-protected-resource req "head"))))

(defonce server
  (future (ring/run-jetty handler {:port 18080})))

(describe "grant-type authorization-code"
  (given [req (base/make-auth-request endpoint-auth-code "bazqux")
          uri (uri/uri->map (uri/make (:uri req)) true)]
    (it "constructs a uri for the authorization redirect"
      (and (= (:scheme uri) "http")
           (= (:host uri) "localhost")
           (= (:port uri) 18080)
           (= (:path uri) "/auth")
           (= (:query uri) {:response_type "code"
                            :client_id "foo"
                            :redirect_uri "http://my.host/cb"
                            :scope "foo bar"
                            :state "bazqux"})))
    (it "contains the passed in scope and state"
      (and (= (:scope req) ["foo" "bar"])
           (= (:state req) "bazqux"))))

  (testing base/get-access-token
    (it "returns an access token hash-map on success"
      (= (:access-token (base/get-access-token endpoint-auth-code
                                               {:code "abracadabra" :state "foo"}
                                               {:state "foo"}))
         "sesame"))
    (it "also works with client credentials passed in the authorization header"
      (= (:access-token (base/get-access-token (assoc endpoint-auth-code
                                                 :authorization-header? true)
                                               {:code "abracadabra" :state "foo"}
                                               {:state "foo"}))
         "sesame"))
    (it "also works with application/x-www-form-urlencoded responses (as produced by Facebook)"
      (= (:access-token (base/get-access-token (assoc endpoint-auth-code :access-token-uri
                                                 (str (:access-token-uri endpoint-auth-code)
                                                      "?formurlenc"))
                                          {:code "abracadabra" :state "foo"}
                                          {:state "foo"}))
         "sesame"))
    (it "returns an access token when no state is given"
      (= (:access-token (base/get-access-token endpoint-auth-code {:code "abracadabra"}))
         "sesame"))
    (it "fails when state differs from expected state"
      (throws? OAuth2StateMismatchException
               (fn []
                 (base/get-access-token endpoint-auth-code
                                   {:code "abracadabra" :state "foo"}
                                   {:state "bar"}))))
    (it "fails when an error response is passed in"
      (throws? OAuth2Exception
               (fn []
                 (base/get-access-token endpoint-auth-code
                                   {:error "invalid_client"
                                    :error_description "something went wrong"}))
               (fn [e]
                 (expect (= ["something went wrong" "invalid_client"] @e)))))
    (it "raises on error response"
      (throws? OAuth2Exception
               (fn []
                 (base/get-access-token (assoc endpoint-auth-code
                                     :access-token-uri
                                     "http://localhost:18080/token-error")
                                   {:code "abracadabra" :state "foo"}
                                   {:state "foo"}))
               (fn [e]
                 (expect (= ["not good" "unauthorized_client"] @e)))))))

(describe "token usage"
  (it "should grant access to protected resources"
    (= "that's gold jerry!"
       (:body (base/request {:method :get
                             :oauth2 access-token
                             :url "http://localhost:18080/some-resource"}))))

  (it "should preserve the url's query string when adding the access-token"
    (= {:foo "123" (:query-param access-token) (:access-token access-token)}
       (uri/form-url-decode
        (:body (base/request {:method :get
                              :oauth2 access-token
                              :query-params {:foo "123"}
                              :url "http://localhost:18080/query-echo"})))))

  (it "should deny access to protected resource given an invalid access token"
    (= "nope"
       (:body (base/request {:method :get
                             :oauth2 (assoc access-token :access-token "nope")
                             :url "http://localhost:18080/some-resource"
                             :throw-exceptions false}))))

  (testing "pre-defined shortcut request functions"
    (given [req {:oauth2 access-token}]
      (it (= "get" (:body (base/get "http://localhost:18080/get" req))))
      (it (= "post" (:body (base/post "http://localhost:18080/post" req))))
      (it (= "put" (:body (base/put "http://localhost:18080/put" req))))
      (it (= "delete" (:body (base/delete "http://localhost:18080/delete" req))))
      (it (= 200 (:status (base/head "http://localhost:18080/head" req)))))))