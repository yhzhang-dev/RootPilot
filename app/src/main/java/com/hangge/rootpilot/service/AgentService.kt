package com.hangge.rootpilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hangge.rootpilot.MainActivity
import com.hangge.rootpilot.R
import com.hangge.rootpilot.RootPilotApplication
import com.hangge.rootpilot.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service (course chapter 8) that hosts the loopback Agent API. The notification carries
 * a Stop action; the server is always stopped in onDestroy so no socket outlives the service.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container get() = (application as RootPilotApplication).container

    @Volatile
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            // Rebind after a settings change (lanAccess / requireToken / port). Enter the
            // foreground first so the startForegroundService() contract is met promptly,
            // then swap the listening socket.
            val port = container.settingsStore.current().agentPort
            startInForeground(buildNotification(port, "正在重启…"))
            starting = false
            scope.launch {
                container.agentServer.stop()
                startServer(port)
            }
            return START_STICKY
        }
        val port = container.settingsStore.current().agentPort
        startInForeground(buildNotification(port, "正在启动…"))
        startServer(port)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) { container.agentServer.stop() }
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        if (starting || container.agentServer.running.value) return
        starting = true
        scope.launch {
            val result = container.agentServer.start(port)
            starting = false
            if (result.isSuccess) {
                val bound = container.agentServer.boundPort.value
                notify(buildNotification(bound, "127.0.0.1:$bound · 需要 Token"))
            } else {
                val reason = container.agentServer.lastError.value ?: "未知错误"
                notify(buildNotification(port, reason))
                Log.w(TAG, "Agent server failed to start: $reason")
                stopSelf()
            }
        }
    }

    private fun stopServerAndSelf() {
        scope.launch {
            container.agentServer.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int, detail: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText("$detail")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.agent_notification_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notify(notification: Notification) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.agent_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.agent_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.hangge.rootpilot.action.STOP_AGENT"
        const val ACTION_RESTART = "com.hangge.rootpilot.action.RESTART_AGENT"
        private const val CHANNEL_ID = "rootpilot_agent"
        private const val NOTIFICATION_ID = 8765
        private const val TAG = "AgentService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java),
            )
        }

        /** Re-read settings and rebind the server (used when lanAccess or requireToken changes). */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }

        fun defaultPort(): Int = AppSettings.DEFAULT_AGENT_PORT
    }
}
