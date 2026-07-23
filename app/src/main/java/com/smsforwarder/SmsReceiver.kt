package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 实时捕获：系统收到短信即触发（即使 App 进程已死，系统也会把它拉起来送广播）。
 * 这里只做「拼装 + 去重 + 入队」这类快速操作，绝不在广播里做网络转发——
 * 广播有 ~10s 执行上限，耗时的转发/重试全部交给 WorkManager。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val msgs = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            null
        } ?: return

        // 长短信会拆成多条 PDU，按发件人把正文拼回来
        val bySender = LinkedHashMap<String, StringBuilder>()
        var ts = System.currentTimeMillis()
        for (m in msgs) {
            val addr = m.originatingAddress ?: "unknown"
            bySender.getOrPut(addr) { StringBuilder() }.append(m.messageBody ?: "")
            if (m.timestampMillis > 0) ts = m.timestampMillis
        }

        val ctx = context.applicationContext
        val now = System.currentTimeMillis()  // 用接收时刻作为去重时间，与短信库 date 对齐
        for ((sender, sb) in bySender) {
            val body = sb.toString()
            if (body.isBlank()) continue
            if (Prefs.seenContains(ctx, sender, body, now)) continue
            Prefs.seenAdd(ctx, sender, body, now)
            // 转发记录里用短信自身时间戳；去重用接收时刻
            WorkScheduler.enqueueForward(ctx, Sms(sender, body, if (ts in 1..now) ts else now))
        }
    }
}
