/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Precompiled convention plugin shared by every bootstrap-<abi> module.
 * Each module's own build.gradle.kts applies this plugin plus
 * `com.libtermux.bootstrap-fetch`, then only sets its own ABI-specific
 * bootstrapFetch { } values — everything else (AGP config, publishing,
 * wireBinariesToBuild) lives here exactly once.
 */
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.android.library")
}

configure<LibraryExtension> {
    namespace = "com.libtermux.bootstrap"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // No source code, no native build of our own — this module's sole
        // job is shipping prebuilt binaries under src/main/jniLibs/<abi>/.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Make sure binaries are fetched before anything reads src/main/jniLibs/.
//
// This is deliberately over-broad rather than targeting one "the" task name,
// because AGP's exact task graph for a *library* module's jniLibs pickup
// has shifted across AGP versions and isn't something we can execute-verify
// in this environment. Three independent hooks, so a naming mismatch in any
// one of them doesn't silently produce an empty jniLibs directory:
//
//   1. preBuild — always exists, always runs first, on every AGP version.
//   2. Any task whose name contains "JniLib" (case-insensitive) — covers
//      mergeDebugJniLibFolders, mergeReleaseJniLibFolders, and whatever
//      AGP 9.x specifically calls it, without hardcoding one exact string.
//   3. Any AAR-bundling task (bundle*Aar) — the actual artifact-producing
//      task read by consumers; if jniLibs picked up nothing by the time
//      this runs, the AAR would ship empty regardless of #1/#2.
//
// If `fetchBootstrapBinaries` still doesn't appear to run, verify manually:
//   ./gradlew :bootstrap-arm64:fetchBootstrapBinaries --stacktrace
// and check src/main/jniLibs/arm64-v8a/ was populated directly, independent
// of whatever task ends up consuming it.
// BOTH fetch tasks write into src/main/jniLibs/<abi>/ (fetchProotBinary is only
// chained via finalizedBy, which orders but does NOT declare a dependency), so
// every consumer hook below must depend on both -- otherwise Gradle 9 fails with
// "Property has implicit dependency ... fetchProotBinary".
val fetchTaskNames = listOf("fetchBootstrapBinaries", "fetchProotBinary")

tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn(fetchTaskNames) }

tasks.matching { it.name.contains("JniLib", ignoreCase = true) }
    .configureEach { dependsOn(fetchTaskNames) }

tasks.matching { it.name.startsWith("bundle") && it.name.endsWith("Aar") }
    .configureEach { dependsOn(fetchTaskNames) }

tasks.matching { it.name == "assemble" || it.name.startsWith("assemble") }
    .configureEach { dependsOn(fetchTaskNames) }
