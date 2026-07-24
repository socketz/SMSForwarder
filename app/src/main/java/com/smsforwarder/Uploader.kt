package com.smsforwarder

import android.content.Context

/**
 * 直接上传一条短信到后端（不经 WorkManager）。华为/HarmonyOS 会掐死后台 WorkManager，
 * 所以前台服务轮询到新短信后用这个当场上传（前台服务活着，协程能正常跑网络）。
 * 必须在后台线程调用。
 */
object Uploader {

    /**
     * @return true = 已处理（上传成功，或永久失败无需重试）→ 调用方可前移游标；
     *         false = 瞬时失败（网络 / 5xx / 429 / 401 会话失效）→ 稍后重试、不要前移游标。
     */
    fun uploadDirect(ctx: Context, sms: Sms): Boolean {
        val key = Prefs.dedupKey(sms.sender, sms.body, sms.timestamp)
        return when (val r = Api.uploadSms(ctx, sms, key)) {
            is ApiResp.Ok -> {
                ForwardLog.append(ctx, LogEntry(System.currentTimeMillis(), sms.sender, true, "已上传"))
                true
            }
            is ApiResp.Err -> when {
                // 会话失效：先不前移游标、持续重试；用户重新登录后自然恢复（不丢消息）
                r.code == 401 -> false
                // 请求本身有问题（如 400），重试也没用 → 记失败、前移游标跳过
                r.code in 400..499 && r.code != 429 -> {
                    ForwardLog.append(ctx, LogEntry(System.currentTimeMillis(), sms.sender, false, "上传被拒 (${r.code}): ${r.message}"))
                    true
                }
                // 网络 / 5xx / 429：瞬时失败，下次轮询重试
                else -> false
            }
        }
    }
}
