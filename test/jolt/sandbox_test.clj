;; jolt-sandbox integration test: one-shot lang (distroless + alpine) and a
;; warm pooled VM. Run under a user with kvm group access.
(ns jolt.test.sandbox
  (:require [jolt.sandbox :as sb]
            [clojure.string :as str]))

(def proj "/tmp/jolt-sandbox")
(def cache "/tmp/jolt-sandbox-cache")

;; one-shot: distroless python (no shell/sshd in the image)
(let [r (sb/lang :python "print(42)" :cache-dir cache :project-dir proj)]
  (assert (zero? (:exit r)) (str "python exit " (:exit r) " err " (:stderr r)))
  (assert (str/includes? (:stdout r) "42") (str "stdout: " (:stdout r))))

;; one-shot: alpine /bin/sh
(let [r (sb/lang :sh "echo sh-ok" :cache-dir cache :project-dir proj)]
  (assert (zero? (:exit r)))
  (assert (str/includes? (:stdout r) "sh-ok")))

;; one-shot: alpine + package seed, direct-exec python3
(let [r (sb/lang :python-alpine "print(43)" :cache-dir cache :project-dir proj)]
  (assert (zero? (:exit r)) (str "python-alpine exit " (:exit r) " err " (:stderr r)))
  (assert (str/includes? (:stdout r) "43") (str "stdout: " (:stdout r))))

;; pool: one warm VM reused across execs
(let [p (sb/make-pool {:image "alpine:3.20" :packages ["python3"]
                       :cache-dir cache
                       :workspace "/tmp/jolt-sandbox-ws"
                       :cpus 2 :memory-mib 512
                       :project-dir proj})]
  (assert (= "a\n" (:stdout (sb/pool-exec p "echo a"))))
  (assert (str/includes? (:stdout (sb/pool-exec! p "python3 -c 'print(99)'")) "99"))
  (assert (str/includes? (:stdout (sb/pool-exec p "python3 -c 'print(100)'")) "100"))
  (sb/close-pool! p))

(println "jolt-sandbox: one-shot lang (distroless + alpine) + warm pool OK")
