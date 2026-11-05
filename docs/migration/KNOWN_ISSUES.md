# Known issues and technical debt

Defects and inconsistencies found while auditing the repository for the AeonRuntime migration. They are
listed separately on purpose: the rename was kept free of unrelated behaviour changes, except for the two
compile fixes in section A, which live in their own commit.

**Evidence levels.** *Confirmed by reading* = verified by reading the code, not by running it. *Suspected* =
plausible from the build files but not verified. No Android SDK, Gradle distribution or Kotlin compiler was
available in the migration environment, so **nothing here was confirmed by compiling or running**.

## A. Fixed in a separate commit (pre-existing compile blockers)

Commit `fix (pre-existing, NOT part of the rename)`; revert it if you prefer to handle these differently.

| # | Issue | Evidence |
|---|---|---|
| A1 | `RuntimeLogger` (was `TermuxLogger`) was `internal` to `:core` but imported by 6 files in `:os` and 1 in `:shizuku`. Kotlin `internal` is module-scoped, so those modules could not compile. Fix: made it `public` (documented as intended for AeonRuntime modules, not a stable API). A cleaner long-term option is a dedicated opt-in annotation or a small shared API. | Confirmed by reading |
| A2 | `ShizukuRuntimeBridge` called `AeonRuntime.getInstance(context, config)`; only `getInstance()` (no arguments) exists. Fix: `AeonRuntime.initialize(context, config)`. | Confirmed by reading |

Both fixes are **uncompiled**. They probably explain why upstream CI only ever built `:core` and `:sample`.
The new CI builds every module; if other baseline compile errors exist in `:os`, `:shizuku` or
`:terminal-view`, CI will show them.

## B. Found and deliberately left unchanged

| # | Issue | Where | Evidence | Suggested fix |
|---|---|---|---|---|
| B1 | `RuntimeBridge.bash()` writes the shebang `#!/data/data/com.termux/files/usr/bin/bash` and then executes the file directly, so the kernel honours it. For any app that is not `com.termux` that interpreter path does not exist, so `bash()` very likely fails. | `core/.../bridge/RuntimeBridge.kt:101`, `executor/CommandExecutor.kt:153` | Confirmed by reading | Use the app's own prefix (`vfs.prefixDir`) in the shebang, or run `bash <file>` |
| B2 | Four `RuntimeConfig` options are never read anywhere: `autoInstall`, `enablePackageManager`, `backgroundExecutionEnabled`, `enableLogging`. | `core/.../RuntimeConfig.kt` | Confirmed by search | Implement or remove (API change) |
| B3 | `SessionManager` is not exposed by `AeonRuntime`. The previous README documented `…sessions.createSession(…)`, which does not exist. `TerminalView.attachSession` needs a `SessionHandle` obtained from it. | `AeonRuntime.kt`, `executor/SessionManager.kt` | Confirmed by reading | Expose a `sessions` property or document manual construction |
| B4 | `DefaultBootstrapProvider.isAvailable()` is true whenever `nativeLibraryDir` has any file. Core's own `libaeonruntime_jni.so` is always there, so it likely reports available even without a `bootstrap-<abi>` artifact. | `bootstrap/BootstrapProvider.kt:84` | Confirmed by reading; behaviour inferred | Check for the specific binaries (e.g. `bash`) |
| B5 | Four bootstrap modules share one namespace (`com.aeoncorex.aeonruntime.bootstrap`, set by the convention plugin), identical to a `:core` package name. AGP normally rejects duplicate namespaces in one app. | `aeonruntime.bootstrap-module.gradle.kts:21` | Suspected | Per-module namespace, e.g. `…bootstrap.arm64` |
| B6 | `:terminal-view` and `:shizuku` declare `minSdk 24` but depend on `:core` (`minSdk 26`). | `*/build.gradle.kts` | Suspected (manifest-merge error possible) | Align to 26 |
| B7 | Referenced shrinker files do not exist: `os/proguard-rules.pro`, `terminal-view/consumer-rules.pro`, `sample/proguard-rules.pro` (the sample minifies release builds). | `*/build.gradle.kts` | Confirmed (files absent); effect unverified | Add the files or drop the references |
| B8 | `:core` consumer rule `-keep class com.aeoncorex.aeonruntime.** { *; }` keeps the whole library and defeats shrinking for consumers. | `core/consumer-rules.pro:2` | Confirmed by reading | Narrow to JNI classes and reflective entry points |
| B9 | The default bootstrap fallback tag (`bootstrap-2026.05.24-r1+apt.android-7`) is duplicated in `RuntimeConfig`, `BootstrapInstaller`, the Gradle plugin and each `bootstrap-*` module. | see left | Confirmed by search | Single source of truth |
| B10 | `RootUtils` lives in `:core` under package `…aeonruntime.os`, which is also the root package of the `:os` module (split package). | `core/.../os/RootUtils.kt` | Confirmed by structure | Move to a privilege/platform package |
| B11 | The library version string `1.0.0` is hard-coded in several places (`gradle.properties`, an `initializer.BuildConfig` object, log text and User-Agent) instead of coming from one source. | various | Confirmed by search | Derive from build config |
| B12 | `NativeUtils.load()` is not synchronised and records success even if `System.loadLibrary` throws. | `utils/NativeUtils.kt` | Confirmed by reading | Set the flag only on success; guard with a lock |
| B13 | The release workflow's publish task name was carried over unchanged and is unverified against the pinned publishing plugin. | `.github/workflows/release.yml` | Unverified | Check `./gradlew :core:tasks --group publishing` |
| B14 | `ShizukuRuntimeBridge.runElevated` silently falls back to unelevated execution when Shizuku fails (the result's `elevated` flag is `false`). Callers must check it. | `ShizukuRuntimeBridge.kt` | Confirmed by reading | Consider a strict mode that fails instead |

## C. Behaviour changes caused by the rename (not bugs)

- Guest bind mount `/termux-home` is now `/aeonruntime-home` inside distributions.
- On-disk names changed (`files/aeonruntime`, `.aeonruntime_bootstrap_ok`, DataStore
  `aeonruntime_distro_settings`, notification channel `aeonruntime_bg`). Existing installs of the old build
  would be treated as fresh. Acceptable because there has been no public release.
- Sample `applicationId` changed, so bootstraps built for the old sample id must be rebuilt.
- apt `Origin`/`Label` of the custom repository changed to `aeonruntime`.

## D. Limits of this migration

- JNI uses name-based binding. `RegisterNatives()` was not adopted (see `docs/ARCHITECTURE.md`).
- No `ExecutionEngine` interface was added (see `docs/architecture/execution-engines.md`).
- `:terminal-view` and `:shizuku` gained Maven publication blocks that did not exist before; they mirror the
  other modules but have never been exercised.
- `:terminal-view` still defaults to a Catppuccin colour scheme; only the sample was re-themed.
