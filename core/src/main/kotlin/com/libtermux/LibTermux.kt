/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * * http://www.apache.org/licenses/LICENSE-2.0
 * * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux

import android.content.Context
import com.libtermux.bootstrap.BootstrapInstaller
import com.libtermux.bootstrap.InstallState
import com.libtermux.bridge.TermuxBridge
import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.SessionManager
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.pkg.PackageManager
import com.libtermux.service.TermuxBackgroundService
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach

/**
 * # LibTermux
 *
 * Main entry-point for the LibTermux SDK.
 *
 * ## Quick Start (Kotlin DSL)
 * ```kotlin
 * val termux = LibTermux.getInstance(context)
 * termux.initialize().collect { state ->
 *     when (state) {
 *         is InstallState.Completed -> {
 *             val result = termux.bridge.run("echo Hello from Linux!")
 *             println(result.stdout) // Hello from Linux!
 *         }
 *         else -> { /* handle progress */ }
 *     }
 * }
 * ```
 *
 * ## Quick Start (Java)
 * ```java
 * LibTermux termux = LibTermux.getInstance(context);
 * termux.initializeBlocking(); // blocks current thread
 * String output = termux.getBridge().runOrThrow("uname -a");
 * ```
 */
class LibTermux private constructor(
    private val context: Context,
    val config: TermuxConfig,
) {
    // ── State ─────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<LibTermuxState>(LibTermuxState.Uninitialized)
    val state: StateFlow<LibTermuxState> = _state.asStateFlow()

    val isInitialized: Boolean get() = _state.value is LibTermuxState.Ready

    // ── Core components (lazy — created after init) ────────────────────

    val vfs: VirtualFileSystem by lazy { VirtualFileSystem(context, config) }

    val executor: CommandExecutor by lazy { CommandExecutor(config, vfs) }

    val bridge: TermuxBridge by lazy {
        TermuxBridge(executor, PackageManager(executor), vfs)
    }

    val sessions: SessionManager by lazy {
        SessionManager(executor, scope)
    }

    val packages: PackageManager by lazy { PackageManager(executor) }

    private val installer: BootstrapInstaller by lazy {
        BootstrapInstaller(context, config, vfs)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Initialization ────────────────────────────────────────────────────

    /**
     * Initialize LibTermux.
     * Downloads and installs the bootstrap on first run (auto-skips if installed).
     * Returns a [Flow] of [InstallState] events.
     */
    fun initialize(forceReinstall: Boolean = false): Flow<InstallState> {
        TermuxLogger.level = config.logLevel
        _state.value = LibTermuxState.Initializing

        return installer.install(forceReinstall)
            .onEach { installState ->
                when (installState) {
                    is InstallState.Completed,
                    is InstallState.AlreadyInstalled -> {
                        _state.value = LibTermuxState.Ready
                        TermuxLogger.i("LibTermux is ready.")
                        if (config.backgroundExecutionEnabled) {
                            TermuxBackgroundService.start(context, config)
                        }
                    }
                    is InstallState.Failed -> {
                        _state.value = LibTermuxState.Error(installState.error)
                    }
                    else -> { /* intermediate states */ }
                }
            }
            .catch { e ->
                _state.value = LibTermuxState.Error(e.message ?: "Initialization failed")
                TermuxLogger.e("LibTermux initialization failed", e)
            }
    }

    /**
     * Blocking initialization for Java interop.
     * MUST NOT be called on the main thread.
     */
    @Throws(Exception::class)
    fun initializeBlocking(): Boolean = runBlocking {
        var success = false
        initialize().collect { state ->
            when (state) {
                is InstallState.Completed,
                is InstallState.AlreadyInstalled -> success = true
                is InstallState.Failed           -> throw Exception(state.error)
                else -> {}
            }
        }
        success
    }

    /**
     * Ensure LibTermux is initialized before running code.
     * Suspends until ready.
     */
    suspend fun ensureReady(): LibTermux {
        if (!isInitialized) {
            initialize().collect { state ->
                if (state is InstallState.Completed || state is InstallState.AlreadyInstalled) return@collect
                if (state is InstallState.Failed) throw Exception(state.error)
            }
        }
        return this
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Stop background service and cancel coroutines */
    fun destroy() {
        sessions.closeAll()
        scope.cancel()
        TermuxBackgroundService.stop(context)
        TermuxLogger.i("LibTermux destroyed.")
    }

    /** Uninstall the bootstrap completely */
    fun uninstall() {
        installer.uninstall()
        _state.value = LibTermuxState.Uninitialized
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    companion object {
        @Volatile private var instance: LibTermux? = null

        /**
         * Get or create the singleton LibTermux instance.
         */
        @JvmStatic
        fun getInstance(
            context: Context,
            config: TermuxConfig = TermuxConfig.default(),
        ): LibTermux = instance ?: synchronized(this) {
            instance ?: LibTermux(context.applicationContext, config)
                .also { instance = it }
        }

        /**
         * Kotlin DSL convenience factory.
         */
        @JvmStatic
        fun create(context: Context, block: TermuxConfigDsl.() -> Unit): LibTermux =
            getInstance(context, termuxConfig(block))

        /** Reset singleton (for testing) */
        @JvmStatic
        internal fun resetInstance() {
            instance?.destroy()
            instance = null
        }
    }
}

sealed class LibTermuxState {
    object Uninitialized                 : LibTermuxState()
    object Initializing                  : LibTermuxState()
    object Ready                         : LibTermuxState()
    data class Error(val message: String): LibTermuxState()
}
