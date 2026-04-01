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

import com.libtermux.TermuxConfig
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Core process executor that runs commands inside the VFS environment.
 */
class CommandExecutor(
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {

    /**
     * Execute a command and return a complete [ExecutionResult].
     *
     * @param command   Shell command string (e.g. "python3 -c 'print(1+1)'")
     * @param workDir   Working directory (defaults to HOME)
     * @param extraEnv  Extra environment variables
     * @param shell     Shell binary name in PREFIX/bin (default: bash)
     */
    suspend fun execute(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        TermuxLogger.d("exec: $command")

        val result = withTimeoutOrNull(config.maxCommandTimeoutMs) {
            runProcess(command, workDir, extraEnv, shell)
        } ?: ExecutionResult(
            stdout         = "",
            stderr         = "Command timed out after ${config.maxCommandTimeoutMs}ms",
            exitCode       = -1,
            executionTimeMs= config.maxCommandTimeoutMs,
            command        = command,
        )

        TermuxLogger.d("exit=${result.exitCode} time=${result.executionTimeMs}ms")
        result
    }

    /**
     * Execute a command and stream output line by line via [OutputLine].
     */
    fun executeStreaming(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): Flow<OutputLine> = flow {
        TermuxLogger.d("stream exec: $command")
        val proc = buildProcess(command, workDir, extraEnv, shell)

        val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))

        // Read stdout and stderr concurrently
        val stdoutThread = Thread {
            runCatching {
                stdoutReader.forEachLine { /* stored in main loop */ }
            }
        }

        try {
            // Interleave stdout/stderr
            val stdoutLines = mutableListOf<String>()
            val stderrLines = mutableListOf<String>()

            Thread { stdoutReader.forEachLine { stdoutLines.add(it) } }.also { it.start() }.join(config.maxCommandTimeoutMs)
            Thread { stderrReader.forEachLine { stderrLines.add(it) } }.also { it.start() }.join(100)

            stdoutLines.forEach { emit(OutputLine.Stdout(it)) }
            stderrLines.forEach { emit(OutputLine.Stderr(it)) }

            val exit = proc.waitFor()
            emit(OutputLine.Exit(exit))
        } finally {
            proc.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Execute a Python script string directly.
     */
    suspend fun executePython(
        script: String,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.py")
        scriptFile.writeText(script)
        val argStr = args.joinToString(" ")
        return try {
            execute("python3 ${scriptFile.absolutePath} $argStr".trim(), extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a Node.js script string directly.
     */
    suspend fun executeNode(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.js")
        scriptFile.writeText(script)
        return try {
            execute("node ${scriptFile.absolutePath}", extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a shell script file.
     */
    suspend fun executeScript(
        scriptFile: File,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult {
        scriptFile.setExecutable(true, false)
        val argStr = args.joinToString(" ")
        return execute(
            command  = "${scriptFile.absolutePath} $argStr".trim(),
            extraEnv = extraEnv,
            shell    = shell,
        )
    }

    /**
     * Test if a binary exists in PREFIX/bin.
     */
    suspend fun hasBinary(name: String): Boolean {
        val result = execute("which $name")
        return result.isSuccess && result.stdout.isNotBlank()
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun runProcess(
        command: String,
        workDir: File?,
        extraEnv: Map<String, String>,
        shell: String,
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val proc = buildProcess(command, workDir, extraEnv, shell)
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit   = proc.waitFor()
        return ExecutionResult(
            stdout          = stdout.trimEnd(),
            stderr          = stderr.trimEnd(),
            exitCode        = exit,
            executionTimeMs = System.currentTimeMillis() - startTime,
            command         = command,
        )
    }

    private fun buildProcess(
        command: String,
        workDir: File?,
        extraEnv: Map<String, String>,
        shell: String,
    ): Process {
        val shellBin = File(vfs.binDir, shell).let {
            if (it.exists()) it.absolutePath else "/system/bin/sh"
        }
        val env = vfs.buildEnv(extraEnv)

        return ProcessBuilder(shellBin, "-c", command)
            .directory(workDir ?: vfs.homeDir)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(env)
            }
            .redirectErrorStream(false)
            .start()
    }
}
