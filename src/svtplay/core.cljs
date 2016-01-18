(ns svtplay.core
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.events]
            [domina.core :as domina]
            [domina.xpath :as dominax :refer [xpath]]
            [sablono.core :as html :refer-macros [html]]
            [goog.events :as events]
            [cuerdas.core :refer [format split replace]])
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

(defmethod readf :programme
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if (contains? st key)
      {:value (get st key)}
      {:remote true})))

(defmethod readf :default
  [{:keys [state] :as env} key params]
  {:value (-> state deref key)})

(defmethod readf :child
  [{:keys [parser query ast target] :as env} k params]
  (let [v (parser env query target)]
    (if (or (empty? v) (nil? target))
      {:value v}
      {target (update ast assoc :query v)})))

(defmulti mutatef om/dispatch)

(defmethod mutatef 'session/set-route
  [{:keys [state]} _ {:keys [name args]}]
  {:action #(swap! state assoc
                   :session/route name
                   :session/params args)})

(defn text-at-xpath [node path]
  (domina/text (domina/single-node (dominax/xpath node path))))

(defn parse-grid-item [node]
  {:item (last (re-find #".+/([^.]+).xml" (domina/attr node :onPlay)))
   :short (last (re-find #".+/([^.]+).xml" (domina/attr node :onPlay)))
   :url (last (re-find #"[^']+'([^']+)" (domina/attr node :onPlay)))
   :title (text-at-xpath node "title")
   :image (text-at-xpath node "image")})

(defn parse-grid-items [root]
  (let [items (domina/nodes (dominax/xpath root "//sixteenByNinePoster"))]
    (map parse-grid-item items)))

(defn parse-episode [node]
  (let [preview (domina/single-node (dominax/xpath node "preview/longDescriptionPreview"))
        image (replace (text-at-xpath node "image") "/medium/" "/%(size)s/")]
    {:episode-id (int (last (re-find #"(\d+)$" (domina/attr node :id))))
     :label (text-at-xpath node "label") ;; skip it?
     :title (text-at-xpath preview "title")
     :image #(format image {:size (name %)}) ;; :medium, :large :extralarge
     :summary (text-at-xpath preview "summary")
     :duration (text-at-xpath preview "metadata/label[1]")
     :air-time (text-at-xpath preview "metadata/label[2]")
     :availability (text-at-xpath preview "metadata/label[3]")}))

(defn parse-episodes [nodes]
  (doall (map parse-episode (domina/nodes (dominax/xpath nodes "stash/items/twoLineEnhancedMenuItem")))))

(defn parse-programme [root]
  (let [title-node (dominax/xpath root "//tabWithTitle")
        title (text-at-xpath title-node "title")
        subtitle (text-at-xpath title-node "subtitle")
        episode-nodes (dominax/xpath root "//navigationItem[@id='episodes']")
        episodes (parse-episodes episode-nodes)]
    {:title title
     :subtitle subtitle
     :summary #(.log js/console "fooo")
     :image ""
     :episodes episodes}))

(def resource-mapping
  {:categories {:url "http://www.svtplay.se/xml/categories.xml"
                :parser parse-grid-items}
   :category {:url "http://www.svtplay.se/xml/category/%(section)s/%(view)s.xml"
              :parser parse-grid-items}
   :programme {:url "http://www.svtplay.se/xml/title/%(programmeid)s.xml"
               :parser parse-programme}})

(defn reconciler-send []
  (fn [{:keys [remote] :as args} cb]
    (let [{:keys [dispatch-key params]} (get-in (om/query->ast remote) [:children 0 :children 0]) ;; ugh horrible hack
          {:keys [url parser]} (get resource-mapping dispatch-key)]
      (.log js/console "got params: " (str params "  " (format url  params)))
      (.send XhrIo (format url params)
             #(this-as this (cb {dispatch-key (parser (.getResponseXml this))}))))))

(defui Episode
  static om/Ident
  (ident [this {:keys [episode-id]}]
         [:episode-id episode-id])
  static om/IQuery
  (query [this]
         [:episode-id :title :image :summary :duration :air-time :availability])
  Object
  (render [this]
          (html
           (let [{:keys [episode-id title image]} (om/props this)
                 {:keys [select-episode]} (om/get-computed this)]
             [:div {:onClick #(select-episode episode-id) :className "row" :style {:height "100%"} }
              [:div {:className "col-lg-4"}
               [:img {:className "img-responsive" :src (image :small) :style {:border "1px solid blue"}}]]
              [:div {:classname "col-lg-8"}
               [:h3  title]]]))))
(def episode-ui (om/factory Episode {:keyfn :episode-id}))

(defui Programme
  static om/Ident
  (ident [this {:keys [programmeid]}]
         [:programmeid programmeid])
  static om/IQueryParams
  (params [this]
          {:episodes (om/get-query Episode)
           :programmeid nil})
  static om/IQuery
  (query [this]
         `[({:programme [:title :subtitle :summary :image {:episodes ~(om/get-query Episode)}]} {:programmeid ?programmeid})])
  Object
  (initLocalState [this]
                  {:selected nil})
  (select-episode [this episode-id]
                  (om/set-state! this {:selected episode-id}))
  (selected-episode [this episodes]
                    (let [selected (om/get-state this :selected)]
                      (if (nil? selected)
                        (first episodes)
                        (first (filter #(= selected (:episode-id %)) episodes)))))
  (render [this]
          (html
           (let [{:keys [programme]} (om/props this)
                 {:keys [episodes summary]} programme
                 {:keys [title summary image air-time duration availability] :as active} (.selected-episode this episodes)]
             [:div {:className "row"}
              (when active
                [:div {:className "col-lg-8 col-md-8"}
                 [:div {:className "row"}
                  [:div {:className "col-lg-9"}
                   [:img {:className "img-responsive" :src (image :extralarge)}]]
                  [:div {:className "col-lg-3"}
                   [:div {:className "row"}
                    [:div [:h3 duration]]
                    [:div [:h3 air-time]]
                    [:div [:h3 availability]]]]]
                 [:div {:className "row"}
                  [:div {:className  "col-lg-12"}
                   [:h2 title]
                   [:h3 summary]]]])
              [:div {:className "col-lg-4 col-md-4"}
               [:ul {:style {:list-style "none"}}
                (for [episode episodes]
                  [:li (episode-ui (om/computed episode {:select-episode #(.select-episode this %1)}))])]
               ]]))))

(defui Item
  static om/Ident
  (ident [this {:keys [item-id]}]
         [:item item-id])
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
           (let [{:keys [category]} (om/props this)
                 {:keys [update-route]} (om/get-computed this)
                 open-item #(update-route :programme {:programmeid %1})]
             [:div {:className "boxes-container"}
              [:ul
               (map (fn [item] (item-ui (om/computed item {:item-action open-item}))) category)]]))))

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
   :category Category
   :programme Programme})

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
                             orig-query (route->query target)
                             child-ast (om/query->ast orig-query)
                             parametrized (om/ast->query (assoc-in child-ast [:children 0 :params] params))
                             metadatad (with-meta parametrized (meta orig-query))] ;; O_o
                         (om/set-query! this {:query [:session/route
                                                      :session/params
                                                      {:child metadatad}]})))
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
