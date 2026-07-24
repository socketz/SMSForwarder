package com.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 广播路径的上传 Worker（非华为机型上，SMS_RECEIVED 触发后走这里即时上传）。
 * 华为上 WorkManager 通常不执行，热路径由前台服务的直接上传(Uploader)承担，这里作为
 * 非华为的即时通道 + 兜底。实际上传逻辑复用 Uploader。
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val K_SENDER = "sender"
        const val K_BODY = "body"
        const val K_TS = "ts"
        const val K_KEY = "key"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!Prefs.isLoggedIn(ctx)) return@withContext Result.failure()

        val sms = Sms(
            sender = inputData.getString(K_SENDER) ?: "",
            body = inputData.getString(K_BODY) ?: "",
            timestamp = inputData.getLong(K_TS, System.currentTimeMillis()),
        )
        when (Uploader.uploadDirect(ctx, sms)) {
            UploadResult.SUCCESS -> Result.success()
            UploadResult.PERMANENT_FAIL -> Result.failure()
            UploadResult.TRANSIENT_FAIL -> Result.retry()
        }
    }
}
