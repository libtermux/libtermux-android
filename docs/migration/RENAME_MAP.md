# Rename map and classification of remaining "termux" references

This file records every class of identifier that changed, and every class of `termux` reference that was kept
on purpose. `scripts/verify_repo.py` enforces it: any `termux` token that does not match a documented upstream
pattern fails the check.

## 1. Project-owned identifiers: renamed

### Packages and namespaces

| Old | New |
|---|---|
| `com.libtermux` | `com.aeoncorex.aeonruntime` |
| `com.libtermux.fs` | `com.aeoncorex.aeonruntime.filesystem` |
| `com.libtermux.pkg` | `com.aeoncorex.aeonruntime.packagemanager` (lower-case, per Kotlin convention) |
| `com.libtermux.view` | `com.aeoncorex.aeonruntime.terminal` |
| `com.libtermux.{bridge,bootstrap,executor,service,initializer,utils,os,shizuku,sample}` | same sub-package under `com.aeoncorex.aeonruntime` |
| `com.libtermux.buildlogic` | `com.aeoncorex.aeonruntime.buildlogic` |

Deviations from the suggested layout, and why: no `core` sub-package (the entry point stays in the root
package, avoiding `…core.core`); `shizuku` is not placed under an `android` sub-package, to avoid any
ambiguity with the platform's `android.*` root package in fully-qualified references; `packagemanager` is
lower-case.

### Public API

| Old | New |
|---|---|
| `LibTermux` | `AeonRuntime` (`init` → `initialize`, plus `initialize(context) { … }` overload) |
| `TermuxConfig` / `TermuxConfigDsl` / `termuxConfig` | `RuntimeConfig` / `RuntimeConfigDsl` / `runtimeConfig` |
| `TermuxDsl` (DSL marker) | `RuntimeDsl` |
| `TermuxBridge` | `RuntimeBridge` |
| `TermuxBackgroundService` | `RuntimeBackgroundService` |
| `ShizukuTermux` | `ShizukuRuntimeBridge` |
| `TermuxLogger` | `RuntimeLogger` |
| `TermuxCommandException` | `RuntimeCommandException` |
| `LibTermuxInitializer` | `AeonRuntimeInitializer` |
| `Architecture.termuxName` | `Architecture.bootstrapArch` |

Kept generic (unchanged): `BootstrapInstaller`, `BootstrapProvider`, `PackageManager`, `ExecutionResult`,
`SessionManager`, `CommandExecutor`, `VirtualFileSystem`.

### Native

| Old | New |
|---|---|
| CMake project/target `libtermux_jni` | `aeonruntime_jni` (file `libaeonruntime_jni.so`) |
| `System.loadLibrary("libtermux_jni")` | `System.loadLibrary("aeonruntime_jni")` |
| `Java_com_libtermux_utils_NativeUtils_*` (11 symbols) | `Java_com_aeoncorex_aeonruntime_utils_NativeUtils_*` |
| `termux_jni.cpp` | `aeonruntime_jni.cpp` |
| log tags `LibTermux-JNI`, `-PTY`, `-Process` | `AeonRuntime-JNI`, `-PTY`, `-Process` |

### Build, Gradle, Maven, CI

| Old | New |
|---|---|
| `rootProject.name = "libtermux-android"` | `AeonRuntime` |
| Maven group `com.github.libtermux` | `com.aeoncorex` |
| artifacts `libtermux-android`, `libtermux-os`, `libtermux-android-bootstrap-<abi>` | `aeonruntime-core`, `aeonruntime-os`, `aeonruntime-bootstrap-<abi>`; new: `aeonruntime-terminal`, `aeonruntime-shizuku` |
| plugin `com.libtermux.bootstrap-fetch` | `com.aeoncorex.aeonruntime.bootstrap-fetch` |
| plugin `libtermux.bootstrap-module` | `aeonruntime.bootstrap-module` |
| properties `libtermux.bootstrapTag` / `.bootstrapRepo` | `aeonruntime.bootstrapTag` / `.bootstrapRepo` |
| extension property `termuxArch` | `bootstrapArch` |
| repository URLs (`…/libtermux-android`) | `https://github.com/AeonCoreX-Lab/AeonRuntime` |
| CI artifact names, release title | `aeonruntime-…`, `AeonRuntime <version>` |
| builder Docker image `libtermux-builder` | `aeonruntime-builder` |

### Runtime, on-disk and persisted names

| Old | New |
|---|---|
| asset `libtermux/bootstrap-<arch>.zip`, `libtermux/bootstrap-version.txt` | `aeonruntime/…` (producer plugin and consumer `BootstrapInstaller` changed together) |
| `filesDir/libtermux` (incl. `distros/`) | `filesDir/aeonruntime` |
| marker `.libtermux_bootstrap_ok` | `.aeonruntime_bootstrap_ok` |
| DataStore `libtermux_distro_settings` | `aeonruntime_distro_settings` |
| notification channel `libtermux_bg`, titles | `aeonruntime_bg`, AeonRuntime strings |
| guest mount `/termux-home` | `/aeonruntime-home` |
| temp scripts `/tmp/libtermux_*.py` | `/tmp/aeonruntime_*.py` |
| User-Agent `LibTermux-Android/1.0.0`, `libtermux-build/1.0` | `AeonRuntime/1.0.0`, `aeonruntime-build/1.0` |
| apt `Origin`/`Label` `libtermux` | `aeonruntime` |
| Gradle cache dir `libtermux-bootstrap-cache`, task group `libtermux` | `aeonruntime-bootstrap-cache`, `aeonruntime` |

### Sample application

`applicationId` and namespace → `com.aeoncorex.aeonruntime.sample` (this is the **consumer app** id and is
independent of the library namespace; the bootstrap-builder workflow's default `package_name` follows it).
Theme `Theme.LibTermux` → `Theme.AeonRuntime`, app label, strings, palette (AeonCoreX brand colours).

## 2. Upstream references: kept on purpose

| Reference | Why it stays |
|---|---|
| `TERMUX_PREFIX`, `TERMUX_HOME`, `TERMUX_VERSION`, `TERMUX_API_VERSION` environment variables | Read by Termux packages and scripts; renaming would break upstream binaries |
| `lib/libtermux-exec.so` (`LD_PRELOAD`) | Upstream `termux-exec` library name |
| `termux/termux-packages` (bootstrap releases, API URL, working directory names) | The upstream source of the bootstrap |
| `termux/proot-distro` | Upstream rootfs releases |
| `TERMUX_APP__PACKAGE_NAME`, `TERMUX_PACKAGES_REF`, `termux_packages_ref` | Upstream build-system property and the workflow input naming the upstream ref |
| `com.termux` default package and `/data/data/com.termux/…` in docs, checks and the prefix-mismatch detection | Upstream default prefix that bootstrap zips are built for |
| `SYMLINKS.txt` handling | Upstream bootstrap archive format |
| Capitalised "Termux" in prose and comments | Refers to the upstream project |

Exception recorded as a defect, not an intentional keep: the `com.termux` shebang in `RuntimeBridge.bash()`
(see `KNOWN_ISSUES.md`, B1). The verifier classifies it as upstream-pattern text, so it is not flagged.

Attribution and licensing: the Apache-2.0 `LICENSE` and the source-file copyright headers were left intact
(only the product name line in headers changed). No upstream licence text or attribution was removed.
