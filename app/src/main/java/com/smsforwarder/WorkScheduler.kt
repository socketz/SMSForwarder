package com.smsforwarder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 只剩周期性对账任务的安排。抓取+上传的热路径已改为前台服务轮询 + 广播触发扫描
 * （见 SmsScanner / SmsReceiver），不再用 WorkManager（华为会掐死它）。
 * 这里的 ReconcileWorker 只是"服务没在跑时"的 15 分钟兜底（非华为机型上有效）。
 */
object WorkScheduler {

    private const val RECONCILE_WORK = "sms_reconcile"

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
        wm.enqueueUniquePeriodicWork(RECONCILE_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
