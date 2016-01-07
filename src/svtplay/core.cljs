(ns svtplay.core
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.events]
            [domina.core :as domina]
            [domina.xpath :as dominax :refer [xpath]]
            [sablono.core :as html :refer-macros [html]]
            [goog.events :as events])
  (:import [goog.net XhrIo]
           goog.net.EventType
           [goog.events EventType]))

(enable-console-print!)

(defmulti read om/dispatch)

(defmethod read :categories
  [{:keys [state]} key params]
  (let [st @state]
    (if (contains? st key)
      {:value (get st key)}
      {:categories true})))

(defn parse-category [node]
  {:category (domina/attr node :id)
   :url (last (re-find #"[^']+'([^']+)" (domina/attr node :onPlay)))
   :title (domina/text (domina/single-node (dominax/xpath node "title")))
   :image (domina/text (domina/single-node (dominax/xpath node "image")))})

(defn parse-categories [root]
  (let [items (domina/nodes (dominax/xpath root "//sixteenByNinePoster"))]
    {:categories (map parse-category items)}))

(defn reconciler-send []
  (fn [re cb]
    (let [xhr (XhrIo.)]
      (events/listen xhr goog.net.EventType.SUCCESS
                     #(this-as this (cb (parse-categories (.getResponseXml this)))))
      (.. xhr (setWithCredentials true))
      (. xhr (send "http://www.svtplay.se/xml/categories.xml")))))

(defui Category
  static om/Ident
  (ident [this {:keys [category]}]
         [:category category])
  static om/IQuery
  (query [this]
         [:id :title :url :image])
  Object
  (render [this]
          (html
           (let [{:keys [title url image]} (om/props this)]
             [:li {:className "boxes-item"}
              [:div {:className "image" :tabIndex -1}
               [:img {:src image }]
               [:h2 [:span title]]]]))))

(def category-ui (om/factory Category {:keyfn :category}))

(defn update-position [this event]
  (.log js/console (.-keyCode event) (om/get-state this :x))
;  (.preventDefault event)
;  (.stopPropagation event)
  (case (.-keyCode event)
    40 (om/update-state! this update-in [:x] inc) ;; down
    38 (om/update-state! this update-in [:x] dec)  ;; up
    37 (om/update-state! this update-in [:y] inc) ;; left
    39 (om/update-state! this update-in [:y] dec))) ;; right

(defui Categories
  static om/IQuery
  (query [this]
         [{:categories (om/get-query Category)}])
  Object
  (initLocalState [this] {:pos/x 0
                          :pos/y 0})
  (componentWillMount [this]
                      (let [cb (fn [event] (update-position this event))]
                        (.addEventListener js/window "keydown" cb)
                        (om/update-state! this {:keydown-fn cb})))
  (componentWillUnmount [this]
                        (let [cb (:keydown-fn (om/get-state this))]
                          (.removeEventListener js/window "resize" cb)))
  (render [this]
          (html
           (let [{:keys [categories]} (om/props this)]
             [:div {:className "boxes-container"}
              [:ul
               (map-indexed (fn [idx category] (category-ui category)) categories)]]))))

(def categories-ui (om/factory Categories))

(def reconciler
  (om/reconciler
   {:state {}
    :parser (om/parser {:read read})
    :remotes [:categories]
    :send (reconciler-send)}))

(def code->key
  "map from a character code (read from events with event.which)
  to a string representation of it.
  Only need to add 'special' things here."
  {13 :enter
   37 :left
   38 :up
   39 :right
   40 :down})

(.addEventListener js/window "keydown" (fn [event]
                                         (.stopPropagation event)
                                         (.log js/console event)))

(om/add-root! reconciler Categories (gdom/getElement "app"))
