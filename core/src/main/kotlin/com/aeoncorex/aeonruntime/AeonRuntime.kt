package com.aeoncorex.aeonruntime

import android.content.Context
import com.aeoncorex.aeonruntime.bootstrap.BootstrapInstaller
import com.aeoncorex.aeonruntime.bridge.RuntimeBridge
import com.aeoncorex.aeonruntime.executor.CommandExecutor
import com.aeoncorex.aeonruntime.filesystem.VirtualFileSystem
import com.aeoncorex.aeonruntime.os.RootUtils
import com.aeoncorex.aeonruntime.packagemanager.PackageManager
import com.aeoncorex.aeonruntime.utils.RuntimeLogger
import kotlinx.coroutines.flow.Flow

/**
 * Main entry point for AeonRuntime.
 *
 * ```kotlin
 * // Initialize once (e.g. in Application.onCreate)
 * val runtime = AeonRuntime.initialize(context) {
 *     logLevel = LogLevel.DEBUG
 *     env("MY_APP", "demo")
 * }
 *
 * // Bootstrap install (required before any command)
 * runtime.install().collect { state -> ... }
 *
 * // Run a command in the runtime environment
 * val result = runtime.bridge.bash("echo hello")
 *
 * // Set up and use a Linux distro (requires the :os module)
 * // val os = runtime.createOs { registry { distro(Distro.Kali) { ... } } }
 * // os.setupDistro(Distro.Kali).collect { ... }
 * ```
 *
 * From Java, use [RuntimeConfig.builder]:
 * ```java
 * AeonRuntime runtime = AeonRuntime.initialize(context, RuntimeConfig.builder().build());
 * ```
 *
 * [initialize] is a process-wide singleton: once an instance exists, later calls
 * return it unchanged and their `config` argument is ignored. Use [reset] (tests)
 * to discard the instance.
 */
class AeonRuntime private constructor(
    val context: Context,
    val config:  RuntimeConfig,
    val vfs:     VirtualFileSystem,
) {
    val executor: CommandExecutor by lazy {
        CommandExecutor(config, vfs)
    }

    val bridge: RuntimeBridge by lazy {
        RuntimeBridge(
            executor   = executor,
            pkgManager = packageManager,
            vfs        = vfs,
        )
    }

    val packageManager: PackageManager by lazy {
        PackageManager(executor)
    }

    val installer: BootstrapInstaller by lazy {
        BootstrapInstaller(context, config, vfs)
    }

    /**
     * Install the bootstrap environment (a Termux-derived userland).
     * Must be collected before calling any [bridge] methods.
     *
     * When [RuntimeConfig.bootstrapProvider] is set, this does NOT download
     * anything — binaries already shipped inside the APK (via a
     * `bootstrap-<abi>` dependency) at install time. This just emits a
     * quick verification flow instead of the legacy network-download flow.
     */
    fun install(forceReinstall: Boolean = false): Flow<com.aeoncorex.aeonruntime.bootstrap.InstallState> {
        val provider = config.bootstrapProvider
        if (provider != null) {
            return kotlinx.coroutines.flow.flow {
                emit(com.aeoncorex.aeonruntime.bootstrap.InstallState.Checking)
                if (provider.isAvailable()) {
                    emit(com.aeoncorex.aeonruntime.bootstrap.InstallState.Completed)
                } else {
                    emit(
                        com.aeoncorex.aeonruntime.bootstrap.InstallState.Failed(
                            "No bootstrap binaries found under nativeLibraryDir. " +
                            "Add a bootstrap-<abi> dependency matching this device's ABI " +
                            "(e.g. implementation(\"com.aeoncorex.aeonruntime:bootstrap-arm64:...\"))."
                        )
                    )
                }
            }
        }
        return installer.install(forceReinstall)
    }

    /**
     * Returns true if the bootstrap environment is ready to use.
     * With a [RuntimeConfig.bootstrapProvider] configured, this is an
     * on-disk check (binaries bundled in the APK) rather than the legacy
     * filesDir marker-file check.
     */
    val isInstalled: Boolean
        get() = config.bootstrapProvider?.isAvailable() ?: vfs.isBootstrapInstalled

    /** Whether the device is rooted (su available and functional) */
    val isRooted: Boolean get() = RootUtils.isRooted

    /**
     * Throws if the bootstrap hasn't been installed yet.
     * Used by [com.aeoncorex.aeonruntime.utils.withBridge] to fail fast before
     * running commands against an uninitialized environment.
     */
    fun ensureReady() {
        check(isInstalled) {
            "AeonRuntime bootstrap is not installed. Call install() and collect it to completion first."
        }
    }

    /** Release resources. Call from onDestroy if needed. */
    fun release() {
        RuntimeLogger.d("AeonRuntime released")
    }

    companion object {
        @Volatile private var instance: AeonRuntime? = null

        /**
         * Initialize AeonRuntime using a configuration block. Thread-safe singleton.
         *
         * ```kotlin
         * val runtime = AeonRuntime.initialize(context) { logLevel = LogLevel.INFO }
         * ```
         *
         * @param context Any context; the application context is retained.
         * @param block   [RuntimeConfigDsl] block (see [runtimeConfig]).
         */
        @JvmStatic
        fun initialize(context: Context, block: RuntimeConfigDsl.() -> Unit): AeonRuntime =
            initialize(context, runtimeConfig(block))

        /**
         * Initialize AeonRuntime. Thread-safe singleton.
         *
         * @param context Application context
         * @param config  Use the [runtimeConfig] DSL or [RuntimeConfig.builder]
         */
        @JvmStatic
        @JvmOverloads
        fun initialize(
            context: Context,
            config: RuntimeConfig = RuntimeConfig.default(),
        ): AeonRuntime =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext, config)
                    .also { instance = it }
            }

        @JvmStatic
        fun getInstance(): AeonRuntime =
            instance ?: throw IllegalStateException(
                "AeonRuntime not initialized. Call AeonRuntime.initialize(context) first."
            )

        @JvmStatic
        fun reset() { instance = null }

        private fun create(context: Context, config: RuntimeConfig): AeonRuntime {
            RuntimeLogger.level = config.logLevel
            RuntimeLogger.i("AeonRuntime initializing — version 1.0.0")
            RuntimeLogger.i("Architecture: ${config.architecture.resolve().bootstrapArch}")
            RuntimeLogger.i("Root available: ${RootUtils.isRooted}")
            val vfs = VirtualFileSystem(context, config)
            return AeonRuntime(context, config, vfs)
        }
    }
}
