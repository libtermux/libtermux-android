# Developer integration guide

All snippets use APIs that exist in the source. Unreleased: see the README's installation section for how to
consume the build locally.

## 1. Initialize once

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AeonRuntime.initialize(this) {
            logLevel = LogLevel.INFO
            env("MY_APP", "demo")           // extra environment variables
        }
    }
}
```

Configuration options that are actually consumed by the code today: `architecture`, `bootstrapVersion`,
`logLevel`, `maxCommandTimeoutMs`, `env(...)`, `customBootstrapUrl`, `bootstrapProvider`, and the notification
fields used by `RuntimeBackgroundService`. `autoInstall`, `enablePackageManager`, `backgroundExecutionEnabled`
and `enableLogging` exist on `RuntimeConfig` but nothing reads them yet (see known issues); do not rely on them.

`AeonRuntime` is a process-wide singleton. `initialize` returns the existing instance if there is one and
ignores the new configuration. `AeonRuntime.getInstance()` throws `IllegalStateException` before
initialization. From Java use `AeonRuntime.initialize(context, RuntimeConfig.builder().build())`.

The library also registers `AeonRuntimeInitializer` (AndroidX Startup) in its manifest.

## 2. Install the bootstrap

```kotlin
lifecycleScope.launch {
    runtime.install().collect { state ->
        when (state) {
            is InstallState.Downloading -> progress(state.progress)
            is InstallState.Extracting  -> progress(state.progress)
            is InstallState.Failed      -> error(state.error)
            InstallState.Completed, InstallState.AlreadyInstalled -> ready()
            else -> Unit
        }
    }
}
```

`runtime.isInstalled` reports readiness; `runtime.ensureReady()` throws if the bootstrap is missing.

Make sure the bootstrap matches your `applicationId` (see [`bootstrap-builder`](../bootstrap-builder/README.md))
and read the W^X note in the README for API 29+.

## 3. Run commands

```kotlin
val r = runtime.bridge.run("ls -la", workDir = null, env = mapOf("FOO" to "bar"))
if (r.isSuccess) println(r.stdout) else println("exit ${r.exitCode}: ${r.stderr}")

runtime.bridge.runStreamingText("ping -c 3 127.0.0.1").collect { line -> log(line) }

val out = runtime.bridge.python("print(1 + 1)")           // also node, ruby, perl, php, bash
runtime.bridge.writeFile("hello.txt", "hi")
```

Packages:

```kotlin
runtime.packageManager.install("git")
runtime.bridge.pipInstall("requests")
```

Note: `pkg install X` on a custom bootstrap only works for packages built for your prefix.

## 4. Distributions (`:os`)

```kotlin
val os = runtime.createOs {            // OsConfigDsl; defaults are fine for most apps
    // executionMode = ExecutionMode.PROOT
}
os.setupDistro(Distro.Debian12).collect { state -> /* DistroSetupState */ }
val res = os.execute(Distro.Debian12, "cat /etc/os-release")
os.install(Distro.Debian12, "curl")
```

`ExecutionMode.AUTO` uses chroot when root is detected and PRoot otherwise; `REAL_CHROOT` throws if the device
is not rooted. Graphical sessions: `os.createDesktopSession(distro)` with the Compose components in
`com.aeoncorex.aeonruntime.os.gui.compose`.

## 5. Terminal widget (`:terminal-view`)

```xml
<com.aeoncorex.aeonruntime.terminal.TerminalView
    android:layout_width="match_parent" android:layout_height="match_parent" />
```

`TerminalView.attachSession(handle: SessionHandle)` expects a `SessionHandle` from `SessionManager`.
`SessionManager` is a standalone class (constructed with a `CommandExecutor` and a `CoroutineScope`); it is
**not** currently exposed as a property of `AeonRuntime`.

## 6. Background execution

`RuntimeBackgroundService` is declared in the library manifest. Notification channel id/name/title come from
`RuntimeConfig`. Android 13+ apps must handle the notification permission themselves.

## 7. Shizuku (optional, `:shizuku`)

```kotlin
val shizuku = ShizukuRuntimeBridge.getInstance(context)
if (shizuku.isShizukuAvailable && !shizuku.isShizukuPermissionGranted) {
    shizuku.requestShizukuPermission()
}
val r = shizuku.runElevated("id")
if (!r.elevated) { /* Shizuku failed; the command ran unelevated as a fallback */ }
```

Shizuku does not grant unrestricted root, and not every privileged operation becomes available through it.
Always check `ElevatedResult.elevated`: on a Shizuku error the bridge falls back to normal execution.
