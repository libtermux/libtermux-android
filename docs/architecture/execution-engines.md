# Execution engines: current state and migration plan

**Status: plan only. No `ExecutionEngine` interface has been added.**

## What exists

`:os` contains two runners selected by `ExecutionMode` (`AUTO`, `PROOT`, `REAL_CHROOT`):

| Runner | Needs root | Mechanism |
|---|---|---|
| `ProotRunner` | no | PRoot intercepts syscalls in userspace to emulate a chroot |
| `ChrootRunner` (+ `MountManager`) | yes | real `chroot` with bind mounts, driven through `su` |

Both are `internal` and are used by `OsEnvironment`. Their shape is similar but **distro-centric**:
`setup(distro, forceReinstall): Flow<DistroSetupState>`, `execute(distro, command, workDir)`, `login(distro)`,
`isInstalled(distro)`, `uninstall(distro)`, `rootfsDir(distro)`. There is no engine-level lifecycle
(`start`/`stop`/`status`) because neither runner is a long-lived engine process.

## Why no interface yet

The suggested

```kotlin
interface ExecutionEngine {
    suspend fun initialize(config: EngineConfig)
    suspend fun start(): EngineResult
    suspend fun stop()
    suspend fun getStatus(): EngineStatus
}
```

describes a long-lived engine. It matches a VM-style engine (QEMU, UML) much better than PRoot/chroot, where
"start" is per-command. Adding it now would require either contorting the existing runners or creating
implementations that do nothing. Neither is acceptable, so this is deferred.

## Proposed path

1. **Introduce a capability model first.** An `EngineCapabilities` value (needs root, isolation level,
   supports GUI, supports networking mode, supports snapshots…) so engines never imply equal isolation.
2. **Extract the common distro operations** (`setup`, `execute`, `login`, `uninstall`) from the two runners into
   an internal interface and let `OsEnvironment` depend on it. Signatures differ slightly today (`login` is
   `suspend` in `ChrootRunner` only) and must be reconciled. Intended as a behaviour-preserving refactor, to be
   covered by the existing `:os` unit tests.
3. **Add the lifecycle layer** (`initialize/start/stop/getStatus`) as a *separate* interface for engines that
   have a long-lived process. PRoot/chroot would not implement it.
4. **Add QEMU / UML only when real implementations exist.** Each must declare its capabilities and report
   itself unavailable (not "available but failing") when its binaries or kernel support are missing.

Resource cleanup rules for every engine: `stop()` is idempotent, always releases mounts / processes / file
descriptors, and errors are surfaced as typed results rather than swallowed.

## Package placement

Existing: `os/proot`, `os/chroot`, `os/distro`, `os/gui`, `os/registry`, `os/settings`.
Reserved names for the future, to be created **only** with real code: `os/engine`, `os/qemu`, `os/uml`.
