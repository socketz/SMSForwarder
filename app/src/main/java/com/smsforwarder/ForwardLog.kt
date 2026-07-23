package com.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条转发结果记录 */
data class LogEntry(
    val time: Long,
    val sender: String,
    val channel: String,
    val ok: Boolean,
    val detail: String,   // 成功时可为空；失败时是错误信息
)

/**
 * 转发日志：以 JSON 数组字符串存进 prefs，最新在前，按可配置条数裁剪。
 * 直接沿用 NetPulse 的 LogStore 思路，避免引入数据库。
 */
object ForwardLog {

    fun append(ctx: Context, e: LogEntry) {
        val p = Prefs.get(ctx)
        val keep = p.getInt(Prefs.KEY_LOG_KEEP, 200).coerceIn(20, 2000)
        val arr = try {
            JSONArray(p.getString(Prefs.KEY_FORWARD_LOG, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val obj = JSONObject().apply {
            put("t", e.time)
            put("s", e.sender)
            put("c", e.channel)
            put("ok", e.ok)
            put("d", e.detail)
        }
        // 最新插到最前
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until minOf(arr.length(), keep - 1)) out.put(arr.get(i))
        p.edit().putString(Prefs.KEY_FORWARD_LOG, out.toString()).apply()
    }

    fun all(ctx: Context): List<LogEntry> {
        val p = Prefs.get(ctx)
        val arr = try {
            JSONArray(p.getString(Prefs.KEY_FORWARD_LOG, "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        val list = ArrayList<LogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                LogEntry(
                    time = o.optLong("t"),
                    sender = o.optString("s"),
                    channel = o.optString("c"),
                    ok = o.optBoolean("ok"),
                    detail = o.optString("d"),
                )
            )
        }
        return list
    }

    fun clear(ctx: Context) {
        Prefs.get(ctx).edit().putString(Prefs.KEY_FORWARD_LOG, "[]").apply()
    }
}
