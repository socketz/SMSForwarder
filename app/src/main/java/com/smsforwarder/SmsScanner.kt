package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 从短信数据库 content://sms/inbox 扫描新短信并**直接上传**（不经 WorkManager——华为会掐死它）。
 * 由前台服务每 ~15s 调用一次（近实时），也被周期性 ReconcileWorker 兜底调用。
 * 必须在后台线程调用（ContentProvider 查询 + 网络上传）。与广播路径共用去重集合。
 */
object SmsScanner {

    private const val BATCH = 50 // 单次最多处理条数，避免一次轮询卡太久

    fun scan(ctx: Context) {
        if (!Prefs.isLoggedIn(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val p = Prefs.get(ctx)
        val lastSeen = p.getLong(Prefs.KEY_LAST_SEEN_TS, 0L)
        // 首次运行只把游标设到「现在」，不回补历史
        if (lastSeen == 0L) {
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, System.currentTimeMillis()).apply()
            return
        }

        var cursor = lastSeen
        try {
            val uri = Uri.parse("content://sms/inbox")
            val projection = arrayOf("address", "body", "date")
            ctx.contentResolver.query(
                uri, projection, "date > ?", arrayOf(lastSeen.toString()), "date ASC"
            )?.use { c ->
                val idxAddr = c.getColumnIndex("address")
                val idxBody = c.getColumnIndex("body")
                val idxDate = c.getColumnIndex("date")
                var processed = 0
                while (c.moveToNext()) {
                    val sender = if (idxAddr >= 0) c.getString(idxAddr) ?: "" else ""
                    val body = if (idxBody >= 0) c.getString(idxBody) ?: "" else ""
                    val date = if (idxDate >= 0) c.getLong(idxDate) else System.currentTimeMillis()

                    if (body.isBlank()) { cursor = date; continue }
                    if (Prefs.seenContains(ctx, sender, body, date)) { cursor = date; continue }

                    val handled = Uploader.uploadDirect(ctx, Sms(sender, body, date))
                    if (!handled) break // 瞬时失败：保留游标，下次轮询重试

                    Prefs.seenAdd(ctx, sender, body, date)
                    cursor = date
                    if (++processed >= BATCH) break
                }
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, cursor).apply()
        } catch (_: Exception) {
        }
    }
}
