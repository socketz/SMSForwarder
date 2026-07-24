package com.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一条短信上传到后端 /api/sms。转发到各渠道由服务器完成，这里只负责"送达后端"。
 * 这条链路是唯一命脉：网络/5xx 失败就无限退避重试（WorkManager 默认无限重试），
 * 绝不因重试耗尽而丢消息；只有 401（需重新登录）和 4xx（请求本身有问题）才永久失败。
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
        if (!Prefs.isLoggedIn(ctx)) return@withContext Result.failure() // 未登录，无法上传

        val sms = Sms(
            sender = inputData.getString(K_SENDER) ?: "",
            body = inputData.getString(K_BODY) ?: "",
            timestamp = inputData.getLong(K_TS, System.currentTimeMillis()),
        )
        val key = inputData.getString(K_KEY) ?: Prefs.dedupKey(sms.sender, sms.body, sms.timestamp)

        when (val r = Api.uploadSms(ctx, sms, key)) {
            is ApiResp.Ok -> {
                ForwardLog.append(ctx, LogEntry(now(), sms.sender, true, "已上传"))
                Result.success()
            }
            is ApiResp.Err -> when {
                r.code == 401 -> {
                    // token 失效/被吊销：停止并提示重新登录，不再重试
                    ForwardLog.append(ctx, LogEntry(now(), sms.sender, false, "需要重新登录 (401)"))
                    Result.failure()
                }
                r.code in 400..499 && r.code != 429 -> {
                    // 请求本身有问题，重试也没用
                    ForwardLog.append(ctx, LogEntry(now(), sms.sender, false, "上传被拒 (${r.code}): ${r.message}"))
                    Result.failure()
                }
                else -> Result.retry() // 网络(-1) / 5xx / 429 → 无限退避重试
            }
        }
    }

    private fun now() = System.currentTimeMillis()
}
