package com.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行日志（诊断用）：记录轮询/抓取/上传等内部动作，最新在前，**上限 300 条**,
 * 满了自动丢最旧的，绝不会撑爆存储。与用户可见的「最近上传」(ForwardLog) 分开。
 */
object DebugLog {
    private const val KEEP = 300

    fun log(ctx: Context, msg: String) {
        val p = Prefs.get(ctx)
        val arr = try {
            JSONArray(p.getString(Prefs.KEY_DEBUG_LOG, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val out = JSONArray()
        out.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg))
        for (i in 0 until minOf(arr.length(), KEEP - 1)) out.put(arr.get(i))
        p.edit().putString(Prefs.KEY_DEBUG_LOG, out.toString()).apply()
    }

    fun all(ctx: Context): List<Pair<Long, String>> {
        val arr = try {
            JSONArray(Prefs.get(ctx).getString(Prefs.KEY_DEBUG_LOG, "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        val list = ArrayList<Pair<Long, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(o.optLong("t") to o.optString("m"))
        }
        return list
    }

    fun clear(ctx: Context) {
        Prefs.get(ctx).edit().putString(Prefs.KEY_DEBUG_LOG, "[]").apply()
    }
}
