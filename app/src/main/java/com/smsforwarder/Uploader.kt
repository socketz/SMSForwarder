package com.smsforwarder

import android.content.Context

enum class UploadResult {
    SUCCESS,         // 上传成功
    PERMANENT_FAIL,  // 请求本身有问题(4xx)，重试无用 → 记失败、跳过
    TRANSIENT_FAIL,  // 网络/5xx/429/401 → 稍后重试、不前移游标
}

/**
 * 直接上传一条短信到后端（不经 WorkManager）。华为/HarmonyOS 会掐死后台 WorkManager，
 * 所以前台服务轮询到新短信后用这个当场上传（前台服务活着，协程能正常跑网络）。
 * 必须在后台线程调用。
 */
object Uploader {

    fun uploadDirect(ctx: Context, sms: Sms): UploadResult {
        val key = Prefs.dedupKey(sms.sender, sms.body, sms.timestamp)
        return when (val r = Api.uploadSms(ctx, sms, key)) {
            is ApiResp.Ok -> {
                ForwardLog.append(ctx, LogEntry(System.currentTimeMillis(), sms.sender, true, "已上传"))
                UploadResult.SUCCESS
            }
            is ApiResp.Err -> when {
                // 会话失效：不前移游标、持续重试；用户重新登录后自然恢复（不丢消息）
                r.code == 401 -> UploadResult.TRANSIENT_FAIL
                // 请求本身有问题（如 400），重试也没用 → 记失败、前移游标跳过
                r.code in 400..499 && r.code != 429 -> {
                    ForwardLog.append(ctx, LogEntry(System.currentTimeMillis(), sms.sender, false, "上传被拒 (${r.code}): ${r.message}"))
                    UploadResult.PERMANENT_FAIL
                }
                // 网络 / 5xx / 429：瞬时失败，下次轮询重试
                else -> UploadResult.TRANSIENT_FAIL
            }
        }
    }
}
