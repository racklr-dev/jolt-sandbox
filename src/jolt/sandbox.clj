;; jolt.sandbox — a convenience layer over jolt-microvm for running code and
;; commands in a microVM sandbox.
;;
;; Two shapes:
;;   one-shot  (sb/run, sb/lang)      any image incl. distroless, cold boot each
;;                                     call, returns {:stdout :stderr :exit}
;;   warm      (sb/with-session, pool) alpine ssh-session VM kept alive across
;;                                     many execs — for agents/long-running work
(ns jolt.sandbox
  (:require [jolt.microvm :as vm]))

;; -- language registry --------------------------------------------------------
(defonce ^:private languages
  (atom
   {:python        {:image "gcr.io/distroless/python3-debian13:latest"
                    :cmd (fn [code] ["/usr/bin/python3" "-c" code])}
    :python-alpine {:image "alpine:3.20"
                    :packages ["python3"]
                    :cmd (fn [code] ["/usr/bin/python3" "-c" code])}
    :node          {:image "gcr.io/distroless/nodejs22-debian13:latest"
                    :cmd (fn [code] ["/nodejs/bin/node" "-e" code])}
    :sh            {:image "alpine:3.20"
                    :cmd (fn [code] ["/bin/sh" "-c" code])}}))

(defn register-lang!
  "Register a language. entry: {:image ... :cmd (fn [code] [exe arg...])
  and optionally :packages [...] (apk packages to seed into an alpine base).
  Returns the language keyword."
  [lng entry]
  (swap! languages assoc lng entry)
  lng)

(defn langs
  "Return the set of registered language keywords."
  []
  (set (keys @languages)))

;; -- one-shot ----------------------------------------------------------------
(defn run
  "Run a command one-shot in a sandbox VM (direct-exec; any image incl.
  distroless, no SSH/shell). Returns {:stdout :stderr :exit}.
  opts: :image :cmd [exe arg...], :packages (alpine seed), :arch, :cpus,
  :memory-mib, :cache-dir, :project-dir, :timeout-ms."
  [opts]
  (vm/run-image! opts))

(defn lang
  "Run code in a registered language (one-shot). Returns {:stdout :stderr :exit}.
  opts: :timeout-ms, :cpus, :memory-mib, :cache-dir, :project-dir."
  [lng code & {:as opts}]
  (let [{:keys [image packages cmd]} (get @languages lng)]
    (when-not image
      (throw (ex-info "unknown language (see sb/langs, sb/register-lang!)" {:language lng})))
    (run (merge {:image image :packages packages :cmd (cmd code)} opts))))

;; -- warm sessions ------------------------------------------------------------
(defmacro with-session
  "Boot a warm VM (SSH session mode, alpine) and run body with vm bound;
  stop! it in a finally. opts is the vm/start! opts map."
  [[sym opts] & body]
  `(vm/with-vm [~sym ~opts] ~@body))

(defn session-exec!
  "Convenience: run a command on a warm VM session, throwing on nonzero exit."
  [vm command & {:keys [cwd timeout-ms] :or {cwd "/workspace" timeout-ms 300000}}]
  (vm/exec! vm command :cwd cwd :timeout-ms timeout-ms))

;; -- pool ---------------------------------------------------------------------
(defrecord Pool [opts state])

(defn make-pool
  "Create a pool holding one warm VM per opts (reused across calls). opts is the
  vm/start! opts map."
  [opts]
  (->Pool opts (atom {:vm nil :busy false})))

(defn- acquire [pool]
  (locking (:state pool)
    (loop []
      (let [s @(:state pool)]
        (if (:busy s)
          (do (Thread/sleep 10) (recur))
          (let [vm (or (:vm s) (vm/start! (:opts pool)))]
            (swap! (:state pool) assoc :busy true :vm vm)
            vm))))))

(defn- release [pool _vm]
  (swap! (:state pool) assoc :busy false)
  nil)

(defmacro with-pooled
  "Check out the pool's warm VM, run body with vm bound, then return it warm."
  [vm-sym pool & body]
  `(let [~vm-sym (acquire ~pool)]
     (try
       ~@body
       (finally (release ~pool ~vm-sym)))))

(defn- with-pooled* [pool f]
  (let [v (acquire pool)]
    (try (f v) (finally (release pool v)))))

(defn pool-exec
  "Run a command on the pool's warm VM. Returns {:stdout :stderr :exit}, plus
  :interrupted? true when an :interrupt atom fired mid-command."
  [pool command & {:keys [cwd timeout-ms interrupt interrupt-poll-ms]
                   :or {cwd "/workspace" timeout-ms 300000}}]
  (with-pooled* pool (fn [v] (vm/exec v command :cwd cwd :timeout-ms timeout-ms
                                       :interrupt interrupt :interrupt-poll-ms interrupt-poll-ms))))

(defn pool-exec!
  "Like pool-exec but throws on nonzero exit."
  [pool command & {:keys [cwd timeout-ms] :or {cwd "/workspace" timeout-ms 300000}}]
  (with-pooled* pool (fn [v] (vm/exec! v command :cwd cwd :timeout-ms timeout-ms))))

(defn close-pool!
  "Stop the pool's warm VM. Call only when no thread is inside with-pooled."
  [pool]
  (locking (:state pool)
    (let [s @(:state pool)]
      (when-let [v (:vm s)] (vm/stop! v))
      (reset! (:state pool) {:vm nil :busy false})))
  :closed)
