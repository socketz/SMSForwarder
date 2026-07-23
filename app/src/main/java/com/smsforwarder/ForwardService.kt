package com.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台服务。它本身不负责捕获或转发（那两条路径分别由广播和 WorkManager 独立完成），
 * 职责是：保持进程温热、显示常驻状态通知、并确保周期性对账任务已安排。
 * START_STICKY + 前台通知 + 电池优化白名单，共同保证长期存活。
 */
class ForwardService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_forward_status"
        const val NOTIF_ID = 2001
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        WorkScheduler.scheduleReconcile(this)
        startHeartbeat()
        return START_STICKY  // 被系统杀掉后自动重建
    }

    override fun onDestroy() {
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 每 30s 刷新一次通知里的转发计数，并保证对账任务在位 */
    private fun startHeartbeat() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(NOTIF_ID, buildNotification())
                delay(30_000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val logs = ForwardLog.all(this)
        val total = logs.size
        val failed = logs.count { !it.ok }
        val channels = Prefs.enabledChannels(this)
        val text = if (channels.isEmpty()) {
            getString(R.string.notif_no_channel)
        } else {
            getString(R.string.notif_running_fmt, channels.size, total, failed)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
