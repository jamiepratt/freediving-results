(ns freediving.evaluation-rama-inspect
  "Read-only trace inspection pinned to Agent-o-rama 0.10.0 internals.
   The application integration uses only public APIs."
  (:require [com.rpl.agent-o-rama.impl.pobjects :as po]
            [com.rpl.agent-o-rama.impl.types :as types]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :refer [keypath]]))

(defn trace [ipc module-name client invoke]
  (let [{:keys [task-id agent-invoke-id]} invoke
        roots (rama/foreign-pstate ipc module-name (po/agent-root-task-global-name "evaluate"))
        root-id (rama/foreign-select-one [(keypath agent-invoke-id) :root-invoke-id]
                                         roots {:pkey task-id})
        query (:tracing-query (types/underlying-objects client))]
    (rama/foreign-invoke-query query task-id [[task-id root-id]] 10000)))
