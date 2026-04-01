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
package com.libtermux.utils

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object FileUtils {

    /** Extract zip stream to destination directory.
     *  Supports Termux SYMLINKS file format. */
    fun extractZip(
        zipStream: InputStream,
        destDir: File,
        onProgress: ((Float) -> Unit)? = null,
        totalBytes: Long = -1L,
    ) {
        var extracted = 0L
        ZipInputStream(zipStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    extracted += entry.size
                    if (totalBytes > 0) onProgress?.invoke(extracted.toFloat() / totalBytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Process Termux SYMLINKS file and create symlinks */
    fun processSymlinks(symlinksFile: File, baseDir: File) {
        if (!symlinksFile.exists()) return
        symlinksFile.forEachLine { line ->
            val parts = line.split("←")
            if (parts.size == 2) {
                val target = parts[0].trim()
                val linkPath = File(baseDir, parts[1].trim())
                linkPath.parentFile?.mkdirs()
                runCatching { linkPath.delete() }
                runCatching {
                    Runtime.getRuntime().exec(
                        arrayOf("ln", "-sf", target, linkPath.absolutePath)
                    ).waitFor()
                }
            }
        }
    }

    /** Set executable permission recursively on directory */
    fun makeExecutable(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile) file.setExecutable(true, false)
        }
    }

    /** Compute SHA-256 checksum of a file */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copy stream with progress callback */
    fun InputStream.copyToWithProgress(
        out: OutputStream,
        total: Long,
        onProgress: (Float) -> Unit,
        bufferSize: Int = 8 * 1024,
    ): Long {
        var bytesCopied = 0L
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            if (total > 0) onProgress(bytesCopied.toFloat() / total)
            bytes = read(buffer)
        }
        return bytesCopied
    }

    /** Delete directory recursively */
    fun File.deleteRecursivelyQuiet(): Boolean =
        runCatching { deleteRecursively() }.getOrDefault(false)
}
