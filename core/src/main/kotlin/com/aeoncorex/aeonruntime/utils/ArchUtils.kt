/**
 * AeonRuntime
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
 * Project: https://github.com/AeonCoreX-Lab/AeonRuntime
 */
package com.aeoncorex.aeonruntime.utils

import android.os.Build
import com.aeoncorex.aeonruntime.Architecture

internal object ArchUtils {

    /**
     * Detect device architecture safely.
     *
     * Uses null-safe access on [Build.SUPPORTED_ABIS] so this works correctly
     * in both production and unit-test environments.
     * Defaults to [Architecture.ARM64] when detection fails.
     */
    fun detect(): Architecture {
        val primaryAbi = runCatching { Build.SUPPORTED_ABIS }
            .getOrNull()
            ?.firstOrNull()
            .orEmpty()

        return when (primaryAbi) {
            "arm64-v8a"   -> Architecture.ARM64
            "x86_64"      -> Architecture.X86_64
            "armeabi-v7a" -> Architecture.ARM
            "x86"         -> Architecture.X86
            else          -> Architecture.ARM64
        }
    }

    /** Resolve [Architecture.AUTO] to the actual device architecture */
    fun Architecture.resolved(): Architecture =
        if (this == Architecture.AUTO) detect() else this

    /** Bootstrap zip filename (upstream Termux naming) for this architecture */
    fun Architecture.bootstrapZipName(): String =
        "bootstrap-${resolved().bootstrapArch}.zip"

    /** Returns true when the device supports 64-bit ABIs */
    val is64Bit: Boolean
        get() = runCatching { Build.SUPPORTED_64_BIT_ABIS }
            .getOrNull()
            ?.isNotEmpty() == true
}
