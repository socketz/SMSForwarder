package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 从短信数据库 content://sms/inbox 扫描新短信并入队上传。
 * 这是「轮询」抓取路径——华为/HarmonyOS 常常不给第三方 App 下发 SMS_RECEIVED 广播，
 * 但读短信库(有 READ_SMS)是可行的，所以前台服务用它做主力抓取；广播作为补充。
 *
 * 必须在后台线程调用（有 ContentProvider 查询）。与广播路径共用去重集合，不会重复转发。
 */
object SmsScanner {

    /** 扫描一次：把游标之后、尚未见过的短信入队上传。首次运行只把游标设到「现在」，不回补历史。 */
    fun scan(ctx: Context) {
        if (!Prefs.isLoggedIn(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val p = Prefs.get(ctx)
        val lastSeen = p.getLong(Prefs.KEY_LAST_SEEN_TS, 0L)
        if (lastSeen == 0L) {
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, System.currentTimeMillis()).apply()
            return
        }

        var maxDate = lastSeen
        try {
            val uri = Uri.parse("content://sms/inbox")
            val projection = arrayOf("address", "body", "date")
            ctx.contentResolver.query(
                uri, projection, "date > ?", arrayOf(lastSeen.toString()), "date ASC"
            )?.use { c ->
                val idxAddr = c.getColumnIndex("address")
                val idxBody = c.getColumnIndex("body")
                val idxDate = c.getColumnIndex("date")
                while (c.moveToNext()) {
                    val sender = if (idxAddr >= 0) c.getString(idxAddr) ?: "" else ""
                    val body = if (idxBody >= 0) c.getString(idxBody) ?: "" else ""
                    val date = if (idxDate >= 0) c.getLong(idxDate) else System.currentTimeMillis()
                    if (date > maxDate) maxDate = date
                    if (body.isBlank()) continue
                    if (Prefs.seenContains(ctx, sender, body, date)) continue
                    Prefs.seenAdd(ctx, sender, body, date)
                    WorkScheduler.enqueueUpload(ctx, Sms(sender, body, date))
                }
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, maxDate).apply()
        } catch (_: Exception) {
        }
    }
}
