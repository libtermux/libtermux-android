/**
 * AeonRuntime
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/AeonRuntime
 */
package com.aeoncorex.aeonruntime.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.aeoncorex.aeonruntime.AeonRuntime
import com.aeoncorex.aeonruntime.RuntimeConfig
import com.aeoncorex.aeonruntime.bootstrap.InstallState
import com.aeoncorex.aeonruntime.bridge.RuntimeBridge
import com.aeoncorex.aeonruntime.utils.RuntimeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku-enhanced AeonRuntime.
 *
 * Wraps [AeonRuntime] and adds **elevated command execution** via Shizuku
 * (root or system-level privileges without requiring a full root setup).
 *
 * Falls back to normal execution when Shizuku is unavailable or permission
 * has not been granted.
 *
 * ## Usage
 * ```kotlin
 * val shizuku = ShizukuRuntimeBridge.getInstance(context)
 *
 * // Initialize (same as AeonRuntime)
 * shizuku.initialize().collect { state -> ... }
 *
 * // Run normal command
 * val normal = shizuku.bridge.run("ls /data/data")
 *
 * // Run elevated command (system/root privileges via Shizuku)
 * val elevated = shizuku.runElevated("ls /data/data/com.someapp")
 * if (elevated.elevated) println("Ran with elevated privileges")
 * println(elevated.stdout)
 * ```
 *
 * ## Setup
 * 1. Add the `shizuku` module dependency to your app.
 * 2. Add Shizuku's `<provider>` to your `AndroidManifest.xml`.
 * 3. Call [requestShizukuPermission] before [runElevated].
 *
 * @param context Application context
 * @param config  AeonRuntime configuration
 */
class ShizukuRuntimeBridge private constructor(
    context: Context,
    val config: RuntimeConfig,
) {
    private val runtime: AeonRuntime = AeonRuntime.initialize(context, config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Delegated access to the main [RuntimeBridge] */
    val bridge: RuntimeBridge get() = runtime.bridge

    // ── Shizuku state ─────────────────────────────────────────────────────

    /**
     * `true` when Shizuku is installed and its binder is reachable.
     * Call this before [requestShizukuPermission] or [runElevated].
     */
    val isShizukuAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() && Shizuku.getVersion() >= 1 }
            .getOrDefault(false)

    /**
     * `true` when [isShizukuAvailable] AND the app has been granted
     * `rikka.shizuku.PERMISSION`.
     */
    val isShizukuPermissionGranted: Boolean
        get() = isShizukuAvailable &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

    // ── Permission ────────────────────────────────────────────────────────

    /**
     * Suspending request for Shizuku permission.
     * Returns immediately with `true` if permission is already granted.
     * Shows the system permission dialog and suspends until the user responds.
     */
    suspend fun requestShizukuPermission(): Boolean {
        if (isShizukuPermissionGranted) return true
        if (!isShizukuAvailable)        return false

        return suspendCancellableCoroutine { cont ->
            val requestCode = System.currentTimeMillis().toInt() and 0xFFFF

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(reqCode: Int, grantResult: Int) {
                    if (reqCode == requestCode) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }

            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }

            runCatching { Shizuku.requestPermission(requestCode) }
                .onFailure {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    cont.resume(false)
                }
        }
    }

    // ── Elevated execution ────────────────────────────────────────────────

    /**
     * Execute a shell command with elevated privileges via Shizuku.
     *
     * Behaviour:
     * - **Shizuku available + granted** → runs under system/root UID.
     * - **Shizuku unavailable / denied** → falls back to normal execution
     *   (the [ElevatedResult.elevated] flag indicates which path was taken).
     *
     * @param command  Shell command to execute
     * @param env      Extra environment variables (merged with VFS env)
     * @param workDir  Working directory path (null → HOME)
     */
    suspend fun runElevated(
        command: String,
        env: Map<String, String> = emptyMap(),
        workDir: String? = null,
    ): ElevatedResult {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) {
            RuntimeLogger.w("Shizuku not available/permission missing — falling back to normal execution")
            val result = runtime.bridge.run(command, env = env)
            return ElevatedResult.fromExecutionResult(result, elevated = false)
        }

        return withContext(Dispatchers.IO) {
            try {
                val envArray: Array<String> = env.map { (k, v) -> "$k=$v" }.toTypedArray()

                val process = newShizukuProcess(
                    arrayOf("sh", "-c", command),
                    envArray.ifEmpty { null },
                    workDir,
                )
                try {
                    // Read both streams concurrently so a full pipe can't block the process.
                    val (out, err) = coroutineScope {
                        val o = async { process.inputStream.bufferedReader().readText() }
                        val e = async { process.errorStream.bufferedReader().readText() }
                        o.await() to e.await()
                    }
                    ElevatedResult(
                        stdout   = out,
                        stderr   = err,
                        exitCode = process.waitFor(),
                        elevated = true,
                    )
                } finally {
                    process.destroy()
                }
            } catch (e: Exception) {
                RuntimeLogger.e("Shizuku exec failed: ${e.message}", e)
                // Fallback to normal execution on Shizuku error
                val fallback = runtime.bridge.run(command, env = env)
                ElevatedResult.fromExecutionResult(fallback, elevated = false)
            }
        }
    }

    /**
     * Run multiple commands with elevated privileges.
     * Executes them in sequence; stops on first failure if [stopOnError] is true.
     */
    suspend fun runElevatedAll(
        commands: List<String>,
        env: Map<String, String> = emptyMap(),
        workDir: String? = null,
        stopOnError: Boolean = true,
    ): List<ElevatedResult> {
        val results = mutableListOf<ElevatedResult>()
        for (cmd in commands) {
            val result = runElevated(cmd, env, workDir)
            results.add(result)
            if (stopOnError && !result.isSuccess) break
        }
        return results
    }

    // ── Lifecycle delegation ──────────────────────────────────────────────

    /** Initialize AeonRuntime bootstrap (delegates to [AeonRuntime.install]) */
    fun initialize(forceReinstall: Boolean = false): Flow<InstallState> =
        runtime.install(forceReinstall)

    /** Destroy the instance and release resources */
    fun destroy() = runtime.release()

    /**
     * Starts a process in Shizuku's shell.
     *
     * `Shizuku.newProcess` is not part of the public API since Shizuku API 13 (the supported
     * route is a `UserService`), so it is invoked reflectively here. This is an unsupported
     * workaround: it may break with future Shizuku versions. Any failure is reported to the
     * caller as an exception, which [runElevated] turns into an unelevated fallback.
     */
    private fun newShizukuProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    companion object {
        @Volatile private var instance: ShizukuRuntimeBridge? = null

        @JvmStatic
        fun getInstance(
            context: Context,
            config: RuntimeConfig = RuntimeConfig.default(),
        ): ShizukuRuntimeBridge = instance ?: synchronized(this) {
            instance ?: ShizukuRuntimeBridge(context.applicationContext, config)
                .also { instance = it }
        }

        /** Reset singleton — for testing only */
        @JvmStatic
        internal fun resetInstance() {
            instance?.destroy()
            instance = null
        }
    }
}
