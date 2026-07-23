package com.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 转发一条短信：先发主接口，主接口失败再降级到备用接口。
 * 两个接口都失败才交给 WorkManager 指数退避重试；超过最大次数记为永久失败。
 * 每条短信一个 Worker（不再按渠道拆分——渠道分发交给服务器端）。
 */
class ForwardWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val K_SENDER = "sender"
        const val K_BODY = "body"
        const val K_TS = "ts"
        private const val MAX_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val sms = Sms(
            sender = inputData.getString(K_SENDER) ?: "",
            body = inputData.getString(K_BODY) ?: "",
            timestamp = inputData.getLong(K_TS, System.currentTimeMillis()),
        )

        val primary = Prefs.primaryUrl(ctx)
        val backup = Prefs.backupUrl(ctx)
        val headers = Prefs.headers(ctx)
        if (primary.isBlank()) return@withContext Result.failure()  // 没配主接口，无处可发

        // 1) 主接口
        val r1 = HttpForwarder.post(primary, headers, sms)
        if (r1.ok) {
            ForwardLog.append(ctx, LogEntry(now(), sms.sender, Prefs.CH_PRIMARY, true, r1.detail))
            return@withContext Result.success()
        }

        // 2) 主接口挂了 → 降级到备用接口
        if (backup.isNotBlank()) {
            val r2 = HttpForwarder.post(backup, headers, sms)
            if (r2.ok) {
                ForwardLog.append(
                    ctx,
                    LogEntry(now(), sms.sender, Prefs.CH_BACKUP, true, "主接口失败(${r1.detail})，已降级备用")
                )
                return@withContext Result.success()
            }
            return@withContext retryOrFail(ctx, sms, "主(${r1.detail}) / 备(${r2.detail}) 均失败")
        }

        // 只配了主接口且失败
        retryOrFail(ctx, sms, "主接口失败: ${r1.detail}")
    }

    private fun retryOrFail(ctx: Context, sms: Sms, detail: String): Result {
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            ForwardLog.append(ctx, LogEntry(now(), sms.sender, Prefs.CH_PRIMARY, false, "重试${MAX_ATTEMPTS}次仍失败: $detail"))
            Result.failure()
        }
    }

    private fun now() = System.currentTimeMillis()
}
