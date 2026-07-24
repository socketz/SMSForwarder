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
 * 游标用 **`_id`(自增主键)** 而非时间戳——新短信 _id 一定更大、单调递增，不受 SMS `date`
 * 可能落后于手机钟的影响（用 date 会导致新短信被误判为"旧的"而跳过）。
 * 首次运行把游标设到当前最大 _id（不回补历史）；此后只发 _id 更大的。
 */
object SmsScanner {

    private const val BATCH = 50
    private val URI: Uri = Uri.parse("content://sms/inbox")

    fun scan(ctx: Context) {
        Prefs.get(ctx).edit().putLong(Prefs.KEY_LAST_POLL_TS, System.currentTimeMillis()).apply()

        if (!Prefs.isLoggedIn(ctx)) { DebugLog.log(ctx, "轮询跳过：未登录"); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.log(ctx, "轮询跳过：无读取短信权限(READ_SMS)")
            return
        }

        val p = Prefs.get(ctx)
        val lastId = p.getLong(Prefs.KEY_LAST_SEEN_ID, -1L)

        // 首次运行：游标设到当前最大 _id，不回补历史
        if (lastId < 0L) {
            val maxId = maxInboxId(ctx)
            if (maxId == null) {
                DebugLog.log(ctx, "首次运行：读库失败，稍后重试")
                return
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_ID, maxId).apply()
            DebugLog.log(ctx, "首次运行：游标(id)初始化到 $maxId，此后收到的新短信才会转发")
            return
        }

        var newId = lastId
        var rows = 0
        var skippedBlank = 0
        var skippedSeen = 0
        var found = 0
        var ok = 0
        var permFail = 0
        var transient = 0
        try {
            val projection = arrayOf("_id", "address", "body", "date")
            ctx.contentResolver.query(
                URI, projection, "_id > ?", arrayOf(lastId.toString()), "_id ASC"
            )?.use { c ->
                val idxId = c.getColumnIndex("_id")
                val idxAddr = c.getColumnIndex("address")
                val idxBody = c.getColumnIndex("body")
                val idxDate = c.getColumnIndex("date")
                var processed = 0
                while (c.moveToNext()) {
                    val id = if (idxId >= 0) c.getLong(idxId) else break
                    val sender = if (idxAddr >= 0) c.getString(idxAddr) ?: "" else ""
                    val body = if (idxBody >= 0) c.getString(idxBody) ?: "" else ""
                    val date = if (idxDate >= 0) c.getLong(idxDate) else System.currentTimeMillis()
                    rows++

                    if (body.isBlank()) { skippedBlank++; newId = id; continue }
                    if (Prefs.seenContains(ctx, sender, body, date)) { skippedSeen++; newId = id; continue }

                    found++
                    when (Uploader.uploadDirect(ctx, Sms(sender, body, date))) {
                        UploadResult.TRANSIENT_FAIL -> {
                            transient++
                            break // 瞬时失败：不前移游标(不越过这条)，下次重试
                        }
                        UploadResult.SUCCESS -> {
                            ok++; Prefs.seenAdd(ctx, sender, body, date); newId = id
                        }
                        UploadResult.PERMANENT_FAIL -> {
                            permFail++; Prefs.seenAdd(ctx, sender, body, date); newId = id
                        }
                    }
                    if (++processed >= BATCH) break
                }
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_ID, newId).apply()
            DebugLog.log(
                ctx,
                "轮询：读到 $rows 行｜空 $skippedBlank｜已见 $skippedSeen｜发 $found(成功$ok/永久$permFail/重试$transient)"
            )
        } catch (e: Exception) {
            DebugLog.log(ctx, "读短信库异常：${e.message}")
        }
    }

    /** 当前收件箱最大 _id；读库失败返回 null；空收件箱返回 0。 */
    fun maxInboxId(ctx: Context): Long? {
        return try {
            ctx.contentResolver.query(URI, arrayOf("_id"), null, null, "_id DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex("_id")
                    if (i >= 0) c.getLong(i) else 0L
                } else 0L
            }
        } catch (_: Exception) {
            null
        }
    }
}
