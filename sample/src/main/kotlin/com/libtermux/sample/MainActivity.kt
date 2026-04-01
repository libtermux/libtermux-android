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

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.libtermux.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupListeners()

        // Auto-install on launch
        vm.install()
    }

    private fun setupObservers() {
        vm.uiState.onEach { state ->
            binding.tvStatus.text = state.status
            binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            if (state.isLoading && state.installProgress > 0f) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = (state.installProgress * 100).toInt()
            } else if (state.isLoading) {
                binding.progressBar.isIndeterminate = true
            }
            if (state.isReady) {
                binding.tvStatus.setTextColor(getColor(R.color.catppuccin_green))
            }
        }.launchIn(lifecycleScope)

        vm.events.onEach { event ->
            when (event) {
                is UiEvent.Output -> binding.terminalView.appendText(event.text, event.isError)
                is UiEvent.Toast  -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }
        }.launchIn(lifecycleScope)
    }

    private fun setupListeners() {
        binding.btnInstall.setOnClickListener { vm.install(forceReinstall = false) }
        binding.btnPython.setOnClickListener  { vm.runPythonDemo() }
        binding.btnSysInfo.setOnClickListener { vm.runSysInfo() }
        binding.btnClear.setOnClickListener   { binding.terminalView.clear() }

        binding.btnRun.setOnClickListener {
            val cmd = binding.etCommand.text?.toString()?.trim() ?: return@setOnClickListener
            if (cmd.isNotEmpty()) {
                vm.runCommand(cmd)
                binding.etCommand.text?.clear()
            }
        }

        binding.etCommand.setOnEditorActionListener { _, _, _ ->
            binding.btnRun.performClick()
            true
        }
    }
}
