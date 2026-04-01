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
package com.libtermux.bootstrap

import android.content.Context
import com.libtermux.TermuxConfig
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.utils.ArchUtils.resolved
import com.libtermux.utils.FileUtils.copyToWithProgress
import com.libtermux.utils.FileUtils.deleteRecursivelyQuiet
import com.libtermux.utils.FileUtils.extractZip
import com.libtermux.utils.FileUtils.makeExecutable
import com.libtermux.utils.FileUtils.processSymlinks
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class InstallState {
    object Checking       : InstallState()
    object AlreadyInstalled : InstallState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : InstallState()
    data class Extracting(val progress: Float) : InstallState()
    object SettingPermissions : InstallState()
    object ProcessingSymlinks : InstallState()
    object Verifying      : InstallState()
    object Completed      : InstallState()
    data class Failed(val error: String, val cause: Throwable? = null) : InstallState()
}

class BootstrapInstaller(
    private val context: Context,
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Install the Termux bootstrap. Emits [InstallState] events.
     * Safe to call multiple times — skips if already installed.
     */
    fun install(forceReinstall: Boolean = false): Flow<InstallState> = flow {
        emit(InstallState.Checking)
        
        if (!forceReinstall && vfs.isBootstrapInstalled) {
            TermuxLogger.i("Bootstrap already installed, skipping.")
            emit(InstallState.AlreadyInstalled)
            return@flow
        }

        try {
            val arch = config.architecture.resolved()
            val url  = config.customBootstrapUrl
                ?: TermuxConfig.bootstrapUrl(arch, config.bootstrapVersion)

            TermuxLogger.i("Downloading bootstrap: $url")

            // ----- Download -----
            val zipFile = File(context.cacheDir, "bootstrap-${arch.termuxName}.zip")
            var totalBytes = -1L

            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(InstallState.Failed("HTTP ${response.code}: ${response.message}"))
                    return@flow
                }
                totalBytes = response.body?.contentLength() ?: -1L

                zipFile.outputStream().use { out ->
                    response.body?.byteStream()?.copyToWithProgress(
                        out   = out,
                        total = totalBytes,
                        onProgress = { progress ->
                            // Channel capacity — emit best-effort
                            runCatching {
                                // Use trySend in channel-based; here we just track
                            }
                        }
                    )
                }
            }

            emit(InstallState.Downloading(1f, zipFile.length(), zipFile.length()))

            // ----- Extract -----
            TermuxLogger.i("Extracting bootstrap to ${vfs.prefixDir}")
            if (forceReinstall) vfs.prefixDir.deleteRecursivelyQuiet()
            vfs.prefixDir.mkdirs()

            var extractProgress = 0f
            zipFile.inputStream().use { stream ->
                extractZip(
                    zipStream   = stream,
                    destDir     = vfs.prefixDir,
                    onProgress  = { p ->
                        extractProgress = p
                        // Progress tracked externally
                    },
                    totalBytes  = zipFile.length(),
                )
            }
            emit(InstallState.Extracting(1f))

            // ----- Symlinks -----
            emit(InstallState.ProcessingSymlinks)
            val symlinksFile = File(vfs.prefixDir, "SYMLINKS.txt")
            processSymlinks(symlinksFile, vfs.prefixDir)
            symlinksFile.delete()

            // ----- Permissions -----
            emit(InstallState.SettingPermissions)
            makeExecutable(vfs.binDir)

            // ----- Verify -----
            emit(InstallState.Verifying)
            val shell = File(vfs.binDir, "bash")
            if (!shell.exists()) {
                // Try sh as fallback
                val sh = File(vfs.binDir, "sh")
                if (!sh.exists()) {
                    emit(InstallState.Failed("Bootstrap verification failed: shell not found"))
                    return@flow
                }
            }

            // ----- Mark installed -----
            vfs.markBootstrapInstalled()
            zipFile.delete()

            TermuxLogger.i("Bootstrap installation complete!")
            emit(InstallState.Completed)

        } catch (e: Exception) {
            TermuxLogger.e("Bootstrap installation failed", e)
            emit(InstallState.Failed(e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    /** Uninstall the bootstrap */
    fun uninstall() {
        vfs.resetAll()
        TermuxLogger.i("Bootstrap uninstalled.")
    }
}
