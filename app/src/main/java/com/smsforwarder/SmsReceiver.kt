package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 实时捕获:收到短信广播就**立即触发一次扫描**(SmsScanner 读短信库直接上传)。
 * 不再自己拼 PDU、不再入队 WorkManager、也不再提前标记"已见"——统一走 SmsScanner 那条
 * 可靠路径(_id 游标 + 直接上传 + 服务端幂等)。广播能触发就即时,触发不了也有前台服务的
 * 周期轮询兜底。广播里只做快操作:开个后台线程扫一次,goAsync 争取到执行时间。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val ctx = context.applicationContext
        if (!Prefs.isLoggedIn(ctx)) return

        val pending = goAsync()
        Thread {
            try {
                SmsScanner.scan(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
