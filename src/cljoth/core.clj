(ns cljoth.core
  (:require [clojure.xml :as xml]
            [clojure.string :as str]
            [clojure.java.io :as io]

            [environ.core :as environ]
            [org.httpkit.client :as http]
            [net.cgrand.enlive-html :as html]))

(defn env
  [key & {:keys [default]}]
  (if-let [val (get environ/env key)]
    val
    (or default
        (throw
         (Exception.
          (str "Could not find environment variable "
               (str/replace
                (str/upper-case (name key))
                #"-" "_")))))))

(def +paper-directory+
  (env :cljoth-papers
       :default (str (System/getProperty "user.home") "/cljoth-papers/")))

;; http://export.arxiv.org/rss/cs

;; https://arxiv.org/search/advanced?advanced=1&terms-0-operator=AND&terms-0-term=&terms-0-field=title&classification-computer_science=y&classification-physics_archives=all&classification-include_cross_list=include&date-year=&date-filter_by=date_range&date-from_date=1991-07&date-to_date=1991-08&date-date_type=submitted_date&abstracts=show&size=200&order=-announced_date_first

;; "https://arxiv.org/abs/cs/9301113"

(defn paper-urls-in [url]
  (->> (xml/parse url)
       :content first :content
       (filter #(= (:tag %) :items))
       first :content first :content
       (map #(:rdf:resource (:attrs %)))))

(defn -all-date-ranges []
  (let [dates
        (mapcat
         (fn [year] (map (fn [month] [year month]) (range 1 13)))
         (range 1991 2019))]
    (drop 6 (map (fn [a b] [a b])
                 dates (rest dates)))))

(defn -format-query [[[y1 m1] [y2 m2]]]
  (format "https://arxiv.org/search/advanced?advanced=1&terms-0-operator=AND&terms-0-term=&terms-0-field=title&classification-computer_science=y&classification-physics_archives=all&classification-include_cross_list=include&date-year=&date-filter_by=date_range&date-from_date=%d-%02d&date-to_date=%d-%02d&date-date_type=submitted_date&abstracts=show&size=200&order=-announced_date_first"
          y1 m1 y2 m2))

(defn -urls-from-result-page [pg]
  (map
   #(-> % :content first :attrs :href)
   (html/select
    (html/html-resource (java.io.StringReader. pg))
    [:li.arxiv-result :p.list-title])))

(defn get! [url]
  (Thread/sleep 1000)
  (:body @(http/get url)))

(defn historic-paper-urls []
  (mapcat
   #(-urls-from-result-page (get! (-format-query %)))
   (-all-date-ranges)))

(defn pdf-path [paper-url]
  (let [id (last (str/split paper-url #"/"))]
    (str +paper-directory+ id ".pdf")))

(defn pdf-url [paper-url]
  (str/replace paper-url #"/abs/" "/pdf/"))

(defn got-pdf? [paper-url]
  (.exists (io/as-file (pdf-path paper-url))))

(defn grab-pdf! [paper-url]
  (with-open [out (io/output-stream (io/as-file (pdf-path paper-url)))]
    (io/copy
     (:body @(http/get (pdf-url paper-url)))
     out)))
