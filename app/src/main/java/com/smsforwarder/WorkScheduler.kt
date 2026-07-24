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
 * 后台任务入队中心。
 * - 上传：每条短信一个一次性 Work，无限退避重试（进程被杀/重启自动恢复）。
 * - 对账：周期性扫短信库补发漏掉的。
 */
object WorkScheduler {

    private const val RECONCILE_WORK = "sms_reconcile"

    fun enqueueUpload(ctx: Context, sms: Sms) {
        if (!Prefs.isLoggedIn(ctx)) return
        val key = Prefs.dedupKey(sms.sender, sms.body, sms.timestamp)
        val data = Data.Builder()
            .putString(UploadWorker.K_SENDER, sms.sender)
            .putString(UploadWorker.K_BODY, sms.body)
            .putLong(UploadWorker.K_TS, sms.timestamp)
            .putString(UploadWorker.K_KEY, key)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("upload")
            .build()
        // 唯一名 = 去重键，避免广播与对账对同一条重复入队
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload|$key", ExistingWorkPolicy.KEEP, req)
    }

    fun scheduleReconcile(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.reconcileEnabled(ctx)) {
            wm.cancelUniqueWork(RECONCILE_WORK)
            return
        }
        val minutes = Prefs.reconcileMinutes(ctx).toLong()
        val req = PeriodicWorkRequestBuilder<ReconcileWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("reconcile")
            .build()
        wm.enqueueUniquePeriodicWork(RECONCILE_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
