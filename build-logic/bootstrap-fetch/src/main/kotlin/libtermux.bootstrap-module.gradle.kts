/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Precompiled convention plugin shared by every bootstrap-<abi> module.
 * Each module's own build.gradle.kts applies this plugin plus
 * `com.libtermux.bootstrap-fetch`, then only sets its own ABI-specific
 * bootstrapFetch { } values.
 *
 * The zip is wired into the AAR as a generated asset by BootstrapFetchPlugin
 * through AGP's Variant API, so no manual task-dependency hooks are needed.
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
        // No source code, no native build — this module only ships the
        // Termux bootstrap zip as an asset (libtermux/bootstrap-<arch>.zip).
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
