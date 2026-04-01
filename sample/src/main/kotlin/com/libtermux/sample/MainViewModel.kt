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
package com.libtermux.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libtermux.LibTermux
import com.libtermux.LibTermuxState
import com.libtermux.bootstrap.InstallState
import com.libtermux.executor.ExecutionResult
import com.libtermux.termuxConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val status: String        = "Idle",
    val isLoading: Boolean    = false,
    val installProgress: Float = 0f,
    val isReady: Boolean      = false,
    val diskUsage: String     = "",
)

sealed class UiEvent {
    data class Output(val text: String, val isError: Boolean = false) : UiEvent()
    data class Toast(val message: String)                              : UiEvent()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val termux = LibTermux.create(app) {
        autoInstall    = true
        logLevel       = com.libtermux.LogLevel.DEBUG
        backgroundExecutionEnabled = true
        env("COLORTERM", "truecolor")
    }

    private val _uiState  = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // ── Install ──────────────────────────────────────────────────────────

    fun install(forceReinstall: Boolean = false) {
        viewModelScope.launch {
            termux.initialize(forceReinstall).collect { state ->
                when (state) {
                    is InstallState.Checking          -> updateStatus("Checking…", true)
                    is InstallState.AlreadyInstalled  -> {
                        updateStatus("Already installed", false)
                        onReady()
                    }
                    is InstallState.Downloading       -> {
                        val mb = state.bytesDownloaded / (1024 * 1024)
                        val totalMb = state.totalBytes / (1024 * 1024)
                        updateStatus("Downloading ${mb}MB / ${totalMb}MB", true, state.progress)
                    }
                    is InstallState.Extracting        -> updateStatus("Extracting…", true, state.progress)
                    is InstallState.ProcessingSymlinks-> updateStatus("Setting up symlinks…", true)
                    is InstallState.SettingPermissions-> updateStatus("Setting permissions…", true)
                    is InstallState.Verifying         -> updateStatus("Verifying…", true)
                    is InstallState.Completed         -> {
                        updateStatus("Ready ✓", false)
                        emit(UiEvent.Output("Bootstrap installed successfully!"))
                        onReady()
                    }
                    is InstallState.Failed            -> {
                        updateStatus("Error: ${state.error}", false)
                        emit(UiEvent.Toast("Installation failed: ${state.error}"))
                    }
                }
            }
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────

    fun runCommand(command: String) {
        if (!termux.isInitialized) {
            emit(UiEvent.Toast("Please install bootstrap first"))
            return
        }
        viewModelScope.launch {
            emit(UiEvent.Output("$ $command"))
            val result = termux.bridge.run(command)
            if (result.stdout.isNotEmpty()) emit(UiEvent.Output(result.stdout))
            if (result.stderr.isNotEmpty()) emit(UiEvent.Output(result.stderr, true))
            emit(UiEvent.Output("[Exit: ${result.exitCode}]"))
        }
    }

    fun runPythonDemo() {
        if (!termux.isInitialized) { emit(UiEvent.Toast("Install bootstrap first")); return }
        viewModelScope.launch {
            emit(UiEvent.Output("$ python3 [demo script]"))
            val result = termux.bridge.python("""
                import sys, platform, json
                info = {
                    "python": sys.version,
                    "platform": platform.platform(),
                    "machine": platform.machine(),
                }
                import json
                print(json.dumps(info, indent=2))
            """.trimIndent())
            emit(UiEvent.Output(result.stdout, result.isFailure))
            if (result.stderr.isNotEmpty()) emit(UiEvent.Output(result.stderr, true))
        }
    }

    fun runSysInfo() {
        if (!termux.isInitialized) { emit(UiEvent.Toast("Install bootstrap first")); return }
        viewModelScope.launch {
            emit(UiEvent.Output("─── System Information ───"))
            listOf("uname -a", "cat /proc/version", "free -h", "df -h \$PREFIX").forEach { cmd ->
                emit(UiEvent.Output("$ $cmd"))
                val r = termux.bridge.run(cmd)
                emit(UiEvent.Output(r.output, r.isFailure))
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun onReady() {
        _uiState.update { it.copy(isReady = true, diskUsage = termux.vfs.diskUsageString()) }
    }

    private fun updateStatus(msg: String, loading: Boolean, progress: Float = 0f) {
        _uiState.update { it.copy(status = msg, isLoading = loading, installProgress = progress) }
    }

    private fun emit(event: UiEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    override fun onCleared() {
        termux.destroy()
        super.onCleared()
    }
}
