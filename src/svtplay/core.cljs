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
    (if-let [value (get-in st [key (:section params) (:view params)])]
      {:value value}
      {:remote true})))

(defmethod readf :programme
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if-let [value (get-in st [key (:programmeid params)])]
      {:value value}
      {:remote true})))

(defmethod readf :video
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
     :episodes (reverse episodes)}))

(defn parse-video [root]
  {:media-url (text-at-xpath root "//mediaURL")})

(def resource-mapping
  {:categories {:url "http://www.svtplay.se/xml/categories.xml"
                :parser #(parse-grid-items %1)}
   :category {:url "http://www.svtplay.se/xml/category/%(section)s/%(view)s.xml"
              :parser (fn [xml params] {(:section params) {(:view params) (parse-grid-items xml)}})}
   :programme {:url "http://www.svtplay.se/xml/title/%(programmeid)s.xml"
               :parser (fn [xml params] {(:programmeid params) (parse-programme xml)})}
   :video {:url "http://www.svtplay.se/xml/player/%(episodeid)s.xml?isClip=false"
           :parser parse-video}})

(defn reconciler-send []
  (fn [{:keys [remote] :as args} cb]
    (let [{:keys [dispatch-key params]} (get-in (om/query->ast remote) [:children 0 :children 0]) ;; ugh horrible hack
          {:keys [url parser]} (get resource-mapping dispatch-key)]
      (.send XhrIo (format url params)
             #(this-as this (cb {dispatch-key (parser (.getResponseXml this) params)}))))))

(defui ^:once Video
  static om/IQueryParams
  (params [this]
          {:episodeid nil})
  static om/IQuery
  (query [this]
         `[({:video [:media-url]} {:episodeid ?episodeid})])
  Object
  (componentWillUpdate [this next-props next-state]
                       (if-let [media-url (get-in next-props [:video :media-url])]
                         (let [elem (gdom/getElement "av-player")]
                           (.. js/webapis -avplay -open media-url)
                           (.. js/webapis -avplay (prepare))
                           (.. js/webapis -avplay (setDisplayRect
                                                (.-offsetLeft elem)
                                                (.-offsetTop elem)
                                                (.-offsetWidth elem)
                                                (.-offsetHeight elem)))
                           (.. js/webapis -avplay (play)))))
  (render [this]
          (html [:object {:id "av-player"
                          :type "application/avplayer"}])))

(defui ^:once Episode
  static om/Ident
  (ident [this {:keys [episode-id]}]
         [:episode-id episode-id])
  static om/IQuery
  (query [this]
         [:episode-id :title :image :summary :duration :air-time :availability])
  Object
  (render [this]
          (html
           (let [{:keys [episode-id title air-time image]} (om/props this)
                 {:keys [select-episode]} (om/get-computed this)]
             [:div {:onClick #(select-episode episode-id) :className "row"}
              [:img {:className "col-lg-4 img-responsive" :src (image :small)}]
              [:div {:className "details"}
               [:div {:className "title"} title]
               [:div {:className "airtime"} air-time]]]))))
(def episode-ui (om/factory Episode {:keyfn :episode-id}))

(defui ^:once Programme
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
  (componentWillMount [this]
                      (let [{:keys [programmeid]} (:params (om/get-computed this))]
                        (om/update-query! this #(update %1 :params conj {:programmeid programmeid}))))
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
                 {:keys [episode-id title summary image air-time duration availability] :as active} (.selected-episode this episodes)
                 {:keys [update-route]} (om/get-computed this)]
             [:div {:className "row programme"}
              (when active
                [:div {:className "col-lg-8 description"}
                 [:div {:className "row"}
                  [:div {:className "col-lg-9 poster-holder"}
                   [:img {:className "img-responsive poster"
                          :src (image :extralarge)
                          :onClick #(update-route :video {:episodeid episode-id})}]]
                  [:div {:className "col-lg-3"}
                   [:div {:className "row dates" :style {:vertical-align "bottom"}}
                    [:div [:p duration]]
                    [:div [:p air-time]]
                    [:div [:p availability]]]]]
                 [:div {:className "row"}
                  [:div {:className  "col-lg-12"}
                   [:p {:className "title"} title]
                   [:p {:className "summary"} summary]]]])
              [:div {:className "col-lg-4 episodes-list"}
               (for [episode episodes]
                 (episode-ui (om/computed episode {:select-episode #(.select-episode this %1)})))]
               ]))))

(defui ^:once Item
  static om/Ident
  (ident [this {:keys [item]}]
         [:item item])
  static om/IQuery
  (query [this]
         [:short :title :url :image])
  Object
  (render [this]
          (html
           (let [{:keys [short title url image]} (om/props this)
                 {:keys [item-action]} (om/get-computed this)]
             [:div {:className "image col-lg-4" :tabIndex -1 :onClick #(item-action short)}
              [:img {:src image}]
              [:h2 [:span title]]]))))

(def item-ui (om/factory Item {:keyfn :item}))

(defui ^:once Category
  static om/IQueryParams
  (params [this]
          {:item (om/get-query Item)
           :section nil
           :view nil})
  static om/IQuery
  (query [this]
         '[({:category ?item} {:section ?section :view ?view})])
  Object
  (componentWillMount [this]
                      (let [{:keys [section view]} (:params (om/get-computed this))]
                        (om/update-query! this #(update %1 :params conj {:section section :view view}))))
  (render [this]
          (html
           (let [{:keys [category]} (om/props this)
                 {:keys [update-route]} (om/get-computed this)
                 open-item #(update-route :programme {:programmeid %1})]
             [:div
              (for [entries (partition 3 3 [:padding] category)]
                [:div {:className "row"}
                 (map (fn [item]
                        (if-not (= item :padding)
                          (item-ui (om/computed item {:item-action open-item})))) entries)])]))))

(defui ^:once Categories
  static om/IQuery
  (query [this]
         [{:categories (om/get-query Item)}])
  Object
  (render [this]
          (html
           (let [{:keys [categories]} (om/props this)
                 {:keys [update-route]} (om/get-computed this)
                 open-category #(update-route :category {:section %1 :view "alphabetical"})]
             [:div
              (for [entries (partition 3 3 [:padding] categories)]
               [:div {:className "row"}
                (map (fn [item]
                       (if-not (= item :padding)
                         (item-ui (om/computed item {:item-action open-category})))) entries)])]))))

(def route->component
  {:categories Categories
   :category Category
   :programme Programme
   :video Video})

(def route->factory
  (zipmap (keys route->component)
          (map om/factory (vals route->component))))

(def route->query
  (zipmap (keys route->component)
          (map om/get-query (vals route->component))))

(defui ^:once App
  static om/IQuery
  (query [this]
         (let [subquery (route->query :categories)]
           [:session/route :session/params {:child subquery}]))
  Object
  (componentWillUpdate [this next-props next-state]
                       ;; horror to be cleaned up, but need to hack in params, and
                       ;; re-attach the original metadata to the updated query for
                       ;; the components to render correctly.
                       (let [target (route->query (:session/route next-props))
                             params (:session/params next-props)
                             child-ast (om/query->ast target)
                             parametrized (om/ast->query (assoc-in child-ast [:children 0 :params] params))]
                         (om/set-query! this {:query [:session/route
                                                      :session/params
                                                      {:child (with-meta parametrized (meta target))}]})))
  (update-route [this target params]
                (om/transact! this `[(session/set-route {:name ~target :args ~params})]))
  (render [this]
          (let [{:keys [session/route session/params child]} (om/props this)]
            (html [:div
                   [:div {:onClick #(.update-route this :categories)} "back"]
                   ((route->factory route)
                    (om/computed child
                                 {:update-route (fn [tgt arg] (.update-route this tgt arg))
                                  :params params}))]))))

(defonce app-state (atom {:session/route :categories
                          :session/params {}}))

(defonce reconciler
  (om/reconciler
   {:state app-state
    :parser (om/parser {:read readf :mutate mutatef})
    :remotes [:remote]
    :send (reconciler-send)}))

(om/add-root! reconciler App (gdom/getElement "app"))

(defn init []
  ;; workaround for #582
  (if-let [root (om/app-root reconciler)]
    (do
      (js/Object.setPrototypeOf root (.. @app-state -__proto__))
      (om/force-root-render! reconciler))
    (let [target (gdom/getElement "app")]
      (om/add-root! reconciler App target)
      (reset! app-state App))))
