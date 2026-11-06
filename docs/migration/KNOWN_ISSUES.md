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

A1 and A2 were not compiled when written. The first real CI run on GitHub then confirmed that `:core`
compiles and revealed further **baseline** compile errors in `:os` and `:shizuku` (each also present in the
pre-migration code, so not caused by the rename). They are fixed in a second separate commit:

| # | Error reported by CI | Cause (also in baseline) | Fix |
|---|---|---|---|
| A3 | `OsConfig.kt`: unresolved `RuntimeDsl` | `@TermuxDsl` was used without an import; the marker lives in another package | added `import com.aeoncorex.aeonruntime.RuntimeDsl` |
| A4 | `DistroSettings.kt`: unresolved `intKey` / `stringKey` / `booleanKey` | helpers were never defined anywhere | private aliases for `intPreferencesKey` / `stringPreferencesKey` / `booleanPreferencesKey` |
| A5 | `DistroDisplay.kt`: `Size` vs `IntSize` | `drawImage(dstSize = …)` takes `IntSize` | `IntSize((w / scale).toInt(), (h / scale).toInt())` |
| A6 | `VncClient.kt`: `isActive` receiver mismatch | `isActive` used in a plain `suspend fun` | `currentCoroutineContext().isActive` |
| A7 | `ShizukuRuntimeBridge.kt`: unresolved `add/removeOnRequestPermissionResultListener` and `Shizuku.exec` | wrong method names for Shizuku API 13.1.5; `Shizuku.exec` does not exist | renamed to `add/removeRequestPermissionResultListener`; replaced `exec` with a reflective call to `Shizuku.newProcess` (see B15) |
| A8 | `ElevatedResultTest.kt`: unresolved `junit` / `Test` / `assert*` (second CI run) | `:shizuku` has a unit test but declared no test dependency (also in baseline) | `testImplementation(libs.junit)` in `shizuku/build.gradle.kts` |
| A9 | `DistroTest`: 5 of 38 `:os` tests fail with `NullPointerException` (third CI run) | **Real runtime bug, also in baseline.** `Distro.all` was built eagerly in the companion. If a built-in such as `Distro.Kali` is touched first, `Distro` is initialised while `Kali` is still under construction, so `all` permanently holds a `null` for it; later `all`/`fromId` calls then crash | `Distro.all` is now `by lazy` |
| A10 | `:terminal-view:mergeDebugConsumerProguardFiles`: *Supplied consumer proguard configuration does not exist* (fourth CI run) | `consumerProguardFiles("consumer-rules.pro")` pointed at a file that was never created (also in baseline). Same for `os/proguard-rules.pro` and `sample/proguard-rules.pro` (not yet hit by CI) | created the three files (comment-only placeholders, no behaviour change); `verify_repo.py` now checks that every shrinker file referenced from a module build script exists |

A3–A10 are verified only by the compiler errors they address; they were not compiled after the fix. More
errors may appear in code the compiler had not reached yet (remaining `:os` and `:shizuku` sources, their
tests, `:terminal-view`, `:sample`).

`scripts/verify_repo.py` did **not** catch A3–A7: it checks imports that exist, not references that lack an
import, and cannot know third-party API signatures. It is a complement to a compiler, not a substitute.

## B. Found and deliberately left unchanged

| # | Issue | Where | Evidence | Suggested fix |
|---|---|---|---|---|
| B1 | `RuntimeBridge.bash()` writes the shebang `#!/data/data/com.termux/files/usr/bin/bash` and then executes the file directly, so the kernel honours it. For any app that is not `com.termux` that interpreter path does not exist, so `bash()` very likely fails. | `core/.../bridge/RuntimeBridge.kt:101`, `executor/CommandExecutor.kt:153` | Confirmed by reading | Use the app's own prefix (`vfs.prefixDir`) in the shebang, or run `bash <file>` |
| B2 | Four `RuntimeConfig` options are never read anywhere: `autoInstall`, `enablePackageManager`, `backgroundExecutionEnabled`, `enableLogging`. | `core/.../RuntimeConfig.kt` | Confirmed by search | Implement or remove (API change) |
| B3 | `SessionManager` is not exposed by `AeonRuntime`. The previous README documented `…sessions.createSession(…)`, which does not exist. `TerminalView.attachSession` needs a `SessionHandle` obtained from it. | `AeonRuntime.kt`, `executor/SessionManager.kt` | Confirmed by reading | Expose a `sessions` property or document manual construction |
| B4 | `DefaultBootstrapProvider.isAvailable()` is true whenever `nativeLibraryDir` has any file. Core's own `libaeonruntime_jni.so` is always there, so it likely reports available even without a `bootstrap-<abi>` artifact. | `bootstrap/BootstrapProvider.kt:84` | Confirmed by reading; behaviour inferred | Check for the specific binaries (e.g. `bash`) |
| B5 | Four bootstrap modules share one namespace (`com.aeoncorex.aeonruntime.bootstrap`, set by the convention plugin), identical to a `:core` package name. AGP normally rejects duplicate namespaces in one app. | `aeonruntime.bootstrap-module.gradle.kts:21` | Suspected | Per-module namespace, e.g. `…bootstrap.arm64` |
| B6 | `:terminal-view` and `:shizuku` declare `minSdk 24` but depend on `:core` (`minSdk 26`). | `*/build.gradle.kts` | Suspected (manifest-merge error possible) | Align to 26 |
| B7 | ~~Referenced shrinker files do not exist~~ **Resolved (see A10).** | | | |
| B8 | `:core` consumer rule `-keep class com.aeoncorex.aeonruntime.** { *; }` keeps the whole library and defeats shrinking for consumers. | `core/consumer-rules.pro:2` | Confirmed by reading | Narrow to JNI classes and reflective entry points |
| B9 | The default bootstrap fallback tag (`bootstrap-2026.05.24-r1+apt.android-7`) is duplicated in `RuntimeConfig`, `BootstrapInstaller`, the Gradle plugin and each `bootstrap-*` module. | see left | Confirmed by search | Single source of truth |
| B10 | `RootUtils` lives in `:core` under package `…aeonruntime.os`, which is also the root package of the `:os` module (split package). | `core/.../os/RootUtils.kt` | Confirmed by structure | Move to a privilege/platform package |
| B11 | The library version string `1.0.0` is hard-coded in several places (`gradle.properties`, an `initializer.BuildConfig` object, log text and User-Agent) instead of coming from one source. | various | Confirmed by search | Derive from build config |
| B12 | `NativeUtils.load()` is not synchronised and records success even if `System.loadLibrary` throws. | `utils/NativeUtils.kt` | Confirmed by reading | Set the flag only on success; guard with a lock |
| B13 | The release workflow's publish task name was carried over unchanged and is unverified against the pinned publishing plugin. | `.github/workflows/release.yml` | Unverified | Check `./gradlew :core:tasks --group publishing` |
| B14 | `ShizukuRuntimeBridge.runElevated` silently falls back to unelevated execution when Shizuku fails (the result's `elevated` flag is `false`). Callers must check it. | `ShizukuRuntimeBridge.kt` | Confirmed by reading | Consider a strict mode that fails instead |

| B15 | `Shizuku.newProcess` is not public API in Shizuku 13.x; the supported route is a `UserService`. `ShizukuRuntimeBridge` now calls it reflectively as an unsupported workaround that may break with future Shizuku versions. | `ShizukuRuntimeBridge.kt` (`newShizukuProcess`) | Confirmed by reading; not run on a device | Implement a `UserService` |

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
