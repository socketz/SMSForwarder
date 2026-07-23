package com.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一条短信转发到一个渠道。失败则交给 WorkManager 指数退避重试，
 * 超过最大次数才记为永久失败。每个 (短信 × 渠道) 是一个独立 Worker，互不影响。
 */
class ForwardWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val K_SENDER = "sender"
        const val K_BODY = "body"
        const val K_TS = "ts"
        const val K_CHANNEL = "channel"
        private const val MAX_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val channel = inputData.getString(K_CHANNEL) ?: return@withContext Result.failure()
        val sms = Sms(
            sender = inputData.getString(K_SENDER) ?: "",
            body = inputData.getString(K_BODY) ?: "",
            timestamp = inputData.getLong(K_TS, System.currentTimeMillis()),
        )

        val sender = senderFor(channel) ?: return@withContext Result.failure()

        val result = sender.send(ctx, sms)
        if (result.ok) {
            ForwardLog.append(ctx, LogEntry(System.currentTimeMillis(), sms.sender, channel, true, result.detail))
            return@withContext Result.success()
        }

        // 失败：还有重试机会就退避重试，否则记为永久失败
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            ForwardLog.append(
                ctx,
                LogEntry(System.currentTimeMillis(), sms.sender, channel, false, "重试${MAX_ATTEMPTS}次仍失败: ${result.detail}")
            )
            Result.failure()
        }
    }
}
