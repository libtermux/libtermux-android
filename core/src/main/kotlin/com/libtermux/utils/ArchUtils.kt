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

import android.os.Build
import com.libtermux.Architecture

internal object ArchUtils {

    /** Detect device architecture */
    fun detect(): Architecture = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a"   -> Architecture.ARM64
        "x86_64"      -> Architecture.X86_64
        "armeabi-v7a" -> Architecture.ARM
        "x86"         -> Architecture.X86
        else          -> Architecture.ARM64
    }

    /** Resolve AUTO to actual architecture */
    fun Architecture.resolved(): Architecture =
        if (this == Architecture.AUTO) detect() else this

    /** Termux bootstrap zip filename for architecture */
    fun Architecture.bootstrapZipName(): String =
        "bootstrap-${resolved().termuxName}.zip"

    /** Is current device 64-bit? */
    val is64Bit: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
}
