# Changelog

All notable changes to AeonRuntime are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

AeonRuntime has **not been publicly released**. The version in `gradle.properties` (`1.0.0`) is the
pre-release development version inherited from the project's former name; nothing has been published.

## [Unreleased]

### Changed (project identity migration from `libtermux-android`)

- Project renamed to **AeonRuntime** (`AeonCoreX-Lab/AeonRuntime`). Full old-to-new mapping:
  [`docs/migration/RENAME_MAP.md`](docs/migration/RENAME_MAP.md).
- Kotlin/Java namespace `com.libtermux` → `com.aeoncorex.aeonruntime`; sub-packages `fs` → `filesystem`,
  `pkg` → `packagemanager`, `view` → `terminal`.
- Public API: `LibTermux` → `AeonRuntime`, `TermuxConfig` → `RuntimeConfig`, `termuxConfig {}` →
  `runtimeConfig {}`, `TermuxBridge` → `RuntimeBridge`, `TermuxBackgroundService` →
  `RuntimeBackgroundService`, `ShizukuTermux` → `ShizukuRuntimeBridge`, `TermuxLogger` → `RuntimeLogger`,
  `TermuxCommandException` → `RuntimeCommandException`.
- Entry point is now `AeonRuntime.initialize(context) { … }` (previously `LibTermux.init(context, config)`);
  `initialize`, `getInstance` and `reset` are `@JvmStatic`.
- Native library `libtermux_jni` → `aeonruntime_jni`; JNI symbols are now
  `Java_com_aeoncorex_aeonruntime_utils_NativeUtils_*`.
- Maven group `com.aeoncorex`; artifacts `aeonruntime-core`, `aeonruntime-os`, `aeonruntime-terminal`,
  `aeonruntime-shizuku`, `aeonruntime-bootstrap-{arm64,arm,x86_64,x86}`.
- Gradle plugin ids: `com.aeoncorex.aeonruntime.bootstrap-fetch`, `aeonruntime.bootstrap-module`;
  properties `aeonruntime.bootstrapTag` / `aeonruntime.bootstrapRepo`; extension property `termuxArch` →
  `bootstrapArch`; `Architecture.termuxName` → `bootstrapArch`.
- Bootstrap asset path `libtermux/bootstrap-<arch>.zip` → `aeonruntime/bootstrap-<arch>.zip`; data directory
  `files/libtermux` → `files/aeonruntime`; marker file `.libtermux_bootstrap_ok` →
  `.aeonruntime_bootstrap_ok`; DataStore `libtermux_distro_settings` → `aeonruntime_distro_settings`;
  notification channel `libtermux_bg` → `aeonruntime_bg`.
- Guest-visible bind mount `/termux-home` → `/aeonruntime-home` inside distributions (observable behaviour change).
- Sample app: package/`applicationId` `com.aeoncorex.aeonruntime.sample`, AeonCoreX palette, uses the DSL
  `initialize` overload. Bootstraps built for the old sample id must be rebuilt.
- Documentation rewritten; CI/CD reworked (see below).

### Added

- `scripts/verify_repo.py` and `scripts/verify_jni.py` (source-level checks, used by CI).
- CI now builds `:os`, `:terminal-view`, `:shizuku` and all bootstrap modules (previously only `:core` and
  `:sample`), runs `:os`/`:shizuku` unit tests, inspects exported JNI symbols of the built libraries for all
  four ABIs, and verifies the bootstrap asset path inside each bootstrap AAR.
- Maven publication configuration for `:terminal-view` and `:shizuku` (previously none).
- Documentation: architecture overview, execution-engine migration plan, local-AI design notes, integration
  guide, build guide, contributing guide, migration report, known issues.

### Fixed (pre-existing defects, kept in a separate commit from the rename)

- `:os` and `:shizuku` could not compile: `RuntimeLogger` (formerly `TermuxLogger`) was `internal` to `:core`
  but used from other modules. It is now public (intended for AeonRuntime modules only).
- `ShizukuRuntimeBridge` called `AeonRuntime.getInstance(context, config)`, which does not exist; it now calls
  `AeonRuntime.initialize(context, config)`.

These two fixes are **unverified by a compiler** in the environment where the migration was prepared.

### Not changed on purpose

- Upstream Termux identifiers and conventions (`TERMUX_PREFIX`, `TERMUX_HOME`, `TERMUX_VERSION`,
  `libtermux-exec.so`, `termux/termux-packages` URLs, `com.termux` prefix handling).
- Apache-2.0 license and source copyright headers.

See [`docs/migration/KNOWN_ISSUES.md`](docs/migration/KNOWN_ISSUES.md) for defects that were found and
deliberately left alone.

## Development history before the rename (as `libtermux-android` 1.0.0, 2026-04-01, unreleased)

Added: single entry point with Kotlin DSL and Java builder config; `VirtualFileSystem`; `BootstrapInstaller`
(resolves the latest Termux bootstrap via the GitHub API, progress `Flow`); `CommandExecutor` (suspend, streaming,
Python/Node runners); `SessionManager`; high-level bridge (run, Python, Node, bash, Ruby, Perl, PHP);
`PackageManager` (pkg, pip, npm, gem); background service; `TerminalView`; JNI layer (PTY, process forking,
symlink helpers); unit tests; CI; sample application.

Fixed: bootstrap download 404 (latest-release resolution); symlink processing from extracted `SYMLINKS.txt`;
progress reporting; zip-slip vulnerability in `extractZip`; User-Agent header on HTTP requests.
