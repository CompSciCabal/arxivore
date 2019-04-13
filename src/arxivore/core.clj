(ns arxivore.core
  (:require [clojure.xml :as xml]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]

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
  (env :arxivore-papers
       :default (str (System/getProperty "user.home") "/arxivore-papers/")))

(defn get! [url]
  (Thread/sleep 1500)
  (:body @(http/get url)))

(defn get-resource! [url]
  (html/html-resource (java.io.StringReader. (get! url))))

(defn paper-urls-in [url]
  (->> (xml/parse url)
       :content first :content
       (filter #(= (:tag %) :items))
       first :content first :content
       (map #(:rdf:resource (:attrs %)))))

(defn -all-date-ranges []
  (let [current-year (Integer. (.format (java.text.SimpleDateFormat. "yyyy") (new java.util.Date)))
        dates
        (mapcat
         (fn [year] (map (fn [month] [year month]) [1 12]))
         (range 1991 (inc current-year)))]
    (map (fn [a b] [a b])
         dates (rest dates))))

(defn -format-query [[[y1 m1] [y2 m2]] & {:keys [start]}]
  (let [fmt (format "https://arxiv.org/search/advanced?advanced=1&terms-0-operator=AND&terms-0-term=&terms-0-field=title&classification-computer_science=y&classification-physics_archives=all&classification-include_cross_list=include&date-year=&date-filter_by=date_range&date-from_date=%d-%02d&date-to_date=%d-%02d&date-date_type=submitted_date&abstracts=show&size=200&order=-announced_date_first"
                    y1 m1 y2 m2)]
    (if start
      (str fmt "&start=" start)
      fmt)))

(defn -urls-from-single-page [resource]
  (map #(-> % :content first :attrs :href)
       (html/select
        resource
        [:li.arxiv-result :p.list-title])))

(defn -urls-from-date-range [date-range]
  (let [resource (get-resource! (-format-query date-range))
        title (-> (html/select resource [:h1]) first :content first)]
    (if-let [match (rest (re-find #"Showing (\d+)[–-](\d+) of ([\d,]+)" title))]
      (let [[from to of] (map #(edn/read-string (str/replace % #"," "")) match)]
        (if (and to of (> of to))
          (mapcat
           #(-urls-from-single-page
             (get-resource!
              (-format-query
               date-range :start %)))
           (range to of to))
          (-urls-from-single-page resource)))
      (-urls-from-single-page resource))))

(defn historic-paper-urls []
  (mapcat -urls-from-date-range (-all-date-ranges)))

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
     (:body (get! (pdf-url paper-url)))
     out)))

(defn grab-urls! []
  (let [path (str +paper-directory+ "urls.txt")]
    (doseq [url (historic-paper-urls)]
      (spit path (str url \newline) :append true))))

;; (defn grab-urls! []
;;   (historic-paper-urls))

;; http://export.arxiv.org/rss/cs

;; https://arxiv.org/search/advanced?advanced=1&terms-0-operator=AND&terms-0-term=&terms-0-field=title&classification-computer_science=y&classification-physics_archives=all&classification-include_cross_list=include&date-year=&date-filter_by=date_range&date-from_date=1991-07&date-to_date=1991-08&date-date_type=submitted_date&abstracts=show&size=200&order=-announced_date_first

;; "https://arxiv.org/abs/cs/9301113"
