package com.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条本地上传记录（只记"是否成功上传到后端"；各渠道投递结果在服务端 admin 看） */
data class LogEntry(
    val time: Long,
    val sender: String,
    val ok: Boolean,
    val detail: String,
)

/** 上传日志：JSON 数组字符串存进 prefs，最新在前，按条数裁剪。 */
object ForwardLog {

    fun append(ctx: Context, e: LogEntry) {
        val p = Prefs.get(ctx)
        val keep = p.getInt(Prefs.KEY_LOG_KEEP, 200).coerceIn(20, 2000)
        val arr = try {
            JSONArray(p.getString(Prefs.KEY_UPLOAD_LOG, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val obj = JSONObject().apply {
            put("t", e.time); put("s", e.sender); put("ok", e.ok); put("d", e.detail)
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until minOf(arr.length(), keep - 1)) out.put(arr.get(i))
        p.edit().putString(Prefs.KEY_UPLOAD_LOG, out.toString()).apply()
    }

    fun all(ctx: Context): List<LogEntry> {
        val p = Prefs.get(ctx)
        val arr = try {
            JSONArray(p.getString(Prefs.KEY_UPLOAD_LOG, "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        val list = ArrayList<LogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(LogEntry(o.optLong("t"), o.optString("s"), o.optBoolean("ok"), o.optString("d")))
        }
        return list
    }

    fun clear(ctx: Context) {
        Prefs.get(ctx).edit().putString(Prefs.KEY_UPLOAD_LOG, "[]").apply()
    }
}
