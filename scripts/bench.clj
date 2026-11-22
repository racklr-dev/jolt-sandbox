;; Throughput benchmark for jolt-sandbox: cold one-shot, warm pooled exec,
;; and a concurrent fan-out across several warm VMs.
(ns bench
  (:require [jolt.sandbox :as sb]))

(def proj "/tmp/jolt-sandbox")
(def cache "/tmp/bench-cache")
(def ws "/tmp/bench-ws")

(defn ms [f]
  (let [t (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) t) 1e6)))

(defn round [x] (Math/round (double x)))

;; 1. cold one-shot (boot a fresh VM per call)
(let [t (ms #(sb/lang :sh "true" :cache-dir cache :project-dir proj))]
  (println "cold one-shot:" (round t) "ms/call"))

;; 2. warm pooled exec: VM stays booted, but each exec is a fresh ssh connection
(let [p (sb/make-pool {:image "alpine:3.20" :packages ["python3"]
                       :cache-dir cache :workspace ws
                       :cpus 2 :memory-mib 512 :project-dir proj})
      _ (sb/pool-exec p "true")
      ts (doall (for [_ (range 20)] (ms #(sb/pool-exec p "true"))))
      avg (/ (reduce + ts) (count ts))]
  (println "warm pooled exec:" (round avg) "ms/call (20 samples)")
  (sb/close-pool! p))

;; 3. concurrent fan-out: 4 warm VMs, 40 execs spread across 4 threads
(let [nvm 4
      pools (doall (for [_ (range nvm)]
                     (sb/make-pool {:image "alpine:3.20" :packages ["python3"]
                                    :cache-dir cache :workspace ws
                                    :cpus 1 :memory-mib 256 :project-dir proj})))
      _ (doseq [p pools] (sb/pool-exec p "true"))
      n 40
      t (ms (fn []
              (let [futs (doall (for [i (range n)]
                                  (future (sb/pool-exec (nth pools (mod i nvm)) "true"))))]
                (doseq [f futs] @f))))]
  (println "concurrent:" n "execs over" nvm "VMs:" (round t) "ms ->"
           (round (/ (* n 1000) t)) "execs/sec")
  (doseq [p pools] (sb/close-pool! p)))

(println "bench done")
