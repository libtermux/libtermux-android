/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.libtermux.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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

/** Value of `bootstrapTag` that means "resolve the newest Termux bootstrap release at build time". */
internal const val LATEST = "latest"

/** Pinned release used when `latest` cannot be resolved (offline, GitHub API rate limit, ...). */
internal const val DEFAULT_FALLBACK_TAG = "bootstrap-2026.05.24-r1+apt.android-7"

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
 * ## Which release is used
 * `bootstrapTag` defaults to `"latest"`: the newest `bootstrap-*apt.android-7`
 * (non-glibc) release of termux/termux-packages is resolved via the GitHub API
 * on every build. If that lookup fails, [BootstrapFetchExtension.fallbackTag]
 * is used. To pin a release (reproducible builds) either set
 * `bootstrapFetch { bootstrapTag.set("bootstrap-2026.05.24-r1+apt.android-7") }`
 * or pass `-Plibtermux.bootstrapTag=<tag>`. The tag that was actually packaged
 * is logged and also stored in the asset `libtermux/bootstrap-version.txt`.
 * Set a `GITHUB_TOKEN` env var in CI to avoid API rate limits.
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

        // Defaults: newest release, overridable with -Plibtermux.bootstrapTag=<tag>.
        ext.bootstrapTag.convention(
            project.providers.gradleProperty("libtermux.bootstrapTag").orElse(LATEST)
        )
        ext.fallbackTag.convention(DEFAULT_FALLBACK_TAG)

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
                    fallbackTag.set(ext.fallbackTag)
                    gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)

                    // "latest" is a moving target: the declared inputs never change
                    // when a new release appears, so never treat the task as
                    // up-to-date. The download itself is cached, so a re-run is
                    // cheap (one API call + a file copy).
                    if (ext.bootstrapTag.get() == LATEST) {
                        outputs.upToDateWhen { false }
                    }
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
     * Bootstrap release tag, e.g. "bootstrap-2026.05.24-r1+apt.android-7", or
     * "latest" (default) to resolve the newest release at build time.
     * Can also be set from the command line with `-Plibtermux.bootstrapTag=<tag>`.
     */
    abstract val bootstrapTag: Property<String>

    /** Pinned tag used when "latest" cannot be resolved. Keep in sync with core's `TermuxConfig` fallback tag. */
    abstract val fallbackTag: Property<String>
}

@DisableCachingByDefault(because = "Downloads from the network")
abstract class FetchBootstrapZipTask : DefaultTask() {

    @get:Input abstract val termuxArch: Property<String>
    @get:Input abstract val bootstrapTag: Property<String>
    @get:Input abstract val fallbackTag: Property<String>

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
        val requested = bootstrapTag.get()

        val (tag, zip) = try {
            val t = if (requested == LATEST) resolveLatestTag() else requested
            t to ensureZip(arch, t)
        } catch (e: Exception) {
            if (requested != LATEST) throw e
            val fb = fallbackTag.get()
            logger.warn("Could not fetch the latest Termux bootstrap (${e.message}); falling back to pinned $fb")
            fb to ensureZip(arch, fb)
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dest = File(out, "libtermux/bootstrap-$arch.zip")
        dest.parentFile.mkdirs()
        zip.copyTo(dest, overwrite = true)
        File(out, "libtermux/bootstrap-version.txt").writeText(tag)

        logger.lifecycle(
            "bootstrap-fetch: packaged $tag (${dest.length() / 1024} KB) as asset libtermux/bootstrap-$arch.zip"
        )
    }

    /** Returns a validated cached zip for [tag], downloading it first if needed. */
    private fun ensureZip(arch: String, tag: String): File {
        val cacheFile = File(
            gradleUserHomeDir.get().asFile,
            "libtermux-bootstrap-cache/${tag.replace('+', '_')}/bootstrap-$arch.zip",
        )
        if (isValidBootstrap(cacheFile)) {
            logger.lifecycle("Using cached Termux bootstrap $tag: ${cacheFile.absolutePath}")
            return cacheFile
        }
        val url = "https://github.com/termux/termux-packages/releases/download/" +
            "${tag.replace("+", "%2B")}/bootstrap-$arch.zip"
        logger.lifecycle("Downloading Termux bootstrap $tag for $arch: $url")
        download(url, cacheFile)
        check(isValidBootstrap(cacheFile)) {
            "Downloaded file is not a valid Termux bootstrap zip (no SYMLINKS.txt): $url"
        }
        return cacheFile
    }

    /**
     * Newest `bootstrap-*apt.android-7` (non-glibc) release tag from the GitHub
     * API. Falls back to [fallbackTag] if the lookup fails.
     */
    private fun resolveLatestTag(): String {
        val fallback = fallbackTag.get()
        return try {
            val conn = URL(
                "https://api.github.com/repos/termux/termux-packages/releases?per_page=50"
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "libtermux-build/1.0")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let {
                conn.setRequestProperty("Authorization", "Bearer $it")
            }
            val body = try {
                check(conn.responseCode == 200) { "GitHub API HTTP ${conn.responseCode}" }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val latest = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(body)
                .map { it.groupValues[1] }
                .filter {
                    it.startsWith("bootstrap-") &&
                        it.contains("apt.android-7") &&
                        !it.contains("glibc")
                }
                .maxByOrNull { versionScore(it) }

            if (latest == null) {
                logger.warn("No matching bootstrap release found via GitHub API; using pinned $fallback")
                fallback
            } else {
                logger.lifecycle("Resolved latest Termux bootstrap tag: $latest")
                latest
            }
        } catch (e: Exception) {
            logger.warn("GitHub API lookup failed (${e.message}); using pinned $fallback")
            fallback
        }
    }

    /** Same ordering as core's BootstrapReleaseResolver: date, then -rN revision. */
    private fun versionScore(tag: String): Long {
        val m = Regex("""bootstrap-(\d{4})\.(\d{2})\.(\d{2})-r(\d+)""").find(tag) ?: return 0
        val (y, mo, d, r) = m.destructured
        return y.toLong() * 100_000_000L + mo.toLong() * 1_000_000L + d.toLong() * 10_000L + r.toLong()
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
        throw GradleException("Failed to download $url", lastError)
    }
}
