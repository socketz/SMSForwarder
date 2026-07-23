package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 兜底对账：周期性扫描短信收件箱，把「广播漏掉」的短信补发。
 * 依赖去重集合避免与广播路径重复转发。无 READ_SMS 权限时安静跳过。
 */
class ReconcileWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.success()  // 没权限就不做，等用户去授权
        }
        if (Prefs.enabledChannels(ctx).isEmpty()) return@withContext Result.success()

        val p = Prefs.get(ctx)
        val lastSeen = p.getLong(Prefs.KEY_LAST_SEEN_TS, 0L)

        // 首次运行：只把游标初始化到「现在」，不回补历史短信（否则会把安装前的旧短信全量转发）。
        // 之后只补发游标之后、被实时广播漏掉的短信。
        if (lastSeen == 0L) {
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, System.currentTimeMillis()).apply()
            return@withContext Result.success()
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
                    WorkScheduler.enqueueForward(ctx, Sms(sender, body, date))
                }
            }
            p.edit().putLong(Prefs.KEY_LAST_SEEN_TS, maxDate).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
