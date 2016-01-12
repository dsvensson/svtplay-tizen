(ns svtplay.core
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.events]
            [domina.core :as domina]
            [domina.xpath :as dominax :refer [xpath]]
            [sablono.core :as html :refer-macros [html]]
            [goog.events :as events]
            [cuerdas.core :refer [format split]])
  (:import [goog.net XhrIo]
           goog.net.EventType
           [goog.events EventType]))

(enable-console-print!)

(defmulti readf om/dispatch)

(defmethod readf :categories
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if (contains? st key)
      {:value (get st key)}
      {:remote true})))

(defmethod readf :category
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if (contains? st key)
      {:value (get st key)}
      {:remote true})))

(defmethod readf :default
  [{:keys [state] :as env} key params]
  {:value (-> state deref key)})

(defmethod readf :child
  [{:keys [parser query ast] :as env} k params]
  (let [value (parser env query)]
    (if (every? #(get value (:dispatch-key %1)) (:children ast)) ;; this is a hack :()
      {:value value}
      {:remote true})))

(defmulti mutatef om/dispatch)

(defmethod mutatef 'session/set-route
  [{:keys [state]} _ {:keys [name args]}]
  {:action (fn []
             (swap! state assoc :session/route name)
             (swap! state assoc :session/params args))})

(defn parse-grid-item [node]
  {:item (domina/attr node :id)
   :short (last (split (str (domina/attr node :id)) #"\."))
   :url (last (re-find #"[^']+'([^']+)" (domina/attr node :onPlay)))
   :title (domina/text (domina/single-node (dominax/xpath node "title")))
   :image (domina/text (domina/single-node (dominax/xpath node "image")))})

(defn parse-grid-items [root]
  (let [items (domina/nodes (dominax/xpath root "//sixteenByNinePoster"))]
    (map parse-grid-item items)))

(def resource-mapping
  {:categories {:url "http://www.svtplay.se/xml/categories.xml"
                :parser parse-grid-items}
   :category {:url "http://www.svtplay.se/xml/category/%(section)s/%(view)s.xml"
              :parser parse-grid-items}})

(defn reconciler-send []
  (fn [{:keys [remote] :as args} cb]
    (let [{:keys [dispatch-key params]} (get-in (om/query->ast remote) [:children 0 :children 0]) ;; ugh horrible hack
          {:keys [url parser]} (get resource-mapping dispatch-key)]
      (.send XhrIo (format url params)
             #(this-as this (cb {dispatch-key (parser (.getResponseXml this))}))))))

(defui Item
  static om/Ident
  (ident [this {:keys [item]}]
         [:item item])
  static om/IQuery
  (query [this]
         [:item :short :title :url :image])
  Object
  (render [this]
          (html
           (let [{:keys [short title url image]} (om/props this)
                 {:keys [item-action]} (om/get-computed this)]
             [:li
              [:div {:className "image" :tabIndex -1 :onClick #(item-action short)}
               [:img {:src image}]
               [:h2 [:span title]]]]))))

(def item-ui (om/factory Item {:keyfn :item}))

(defui Category
  static om/IQueryParams
  (params [this]
          {:item (om/get-query Item)
           :section nil
           :view nil})
  static om/IQuery
  (query [this]
         '[({:category ?item} {:section ?section :view ?view})])
  Object
  (render [this]
          (html
           (let [{:keys [category]} (om/props this)]
             [:div {:className "boxes-container"}
              [:ul
               (map (fn [item] (item-ui item)) category)]]))))

(defui Categories
  static om/IQuery
  (query [this]
         [{:categories (om/get-query Item)}])
  Object
  (render [this]
          (html
           (let [{:keys [categories]} (om/props this)
                 {:keys [update-route]} (om/get-computed this)
                 open-category #(update-route :category {:section %1 :view "alphabetical"})]
             [:div {:className "boxes-container"}
              [:ul
               (map (fn [item] (item-ui (om/computed item {:item-action open-category}))) categories)]]))))

(def route->component
  {:categories Categories
   :category Category})

(def route->factory
  (zipmap (keys route->component)
          (map om/factory (vals route->component))))

(def route->query
  (zipmap (keys route->component)
          (map om/get-query (vals route->component))))

(defui App
  static om/IQuery
  (query [this]
         (let [subquery (route->query :categories)]
           [:session/route :session/params {:child subquery}]))
  Object
  (componentWillUpdate [this next-props next-state]
                       (let [target (:session/route next-props)
                             params (:session/params next-props)
                             child-ast (om/query->ast (route->query target))
                             parametrized (assoc-in child-ast [:children 0 :params] params)] ;; O_o
                         (om/set-query! this {:query [:session/route :session/params {:child (om/ast->query parametrized)}]})))
  (update-route [this target params]
                (om/transact! this `[(session/set-route {:name ~target :args ~params})]))
  (render [this]
          (let [{:keys [session/route child]} (om/props this)]
            ((route->factory route)
             (om/computed child
                          {:update-route (fn [tgt arg] (.update-route this tgt arg))})))))

(def app-ui (om/factory App))

(def app-state (atom {:session/route :categories
                      :session/params {}}))

(def reconciler
  (om/reconciler
   {:state app-state
    :parser (om/parser {:read readf :mutate mutatef})
    :remotes [:remote]
    :send (reconciler-send)}))

(om/add-root! reconciler App (gdom/getElement "app"))
