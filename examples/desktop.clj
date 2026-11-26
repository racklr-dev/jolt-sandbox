;; Boot a jolt-sandbox session with an alpine desktop and serve it over VNC.
;; Run from the jolt-sandbox repo root:
;;   sudo -u $USER --preserve-env env JOLT_BIN=/path/to/jolt \
;;     jolt run examples/desktop.clj
(require '[jolt.sandbox :as sb]
         '[jolt.microvm.desktop :as desktop])

(def profile :wayland) ; or :x11

(sb/with-session [v {:image "alpine:3.20"
                     :project-dir (System/getProperty "user.dir")
                     :packages (desktop/profile profile)
                     :memory-mib 1024
                     :cpus 2}]
  (println (if (= profile :wayland)
             (desktop/start-wayland! v)
             (desktop/start-x11! v)))
  (println "Open the url above in a VNC client, then press Enter to stop.")
  (read-line))
