package com.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 周期性兜底对账（最短 15 分钟）：当前台服务没在跑时的后备抓取。实际扫描逻辑在 SmsScanner，
 * 与前台服务的高频轮询共用一套（含去重与游标）。
 */
class ReconcileWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SmsScanner.scan(applicationContext)
        Result.success()
    }
}
