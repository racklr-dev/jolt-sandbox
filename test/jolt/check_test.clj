;; Load smoke for CI: resolves jolt-microvm transitively and prints the
;; language registry. Boots nothing (hosted runners have no KVM).
(ns jolt.test.sandbox-check
  (:require [jolt.sandbox :as sb]))

(println :langs (sb/langs))
