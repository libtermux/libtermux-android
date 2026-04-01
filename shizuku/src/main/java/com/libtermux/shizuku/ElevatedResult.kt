package com.libtermux.shizuku

import com.libtermux.executor.ExecutionResult

/**
 * Result of an elevated command.
 */
data class ElevatedResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val elevated: Boolean,
) {
    val isSuccess: Boolean get() = exitCode == 0

    companion object {
        fun fromExecutionResult(result: ExecutionResult, elevated: Boolean) = ElevatedResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            elevated = elevated,
        )
    }
}