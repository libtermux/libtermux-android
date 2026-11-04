/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.libtermux.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Termux-style bootstrap packaging.
 *
 * Termux itself embeds the complete bootstrap zip in the APK and extracts it
 * into `$PREFIX` on first launch (see termux-app `TermuxInstaller`). This
 * plugin does the same for a `bootstrap-<abi>` library module: at build time it
 * downloads the official Termux bootstrap zip (cached in the Gradle user home)
 * and ships it as an Android *asset* at `libtermux/bootstrap-<arch>.zip`.
 * `:core`'s `BootstrapInstaller` finds it there at runtime and extracts it —
 * no network needed on the device.
 *
 * ## Why this fixes the Gradle 9 "implicit dependency" failure
 * The old design wrote files into `src/main/jniLibs` (a *source* directory that
 * AGP merely reads), so Gradle could not see the producer->consumer edge. Here
 * the zip goes to a task *output* directory that is registered with AGP through
 * the official Variant API (`sources.assets.addGeneratedSourceDirectory`), so
 * AGP wires the task dependency itself. No `dependsOn`/name-matching hacks, and
 * no use of `project` at execution time (configuration-cache safe).
 *
 * ## Note on execution
 * Extracted files live in app-private storage. Android 10+ blocks `exec()` of
 * those for apps with targetSdk >= 29, which is why Termux (and the sample app)
 * use targetSdk 28.
 */
class BootstrapFetchPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create<BootstrapFetchExtension>("bootstrapFetch")

        // withId makes this independent of plugin application order.
        project.plugins.withId("com.android.library") {
            val components = project.extensions
                .getByType(LibraryAndroidComponentsExtension::class.java)

            components.onVariants { variant ->
                val cap = variant.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }

                // One task per variant: each addGeneratedSourceDirectory call
                // gets its own AGP-assigned output directory.
                val fetch = project.tasks.register<FetchBootstrapZipTask>("fetchBootstrapZip$cap") {
                    group = "libtermux"
                    description = "Downloads the Termux bootstrap zip and packages it as an " +
                        "asset (libtermux/bootstrap-<arch>.zip) for the $cap variant."
                    termuxArch.set(ext.termuxArch)
                    bootstrapTag.set(ext.bootstrapTag)
                    gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
                }

                variant.sources.assets?.addGeneratedSourceDirectory(
                    fetch,
                    FetchBootstrapZipTask::outputDir,
                )
            }
        }
    }
}

abstract class BootstrapFetchExtension {
    /** Termux's arch name used in the zip filename: aarch64, arm, i686 or x86_64. */
    abstract val termuxArch: Property<String>

    /**
     * Bootstrap release tag, e.g. "bootstrap-2026.05.24-r1+apt.android-7".
     * Keep in sync with core's `TermuxConfig.LATEST_FALLBACK_TAG`.
     */
    abstract val bootstrapTag: Property<String>
}

@DisableCachingByDefault(because = "Downloads from the network")
abstract class FetchBootstrapZipTask : DefaultTask() {

    @get:Input abstract val termuxArch: Property<String>
    @get:Input abstract val bootstrapTag: Property<String>

    /**
     * Gradle user home, captured at configuration time. Reading `project`
     * inside [fetch] is unsupported with the configuration cache.
     */
    @get:Internal abstract val gradleUserHomeDir: DirectoryProperty

    /** Wired by AGP via addGeneratedSourceDirectory — do not set manually. */
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val arch = termuxArch.get()
        val tag = bootstrapTag.get()

        val cacheFile = File(
            gradleUserHomeDir.get().asFile,
            "libtermux-bootstrap-cache/${tag.replace('+', '_')}/bootstrap-$arch.zip",
        )

        if (!isValidBootstrap(cacheFile)) {
            val url = "https://github.com/termux/termux-packages/releases/download/" +
                "${tag.replace("+", "%2B")}/bootstrap-$arch.zip"
            logger.lifecycle("Downloading Termux bootstrap for $arch: $url")
            download(url, cacheFile)
            check(isValidBootstrap(cacheFile)) {
                "Downloaded file is not a valid Termux bootstrap zip (no SYMLINKS.txt): $url"
            }
        } else {
            logger.lifecycle("Using cached Termux bootstrap: ${cacheFile.absolutePath}")
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dest = File(out, "libtermux/bootstrap-$arch.zip")
        dest.parentFile.mkdirs()
        cacheFile.copyTo(dest, overwrite = true)
        logger.lifecycle("bootstrap-fetch: packaged ${dest.length() / 1024} KB as asset libtermux/bootstrap-$arch.zip")
    }

    private fun isValidBootstrap(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            ZipFile(file).use { it.getEntry("SYMLINKS.txt") != null }
        } catch (_: Exception) {
            false
        }
    }

    private fun download(url: String, dest: File) {
        dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "libtermux-build/1.0")
                try {
                    check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                if (dest.exists()) dest.delete()
                check(tmp.renameTo(dest)) { "Could not move $tmp to $dest" }
                return
            } catch (e: Exception) {
                lastError = e
                logger.warn("Download attempt ${attempt + 1}/3 failed: ${e.message}")
                tmp.delete()
            }
        }
        throw org.gradle.api.GradleException("Failed to download $url", lastError)
    }
}
