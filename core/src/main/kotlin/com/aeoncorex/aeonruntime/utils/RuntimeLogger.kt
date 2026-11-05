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

import android.util.Log
import com.aeoncorex.aeonruntime.LogLevel

/**
 * Logging facade used by all AeonRuntime modules.
 *
 * Public (not `internal`) because `:os` and `:shizuku` are separate Gradle/Kotlin modules
 * and log through it. Intended for AeonRuntime's own modules; it is not a stable
 * general-purpose logging API and may change.
 */
object RuntimeLogger {
    private const val TAG = "AeonRuntime"
    var level: LogLevel = LogLevel.INFO

    fun v(msg: String) { if (level <= LogLevel.VERBOSE) Log.v(TAG, msg) }
    fun d(msg: String) { if (level <= LogLevel.DEBUG)   Log.d(TAG, msg) }
    fun i(msg: String) { if (level <= LogLevel.INFO)    Log.i(TAG, msg) }
    fun w(msg: String) { if (level <= LogLevel.WARN)    Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) {
        if (level <= LogLevel.ERROR) Log.e(TAG, msg, t)
    }

    private operator fun LogLevel.compareTo(other: LogLevel): Int =
        this.ordinal.compareTo(other.ordinal)
}
