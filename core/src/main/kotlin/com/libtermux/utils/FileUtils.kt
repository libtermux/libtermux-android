/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.utils

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object FileUtils {

    /**
     * Extract a zip stream to a destination directory.
     * Guards against zip-slip attacks by canonicalizing paths.
     *
     * FIX 1: Changed to `suspend fun` so the `onProgress` callback can be a
     *   suspending lambda, allowing `emit()` inside a `flow {}` block.
     *
     * FIX 2: After extracting each file, Unix permissions stored in the zip
     *   entry's externalAttributes (upper 16 bits) are now applied via
     *   Os.chmod(). Without this, every extracted file lands as 0644
     *   (not executable), causing "error=13, Permission denied" when
     *   ProcessBuilder tries to exec bash or any other binary.
     */
    suspend fun extractZip(
        zipStream: InputStream,
        destDir: File,
        onProgress: (suspend (Float) -> Unit)? = null,
        totalBytes: Long = -1L,
    ) {
        var extracted = 0L
        val canonicalDest = destDir.canonicalFile

        ZipInputStream(zipStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name).canonicalFile

                // Zip-slip protection
                if (!outFile.path.startsWith(canonicalDest.path + File.separator) &&
                    outFile.path != canonicalDest.path
                ) {
                    TermuxLogger.w("Skipping malicious zip entry: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }

                    // Apply Unix permissions from zip entry externalAttributes.
                    // Upper 16 bits = Unix file mode (e.g. 0o100755 for executable).
                    // Lower 12 bits are the actual permission/sticky/setuid bits.
                    // Without this every file is left at default 0644 — not executable.
                    val unixMode = (entry.externalAttributes shr 16).toInt() and 0xFFF
                    if (unixMode != 0) {
                        applyChmod(outFile, unixMode)
                    } else {
                        // No Unix attrs in zip (e.g. zip created on Windows) —
                        // mark as executable so binaries can still run.
                        outFile.setExecutable(true, false)
                    }

                    val entrySize = if (entry.size > 0) entry.size
                                    else entry.compressedSize.coerceAtLeast(0)
                    extracted += entrySize
                    if (totalBytes > 0) onProgress?.invoke(extracted.toFloat() / totalBytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Apply Unix permission bits to [file].
     * Primary: Os.chmod() (available API 21+, handles symlinks correctly).
     * Fallback: /system/bin/chmod binary via Runtime.exec().
     */
    private fun applyChmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (e: ErrnoException) {
            TermuxLogger.w("Os.chmod failed for ${file.name} (${e.message}), trying chmod binary")
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("chmod", Integer.toOctalString(mode), file.absolutePath))
                    .waitFor()
            }
        }
    }

    /**
     * Process Termux SYMLINKS.txt and create symlinks.
     * Supports multiple delimiter styles used across different bootstrap versions.
     */
    fun processSymlinks(symlinksFile: File, baseDir: File) {
        if (!symlinksFile.exists()) {
            TermuxLogger.d("No SYMLINKS.txt found at ${symlinksFile.absolutePath}")
            return
        }

        var processed = 0
        var failed = 0

        symlinksFile.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine

            val parts = when {
                line.contains("←") -> {
                    val split = line.split("←").map { it.trim() }
                    if (split.size == 2) split else null
                }
                line.contains("→") -> {
                    val split = line.split("→").map { it.trim() }
                    if (split.size == 2) listOf(split[1], split[0]) else null
                }
                else -> {
                    val split = line.trim().split(Regex("[ \t]+"))
                    if (split.size >= 2) listOf(split[1], split[0]) else null
                }
            }

            if (parts == null || parts.size != 2) {
                TermuxLogger.w("Cannot parse symlink line: $line")
                failed++
                return@forEachLine
            }

            val target   = parts[0]
            val linkPath = File(baseDir, parts[1])

            linkPath.parentFile?.mkdirs()
            runCatching { linkPath.delete() }

            val exitCode = runCatching {
                Runtime.getRuntime().exec(
                    arrayOf("ln", "-sf", target, linkPath.absolutePath)
                ).waitFor()
            }.getOrDefault(-1)

            if (exitCode == 0) {
                processed++
            } else {
                TermuxLogger.w("Failed to create symlink: $linkPath -> $target (exit=$exitCode)")
                failed++
            }
        }

        TermuxLogger.i("Symlinks processed: $processed OK, $failed failed")
    }

    /**
     * Set executable permission recursively on all files under [dir].
     *
     * FIX: Previously used File.setExecutable() which silently fails on
     *   symlinks (Termux bootstrap has hundreds of symlinks to busybox).
     *   Now uses `chmod -R 755` via the system chmod binary as the primary
     *   strategy, with File.setExecutable() as fallback only.
     */
    fun makeExecutable(dir: File) {
        // chmod -R 755 handles symlinks and is reliable on all Android versions
        val exitCode = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("chmod", "-R", "755", dir.absolutePath))
                .waitFor()
        }.getOrDefault(-1)

        if (exitCode != 0) {
            // Fallback: Java API (won't fix symlinks but better than nothing)
            TermuxLogger.w("chmod -R 755 failed (exit=$exitCode), falling back to setExecutable")
            dir.walkTopDown().forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
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

    /** Delete directory recursively, ignoring errors */
    fun File.deleteRecursivelyQuiet(): Boolean =
        runCatching { deleteRecursively() }.getOrDefault(false)
}
