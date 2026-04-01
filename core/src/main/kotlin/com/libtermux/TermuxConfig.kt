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
package com.libtermux

/**
 * Configuration DSL for LibTermux.
 *
 * Usage (Kotlin DSL):
 * ```kotlin
 * val config = termuxConfig {
 *     architecture = Architecture.AUTO
 *     autoInstall  = true
 *     logLevel     = LogLevel.DEBUG
 *     env("MY_VAR", "value")
 * }
 * ```
 *
 * Usage (Java Builder):
 * ```java
 * TermuxConfig config = TermuxConfig.builder()
 *     .architecture(Architecture.ARM64)
 *     .autoInstall(true)
 *     .build();
 * ```
 */
data class TermuxConfig(
    val architecture: Architecture          = Architecture.AUTO,
    val autoInstall: Boolean                = true,
    val bootstrapVersion: String            = LATEST_BOOTSTRAP,
    val logLevel: LogLevel                  = LogLevel.INFO,
    val maxCommandTimeoutMs: Long           = 30_000L,
    val environmentVariables: Map<String, String> = emptyMap(),
    val customBootstrapUrl: String?         = null,
    val enablePackageManager: Boolean       = true,
    val backgroundExecutionEnabled: Boolean = true,
    val notificationChannelId: String       = "libtermux_bg",
    val notificationChannelName: String     = "LibTermux Service",
    val notificationTitle: String           = "LibTermux Running",
    val enableLogging: Boolean              = true,
) {
    companion object {
        const val LATEST_BOOTSTRAP = "LATEST"
        private const val BASE_RELEASE_URL =
            "https://github.com/termux/termux-packages/releases/download"

        @JvmStatic fun default(): TermuxConfig = TermuxConfig()

        @JvmStatic fun builder(): Builder = Builder()

        internal fun bootstrapUrl(arch: Architecture, version: String): String {
            val tag = if (version == LATEST_BOOTSTRAP) "bootstrap-2024.01.31"
                      else "bootstrap-$version"
            return "$BASE_RELEASE_URL/$tag/bootstrap-${arch.termuxName}.zip"
        }
    }

    class Builder {
        private var cfg = TermuxConfig()
        fun architecture(v: Architecture)  = apply { cfg = cfg.copy(architecture = v)      }
        fun autoInstall(v: Boolean)        = apply { cfg = cfg.copy(autoInstall = v)        }
        fun bootstrapVersion(v: String)    = apply { cfg = cfg.copy(bootstrapVersion = v)   }
        fun logLevel(v: LogLevel)          = apply { cfg = cfg.copy(logLevel = v)           }
        fun maxCommandTimeoutMs(v: Long)   = apply { cfg = cfg.copy(maxCommandTimeoutMs = v)}
        fun customBootstrapUrl(v: String)  = apply { cfg = cfg.copy(customBootstrapUrl = v) }
        fun addEnv(key: String, value: String) = apply {
            cfg = cfg.copy(environmentVariables = cfg.environmentVariables + (key to value))
        }
        fun build(): TermuxConfig = cfg
    }
}

/** Kotlin DSL entry point */
fun termuxConfig(block: TermuxConfigDsl.() -> Unit): TermuxConfig =
    TermuxConfigDsl().apply(block).build()

@DslMarker annotation class TermuxDsl

@TermuxDsl
class TermuxConfigDsl {
    var architecture: Architecture          = Architecture.AUTO
    var autoInstall: Boolean                = true
    var bootstrapVersion: String            = TermuxConfig.LATEST_BOOTSTRAP
    var logLevel: LogLevel                  = LogLevel.INFO
    var maxCommandTimeoutMs: Long           = 30_000L
    var customBootstrapUrl: String?         = null
    var enablePackageManager: Boolean       = true
    var backgroundExecutionEnabled: Boolean = true

    private val envVars = mutableMapOf<String, String>()
    fun env(key: String, value: String) { envVars[key] = value }

    internal fun build() = TermuxConfig(
        architecture              = architecture,
        autoInstall               = autoInstall,
        bootstrapVersion          = bootstrapVersion,
        logLevel                  = logLevel,
        maxCommandTimeoutMs       = maxCommandTimeoutMs,
        environmentVariables      = envVars.toMap(),
        customBootstrapUrl        = customBootstrapUrl,
        enablePackageManager      = enablePackageManager,
        backgroundExecutionEnabled= backgroundExecutionEnabled,
    )
}

enum class Architecture(val termuxName: String) {
    ARM64("aarch64"),
    X86_64("x86_64"),
    ARM("arm"),
    X86("i686"),
    AUTO("") {
        override fun resolve(): Architecture = when (
            android.os.Build.SUPPORTED_ABIS.firstOrNull()
        ) {
            "arm64-v8a"    -> ARM64
            "x86_64"       -> X86_64
            "armeabi-v7a"  -> ARM
            "x86"          -> X86
            else           -> ARM64
        }
    };
    open fun resolve(): Architecture = this
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }
