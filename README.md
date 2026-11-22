# jolt-sandbox

[![tests](https://github.com/racklr-dev/jolt-sandbox/actions/workflows/ci.yml/badge.svg)](https://github.com/racklr-dev/jolt-sandbox/actions/workflows/ci.yml)

A convenience layer over [jolt-microvm](https://github.com/racklr-dev/jolt-microvm)
for running code and commands in a microVM sandbox.

Two modes:

- **one-shot**: `run` / `lang`: cold-boot any image (including distroless) into
  a command, return `{:stdout :stderr :exit}`. Good for isolated code execution.
- **warm**: `with-session` / pool: keep an alpine VM booted across many `exec`
  calls. Good for agents and long-running work.

## Use

```clojure
{:deps {racklr-dev/jolt-sandbox
        {:git/url "https://github.com/racklr-dev/jolt-sandbox"
         :git/sha "<latest sha>"}}}
```

```clojure
(require '[jolt.sandbox :as sb])

;; one-shot, any image (distroless python has no shell/sshd)
(sb/lang :python "print(42)")
;; => {:stdout "42\n" :stderr "" :exit 0}

(sb/lang :sh "echo hello")             ; alpine /bin/sh
(sb/lang :python-alpine "print(43)")   ; alpine + python3 (auto-seeded)
(sb/run {:image "gcr.io/distroless/nodejs22-debian13:latest"
         :cmd ["/nodejs/bin/node" "-e" "console.log(1+1)"]})

;; warm session
(sb/with-session [v {:image "alpine:3.20" :packages ["python3"] :project-dir "..."}]
  (sb/session-exec! v "python3 -c 'print(1)'"))

;; warm pool (reuses one VM across calls)
(def p (sb/make-pool {:image "alpine:3.20" :packages ["python3"] :project-dir "..."}))
(sb/pool-exec! p "python3 -c 'print(99)'")
(sb/close-pool! p)
```

## Languages

Built-in: `:python` (distroless python3), `:python-alpine` (alpine + python3),
`:node` (distroless nodejs), `:sh` (alpine `/bin/sh`).

```clojure
(sb/langs)   ; => #{:python :python-alpine :node :sh}

(sb/register-lang! :ruby
  {:image "alpine:3.20" :packages ["ruby"]
   :cmd (fn [code] ["/usr/bin/ruby" "-e" code])})
```

## opts

`run`/`lang` take `:timeout-ms`, `:cpus`, `:memory-mib`, `:cache-dir`,
`:project-dir`, `:arch`. `with-session` and `make-pool` take the full
`vm/start!` opts map (including `:mounts` and `:packages`).

## License

EPL-2.0
