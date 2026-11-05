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

import com.aeoncorex.aeonruntime.AeonRuntime
import com.aeoncorex.aeonruntime.bridge.RuntimeBridge
import com.aeoncorex.aeonruntime.executor.ExecutionResult

// ── Suspend DSL extensions ────────────────────────────────────────────────

/**
 * Run a block with bridge access after ensuring AeonRuntime is ready.
 * ```kotlin
 * runtime.withBridge {
 *     val result = run("ls -la")
 *     println(result.stdout)
 * }
 * ```
 */
suspend fun AeonRuntime.withBridge(block: suspend RuntimeBridge.() -> Unit) {
    ensureReady()
    bridge.block()
}

/**
 * Run command and pipe result to next command (like shell pipe).
 */
suspend fun RuntimeBridge.pipe(vararg commands: String): ExecutionResult {
    return run(commands.joinToString(" | "))
}

/**
 * Run command and get stdout lines as List<String>.
 */
suspend fun RuntimeBridge.lines(command: String): List<String> =
    run(command).stdoutLines()

/**
 * Run command silently — returns true if exit code is 0.
 */
suspend fun RuntimeBridge.check(command: String): Boolean =
    run(command).isSuccess

/**
 * Install packages only if the binary is not already available.
 */
suspend fun RuntimeBridge.ensureInstalled(vararg packages: String) {
    packages.forEach { pkg ->
        if (!hasCommand(pkg)) install(pkg)
    }
}

// ── Formatting ────────────────────────────────────────────────────────────

fun ExecutionResult.prettyPrint(): String = buildString {
    appendLine("═══════════════════════════════════════")
    appendLine("CMD:  $command")
    appendLine("EXIT: $exitCode  TIME: ${executionTimeMs}ms")
    if (stdout.isNotEmpty()) { appendLine("STDOUT:"); appendLine(stdout) }
    if (stderr.isNotEmpty()) { appendLine("STDERR:"); appendLine(stderr) }
    appendLine("═══════════════════════════════════════")
}
