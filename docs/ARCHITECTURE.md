# AeonRuntime architecture overview

This document describes what exists **today**. Planned work is kept in separate documents
([execution engines](architecture/execution-engines.md), [local AI](architecture/local-ai.md)) and is not
implemented.

## Modules

| Gradle module | Artifact (intended) | Namespace | Purpose |
|---|---|---|---|
| `:core` | `com.aeoncorex:aeonruntime-core` | `com.aeoncorex.aeonruntime` | Entry point, bootstrap, command execution, package manager, filesystem layout, JNI |
| `:os` | `aeonruntime-os` | `com.aeoncorex.aeonruntime.os` | Linux distributions: PRoot and chroot runners, VNC desktop, Compose components |
| `:terminal-view` | `aeonruntime-terminal` | `com.aeoncorex.aeonruntime.terminal` | `TerminalView` widget |
| `:shizuku` | `aeonruntime-shizuku` | `com.aeoncorex.aeonruntime.shizuku` | Optional elevated execution via Shizuku |
| `:bootstrap-arm64` / `-arm` / `-x86_64` / `-x86` | `aeonruntime-bootstrap-<abi>` | `com.aeoncorex.aeonruntime.bootstrap` | Per-ABI bootstrap payload (download at build time) |
| `:sample` | – | `com.aeoncorex.aeonruntime.sample` | Demo app (not published) |
| `build-logic` | – | `com.aeoncorex.aeonruntime.buildlogic` | Gradle plugins `com.aeoncorex.aeonruntime.bootstrap-fetch`, `aeonruntime.bootstrap-module` |

Only modules that exist as Gradle modules are listed. There are no modules for QEMU, UML or AI.

## `:core` packages (`com.aeoncorex.aeonruntime`)

| Package | Responsibility |
|---|---|
| *(root)* | `AeonRuntime` (SDK entry point), `RuntimeConfig` / `RuntimeConfigDsl` / `runtimeConfig`, `Architecture`, `LogLevel` |
| `bootstrap` | `BootstrapInstaller` (download, extract, symlinks, permissions, marker file), `BootstrapProvider` |
| `bridge` | `RuntimeBridge`: high-level API over the executor (shell, interpreters, files, packages) |
| `executor` | `CommandExecutor`, `ExecutionResult`, `SessionManager`, `RuntimeCommandException` |
| `filesystem` | `VirtualFileSystem`: directory layout and environment variables |
| `packagemanager` | `PackageManager` (`pkg`, pip, npm) |
| `service` | `RuntimeBackgroundService` (foreground service) |
| `initializer` | `AeonRuntimeInitializer` (AndroidX Startup) |
| `utils` | `NativeUtils` (JNI), `RuntimeLogger`, `ArchUtils`, `FileUtils`, extensions |
| `os` | `RootUtils` (root detection). Note: shares its package name with `:os` (split package, see known issues) |

## On-disk layout

Inside the host app's `filesDir`:

```
files/
├── usr/            bootstrap prefix (TERMUX_PREFIX); upstream layout, kept for binary compatibility
├── home/           HOME
└── aeonruntime/    library-private data (e.g. distros/), not part of the bootstrap prefix
```

The `usr` prefix has the host app's package name compiled into upstream binaries. This is why the consuming
app's `applicationId` and the bootstrap build must agree (see `bootstrap-builder`).

## Native layer

`libaeonruntime_jni.so` (CMake target `aeonruntime_jni`, sources in `core/src/main/jni`) exports 11 functions
under `Java_com_aeoncorex_aeonruntime_utils_NativeUtils_*`: PTY open/resize/close, fork/exec, signals, wait,
symlink helpers, architecture detection. Symbols are bound by name. `RegisterNatives()` registration was
considered and **not** adopted in this migration (it changes the native entry path and could not be tested on
a device); it is listed as future work. `scripts/verify_jni.py` enforces the name contract.

## Bootstrap data flow

1. `bootstrap-fetch` Gradle plugin downloads `bootstrap-<arch>.zip` at build time and places it at
   `assets/aeonruntime/bootstrap-<arch>.zip`.
2. `BootstrapInstaller` reads that asset (or downloads at runtime), extracts it into the prefix, applies
   `SYMLINKS.txt`, sets permissions and writes `.aeonruntime_bootstrap_ok`.
3. `VirtualFileSystem.buildEnv()` exports the environment, including upstream-named variables
   (`TERMUX_PREFIX`, `TERMUX_HOME`, …) that Termux packages read.

Producer and consumer paths must change together; `scripts/verify_repo.py` and the CI "bootstrap packaging"
job check this.

## Optional features stay optional

`:os`, `:terminal-view`, `:shizuku` and the bootstrap modules depend on `:core`; `:core` depends on none of
them. Future engines and the local-AI subsystem are intended to follow the same rule.
