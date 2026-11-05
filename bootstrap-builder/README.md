# bootstrap-builder

Termux binaries have the prefix `/data/data/com.termux/files/usr` compiled in, so `apt`/`dpkg`/`pkg` only work
for an app whose package name is `com.termux`. For any other app, the bootstrap and every package you want to
`apt install` must be **built from source for your package name**. This directory automates that, using the
upstream `termux-packages` fork flow (`TERMUX_APP__PACKAGE_NAME` plus your own apt repository).

`termux-packages` and the Termux names used by its build system are **upstream** and are intentionally kept as
they are.

## The package name must match your app

The package name you build for must equal the **`applicationId` of the Android app that consumes AeonRuntime**.
It is unrelated to AeonRuntime's own library namespace (`com.aeoncorex.aeonruntime`).

For the bundled sample app the `applicationId` is `com.aeoncorex.aeonruntime.sample`, which is the default of
the workflow input `package_name`. Bootstraps built earlier for the pre-migration sample id are **not**
compatible with the new id and must be rebuilt.

`BootstrapInstaller` logs an error when the zip's `SYMLINKS.txt` shows a prefix built for a different package
than the running app.

## One-time setup (GitHub Actions)

1. Settings → Pages → Source: **GitHub Actions**.
2. Actions → **Build custom bootstrap** → Run workflow. Inputs: your `applicationId`, architectures, extra
   packages (for example `python git`) and optionally a `termux-packages` ref. Expect hours per architecture.
3. Result: a GitHub Release containing `bootstrap-<arch>.zip`, and an apt repository at
   `https://<owner>.github.io/<repo>/apt` (written into the bootstrap's `sources.list`).

## Use it in the Gradle build

```bash
./gradlew :sample:assembleDebug -Paeonruntime.bootstrapRepo=<owner>/<repo>
```

or set it in the plugin extension:

```kotlin
bootstrapFetch { bootstrapRepo.set("<owner>/<repo>") }
```

The default remains `termux/termux-packages` (upstream bootstrap, built for `com.termux`). A pinned release can
be selected with `-Paeonruntime.bootstrapTag=<tag>`.

The fetched zip is embedded as `assets/aeonruntime/bootstrap-<arch>.zip`, the path `BootstrapInstaller` reads.

## Rules

- One repository per package name. Release tags look like `bootstrap-YYYY.MM.DD-rN+apt.android-7`.
- Build **every** architecture you ship in the same run: each run replaces the whole Pages site.
- `pkg install X` only works for packages listed in `extra_packages` (their debs are the only ones built for
  your prefix).
- The apt repository is unsigned (`[trusted=yes]`, served over HTTPS). Its apt `Origin`/`Label` is
  `aeonruntime`.
- Local run: see the header of `build-bootstrap.sh`.
