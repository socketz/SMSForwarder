package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 实时捕获：系统收到短信即触发（即使 App 进程已死，系统也会拉起它送广播）。
 * 只做「拼装 + 去重 + 入队上传」这类快操作，网络上传交给 WorkManager。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val msgs = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            null
        } ?: return

        // 长短信拆成多条 PDU，按发件人拼回
        val bySender = LinkedHashMap<String, StringBuilder>()
        var ts = System.currentTimeMillis()
        for (m in msgs) {
            val addr = m.originatingAddress ?: "unknown"
            bySender.getOrPut(addr) { StringBuilder() }.append(m.messageBody ?: "")
            if (m.timestampMillis > 0) ts = m.timestampMillis
        }

        val ctx = context.applicationContext
        if (!Prefs.isLoggedIn(ctx)) return // 未登录不处理
        val now = System.currentTimeMillis()
        for ((sender, sb) in bySender) {
            val body = sb.toString()
            if (body.isBlank()) continue
            if (Prefs.seenContains(ctx, sender, body, now)) continue
            Prefs.seenAdd(ctx, sender, body, now)
            WorkScheduler.enqueueUpload(ctx, Sms(sender, body, if (ts in 1..now) ts else now))
        }
    }
}
