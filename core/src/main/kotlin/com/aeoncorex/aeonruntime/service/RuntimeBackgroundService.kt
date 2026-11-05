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
package com.aeoncorex.aeonruntime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aeoncorex.aeonruntime.RuntimeConfig
import com.aeoncorex.aeonruntime.executor.CommandExecutor
import com.aeoncorex.aeonruntime.executor.ExecutionResult
import com.aeoncorex.aeonruntime.utils.RuntimeLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps the AeonRuntime environment alive
 * even when the host app is in the background.
 *
 * Usage:
 * ```kotlin
 * RuntimeBackgroundService.start(context, config)
 * RuntimeBackgroundService.stop(context)
 * RuntimeBackgroundService.runCommand(context, "job-1", "python3 long_script.py")
 * ```
 */
class RuntimeBackgroundService : Service() {

    private val binder = LocalBinder()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _runningJobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val runningJobs: StateFlow<List<BackgroundJob>> = _runningJobs

    private var executor: CommandExecutor? = null
    private var config: RuntimeConfig = RuntimeConfig.default()

    inner class LocalBinder : Binder() {
        fun getService(): RuntimeBackgroundService = this@RuntimeBackgroundService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.i("RuntimeBackgroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                RuntimeLogger.i("RuntimeBackgroundService started as foreground")
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_RUN_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: cmd.take(20)
                runCommandInBackground(jobId, cmd)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        RuntimeLogger.i("RuntimeBackgroundService destroyed")
        super.onDestroy()
    }

    /** Attach executor (called by AeonRuntime after bootstrap init) */
    fun attachExecutor(exec: CommandExecutor, cfg: RuntimeConfig) {
        executor = exec
        config   = cfg
    }

    /** Run a command in background and track it */
    fun runCommandInBackground(
        jobId: String,
        command: String,
        onComplete: ((ExecutionResult) -> Unit)? = null,
    ): BackgroundJob {
        val job = BackgroundJob(id = jobId, command = command)
        _runningJobs.value = _runningJobs.value + job

        scope.launch {
            try {
                job.status = JobStatus.RUNNING
                val exec = executor ?: run {
                    job.status = JobStatus.FAILED
                    return@launch
                }
                val result = exec.execute(command)
                job.result = result
                job.status = if (result.isSuccess) JobStatus.COMPLETED else JobStatus.FAILED
                onComplete?.invoke(result)
            } catch (e: Exception) {
                job.status = JobStatus.FAILED
                RuntimeLogger.e("Background job '$jobId' failed", e)
            } finally {
                delay(5_000)
                _runningJobs.value = _runningJobs.value.filterNot { it.id == jobId }
                updateNotification()
            }
        }

        updateNotification()
        return job
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                config.notificationChannelId,
                config.notificationChannelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "AeonRuntime background execution service"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val jobCount = _runningJobs.value.size
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RuntimeBackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, config.notificationChannelId)
            .setContentTitle(config.notificationTitle)
            .setContentText(if (jobCount > 0) "$jobCount job(s) running" else "Ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── Companion ────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID = 0xA77E
        const val ACTION_START       = "com.aeoncorex.aeonruntime.START"
        const val ACTION_STOP        = "com.aeoncorex.aeonruntime.STOP"
        const val ACTION_RUN_COMMAND = "com.aeoncorex.aeonruntime.RUN_COMMAND"
        const val EXTRA_COMMAND      = "command"
        const val EXTRA_JOB_ID       = "job_id"

        @JvmStatic
        fun start(context: Context, config: RuntimeConfig = RuntimeConfig.default()) {
            val intent = Intent(context, RuntimeBackgroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.startService(
                Intent(context, RuntimeBackgroundService::class.java).setAction(ACTION_STOP)
            )
        }

        @JvmStatic
        fun runCommand(context: Context, jobId: String, command: String) {
            context.startService(
                Intent(context, RuntimeBackgroundService::class.java).apply {
                    action = ACTION_RUN_COMMAND
                    putExtra(EXTRA_JOB_ID, jobId)
                    putExtra(EXTRA_COMMAND, command)
                }
            )
        }
    }
}

data class BackgroundJob(
    val id: String,
    val command: String,
    val startedAt: Long = System.currentTimeMillis(),
    @Volatile var status: JobStatus = JobStatus.QUEUED,
    @Volatile var result: ExecutionResult? = null,
)

enum class JobStatus { QUEUED, RUNNING, COMPLETED, FAILED }
