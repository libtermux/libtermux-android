/**
 * LibTermux-Android — Root Project Settings
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "libtermux-android"

// Core SDK — bootstrap, executor, bridge, file system
include(":core")

// OS Module — Linux distro management, proot/chroot, VNC GUI
// Depends on :core. Add separately if your app needs distro support.
include(":os")

// Terminal UI widget
include(":terminal-view")

// Optional: Shizuku elevated execution module
include(":shizuku")

// Demo application
include(":sample")
