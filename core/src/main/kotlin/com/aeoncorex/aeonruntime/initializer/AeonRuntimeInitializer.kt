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
package com.aeoncorex.aeonruntime.initializer

import android.content.Context
import androidx.startup.Initializer
import com.aeoncorex.aeonruntime.utils.RuntimeLogger

/**
 * AndroidX Startup Initializer.
 * Runs automatically on app launch — sets up logging and reads manifest metadata.
 * Does NOT trigger bootstrap installation (that's explicit via AeonRuntime.initialize()).
 */
class AeonRuntimeInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        RuntimeLogger.i("AeonRuntime SDK initialized (v${BuildConfig.LIB_VERSION})")
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
