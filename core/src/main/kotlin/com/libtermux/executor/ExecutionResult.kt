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
package com.libtermux.executor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Result of a command execution.
 */
@Serializable
data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val executionTimeMs: Long,
    val command: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val isFailure: Boolean get() = !isSuccess

    /** Combined stdout + stderr */
    val output: String get() = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
            if (stdout.isNotEmpty()) appendLine()
            append(stderr)
        }
    }

    inline fun <reified T> parseJson(): T =
        Json.decodeFromString(stdout.trim())

    fun stdoutLines(): List<String> =
        stdout.lines().filter { it.isNotEmpty() }

    override fun toString() =
        "ExecutionResult(exit=$exitCode, time=${executionTimeMs}ms, cmd='$command')"
}

sealed class OutputLine {
    data class Stdout(val text: String) : OutputLine()
    data class Stderr(val text: String) : OutputLine()
    data class Exit(val code: Int)      : OutputLine()
}
