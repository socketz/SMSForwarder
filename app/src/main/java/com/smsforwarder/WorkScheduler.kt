package com.smsforwarder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 所有后台任务的入队中心。
 * - 转发：每条短信一个一次性 Work，内部按 主接口→备用接口 降级；带指数退避重试。
 * - 对账：一个周期性 Work，定期扫短信库补发漏掉的。
 * WorkManager 自带持久化，进程被杀 / 手机重启后都会自动恢复未完成任务。
 */
object WorkScheduler {

    private const val RECONCILE_WORK = "sms_reconcile"

    /** 为一条短信入队一个转发任务（主备降级在 ForwardWorker 内部完成） */
    fun enqueueForward(ctx: Context, sms: Sms) {
        if (!Prefs.isConfigured(ctx)) return
        val wm = WorkManager.getInstance(ctx)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putString(ForwardWorker.K_SENDER, sms.sender)
            .putString(ForwardWorker.K_BODY, sms.body)
            .putLong(ForwardWorker.K_TS, sms.timestamp)
            .build()
        val req = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("forward")
            .build()
        // 唯一名 = 短信键，避免广播与对账对同一条重复入队
        val name = "fwd|${sms.sender}|${sms.body.hashCode()}|${sms.timestamp / 60_000L}"
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
    }

    /** 安排 / 刷新周期性对账任务 */
    fun scheduleReconcile(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.reconcileEnabled(ctx)) {
            wm.cancelUniqueWork(RECONCILE_WORK)
            return
        }
        val minutes = Prefs.reconcileMinutes(ctx).toLong()
        val req = PeriodicWorkRequestBuilder<ReconcileWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag("reconcile")
            .build()
        // UPDATE：配置变更（间隔）后用新参数替换已有周期任务
        wm.enqueueUniquePeriodicWork(
            RECONCILE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}
