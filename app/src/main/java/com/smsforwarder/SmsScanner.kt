package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 从短信数据库 content://sms/inbox 扫描新短信并**直接上传**（不经 WorkManager——华为会掐死它）。
 * 由前台服务每隔 N 秒调用一次，也被周期性 ReconcileWorker 兜底调用。
 * 必须在后台线程调用（ContentProvider 查询 + 网络上传）。与广播路径共用去重集合。
 *
 * 只发"该发的":只查 date > 游标 的收件箱短信、跳过空正文与已见过的;游标只在"成功/永久失败"后前移。
 */
object SmsScanner {

    private const val BATCH = 50 // 单次最多处理条数，避免一次轮询卡太久

    fun scan(ctx: Context) {
        // 记录本次轮询时刻（供运行日志显示"上次/下次轮询"）
        Prefs.get(ctx).edit().putLong(Prefs.KEY_LAST_POLL_TS, System.currentTimeMillis()).apply()

        if (!Prefs.isLoggedIn(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val p = Prefs.get(ctx)
        val lastSeen = p.getLong(Prefs.KEY_LAST_SEEN_TS, 0L)
        // 首次运行只把游标设到「现在」，不回补历史（那 2000 多条旧短信不会被发）
        if (lastSeen == 0L) {
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, System.currentTimeMillis()).apply()
            DebugLog.log(ctx, "首次运行：游标初始化到现在，此后收到的新短信才会转发")
            return
        }

        var cursor = lastSeen
        var found = 0
        var ok = 0
        var permFail = 0
        var transient = 0
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

                    found++
                    when (Uploader.uploadDirect(ctx, Sms(sender, body, date))) {
                        UploadResult.TRANSIENT_FAIL -> {
                            transient++
                            // 瞬时失败：保留游标，下次轮询重试（不前移、不标记已见）
                            break
                        }
                        UploadResult.SUCCESS -> {
                            ok++; Prefs.seenAdd(ctx, sender, body, date); cursor = date
                        }
                        UploadResult.PERMANENT_FAIL -> {
                            permFail++; Prefs.seenAdd(ctx, sender, body, date); cursor = date
                        }
                    }
                    if (++processed >= BATCH) break
                }
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, cursor).apply()
            if (found > 0 || transient > 0) {
                DebugLog.log(ctx, "轮询：新增 $found｜成功 $ok｜失败(永久) $permFail｜失败(重试) $transient")
            }
        } catch (e: Exception) {
            DebugLog.log(ctx, "读短信库异常：${e.message}")
        }
    }
}
