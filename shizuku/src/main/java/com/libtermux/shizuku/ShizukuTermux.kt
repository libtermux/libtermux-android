package com.libtermux.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.libtermux.LibTermux
import com.libtermux.TermuxConfig
import com.libtermux.bridge.TermuxBridge
import com.libtermux.executor.ExecutionResult
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

/**
 * Shizuku-enhanced version of LibTermux.
 * Provides `runElevated()` to execute commands with root/system privileges.
 * Falls back to normal execution if Shizuku is not available or permission not granted.
 */
class ShizukuTermux private constructor(
    context: Context,
    val config: TermuxConfig,
) {
    private val libTermux: LibTermux = LibTermux.getInstance(context, config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Delegated components
    val bridge: TermuxBridge get() = libTermux.bridge

    /**
     * Checks if Shizuku is installed and running.
     */
    val isShizukuAvailable: Boolean
        get() = Shizuku.pingBinder() && Shizuku.getVersion() >= 1

    /**
     * Checks if the app has Shizuku permission.
     */
    val isShizukuPermissionGranted: Boolean
        get() = isShizukuAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /**
     * Requests Shizuku permission from the user.
     */
    suspend fun requestShizukuPermission(): Boolean = suspendCancellableCoroutine { cont ->
        if (isShizukuPermissionGranted) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        val requestCode = System.currentTimeMillis().toInt()
        val callback = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == requestCode) {
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    Shizuku.removeOnRequestPermissionResultListener(this)
                }
            }
        }
        Shizuku.addOnRequestPermissionResultListener(callback)
        Shizuku.requestPermission(requestCode)
        cont.invokeOnCancellation {
            Shizuku.removeOnRequestPermissionResultListener(callback)
        }
    }

    /**
     * Run a shell command with elevated privileges (if Shizuku available and permission granted).
     */
    suspend fun runElevated(
        command: String,
        env: Map<String, String> = emptyMap(),
        workDir: String? = null,
    ): ElevatedResult {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) {
            TermuxLogger.w("Shizuku not available/permission missing; falling back to normal execution")
            val result = libTermux.bridge.run(command)
            return ElevatedResult.fromExecutionResult(result, elevated = false)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Convert environment map to array of "key=value"
                val envArray = env.flatMap { listOf(it.key, it.value) }.toTypedArray()
                val output = Shizuku.exec(
                    arrayOf("sh", "-c", command),
                    envArray,
                    workDir
                )
                ElevatedResult(
                    stdout = output.stdout,
                    stderr = output.stderr,
                    exitCode = output.exitCode,
                    elevated = true,
                )
            } catch (e: Exception) {
                TermuxLogger.e("Shizuku exec failed", e)
                ElevatedResult(
                    stdout = "",
                    stderr = e.message ?: "Shizuku exec failed",
                    exitCode = -1,
                    elevated = true,
                )
            }
        }
    }

    // Delegate lifecycle methods
    suspend fun initialize() = libTermux.initialize()
    fun destroy() = libTermux.destroy()

    companion object {
        @Volatile
        private var instance: ShizukuTermux? = null

        @JvmStatic
        fun getInstance(
            context: Context,
            config: TermuxConfig = TermuxConfig.default(),
        ): ShizukuTermux = instance ?: synchronized(this) {
            instance ?: ShizukuTermux(context.applicationContext, config).also { instance = it }
        }
    }
}