# bootstrap-builder

Termux binaries have the prefix `/data/data/com.termux/files/usr` compiled in, so `apt`/`dpkg`/`pkg`
only work for an app whose package name is `com.termux`. For any other app the bootstrap and the packages
you want to `apt install` must be **built from source for your package name**. This directory automates that
(termux-packages fork flow: `TERMUX_APP__PACKAGE_NAME` + own apt repo).

## One-time setup (GitHub Actions)
1. Settings → Pages → Source: **GitHub Actions**.
2. Actions → **Build custom bootstrap** → Run workflow. Inputs: your `applicationId`, architectures, extra packages
   (e.g. `python git`). Takes hours per architecture.
3. Result: a GitHub Release with `bootstrap-<arch>.zip` and an apt repo at
   `https://<owner>.github.io/<repo>/apt` (baked into the bootstrap's `sources.list`).

## Use it in the Gradle build
```
./gradlew :sample:assembleDebug -Plibtermux.bootstrapRepo=<owner>/<repo>
```
(or `bootstrapRepo.set("<owner>/<repo>")` in the `bootstrapFetch { }` block). Default stays `termux/termux-packages`.

## Rules
* One repo per package name (release tags are `bootstrap-YYYY.MM.DD-rN+apt.android-7`).
* Build **every** architecture you ship in the same run: each run replaces the whole Pages site.
* `pkg install X` only works for packages listed in `extra_packages` (their debs are the only ones built for your prefix).
* The apt repo is unsigned (`[trusted=yes]`, served over HTTPS).
* Local run: see the header of `build-bootstrap.sh`.
