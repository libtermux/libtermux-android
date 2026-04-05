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
import java.io.FileOutputStream

// Assuming this is your state class structure based on the flow
sealed class InstallState {
    object Downloading : InstallState()
    data class Extracting(val progress: Float) : InstallState()
    object ProcessingSymlinks : InstallState()
    object SettingPermissions : InstallState()
    object Verifying : InstallState()
    object Completed : InstallState()
    data class Failed(val reason: String, val exception: Throwable? = null) : InstallState()
}

class BootstrapInstaller(
    private val context: Context,
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {
    private val httpClient = OkHttpClient()

    /** Install the Termux bootstrap environment */
    fun install(): Flow<InstallState> = flow {
        try {
            // ----- Prepare Zip File -----
            // Ensures the file object itself is strictly non-null from the start
            val zipFileName = "bootstrap-$resolved.zip"
            val zipFile = File(vfs.cacheDir, zipFileName)

            // ----- Download (If not already present or valid) -----
            if (!zipFile.exists() || zipFile.length() == 0L) {
                emit(InstallState.Downloading)
                
                // Assuming you have a bootstrapUrl in your config
                val request = Request.Builder().url(config.bootstrapUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Download failed with HTTP code: ${response.code}")
                    }
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(zipFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Response body is null")
                }
            }

            // Safety check before extraction to prevent NPE down the line
            if (!zipFile.exists()) {
                throw Exception("Bootstrap zip file is missing after download attempt.")
            }

            // ----- Extract -----
            emit(InstallState.Extracting(0f))
            extractZip(zipFile, vfs.prefixDir) { progress ->
                // Emit progress if your extractZip implementation supports it
                // emit(InstallState.Extracting(progress))
            }
            emit(InstallState.Extracting(100f))

            // ----- Symlinks -----
            emit(InstallState.ProcessingSymlinks)
            
            // CRITICAL FIX: Passing the verified non-null zipFile instead of a text file,
            // as the method signature (per the crash log) expects the 'zipFile'.
            processSymlinks(zipFile, vfs.prefixDir)
            
            // Clean up the SYMLINKS.txt if it was extracted into prefixDir
            val symlinksTxt = File(vfs.prefixDir, "SYMLINKS.txt")
            if (symlinksTxt.exists()) {
                symlinksTxt.delete()
            }

            // ----- Permissions -----
            emit(InstallState.SettingPermissions)
            makeExecutable(vfs.binDir)

            // ----- Verify -----
            emit(InstallState.Verifying)
            val bash = File(vfs.binDir, "bash")
            val sh = File(vfs.binDir, "sh")
            
            if (!bash.exists() && !sh.exists()) {
                emit(InstallState.Failed("Bootstrap verification failed: shell executable not found"))
                return@flow
            }

            // ----- Mark installed & Cleanup -----
            vfs.markBootstrapInstalled()
            if (zipFile.exists()) {
                zipFile.delete()
            }

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
