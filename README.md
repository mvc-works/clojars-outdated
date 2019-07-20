
Clojars Outdated
----

> CLI tool for checking outdated Clojars dependencies from `shadow-cljs.edn`.

### Usage

![](https://img.shields.io/npm/v/@mvc-works/clojars-outdated.svg?label=clojars-outdated)

```bash
$ yarn global add @mvc-works/clojars-outdated
$ clojars-outdated
Reading shadow-cljs.edn
>>>>>>>>>>>> cost 15.156s to check.

These packages are up to date: mvc-works/hsl mvc-works/shell-page cumulo/recollect cumulo/reel cumulo/util respo/ui respo/feather respo/message cirru/bisection-key

Outdated packages:
mvc-works/ws-edn   0.1.2 -> 0.1.3
respo              0.10.2 -> 0.10.9
respo/alerts       0.3.10 -> 0.3.11

Not able to check: org.clojure/core.incubator
$
```

"Replace mode" to replace version numbers directly if detected:

```bash
replace=true clojars-outdated
```

To check if CLI outdated:

```bash
npm=true clojars-outdated
```

### License

MIT
