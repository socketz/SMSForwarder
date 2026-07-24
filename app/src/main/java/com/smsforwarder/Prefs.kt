package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置与会话状态。App 现在是瘦客户端：登录到后端拿设备 token，抓到短信就上传，
 * 渠道配置全在服务端（见 ChannelsActivity 调用的后端 API）。
 */
object Prefs {
    const val STORE = "smsforwarder"

    // 后端地址：写死默认，保留一个隐藏覆盖入口（Settings 里）方便对本地/测试服
    const val DEFAULT_BASE_URL = "https://smsforwarder-api.shuttlelab.org"
    const val KEY_BASE_URL = "base_url"

    // 会话
    const val KEY_TOKEN = "device_token"
    const val KEY_USERNAME = "username"

    // 运行状态
    const val KEY_RUNNING = "running"

    // 可选：静音音频保活（默认关；插电常开机可开）
    const val KEY_KEEPALIVE_AUDIO = "keepalive_audio"

    // 兜底对账
    const val KEY_RECONCILE_ENABLED = "reconcile_enabled"
    const val KEY_RECONCILE_MIN = "reconcile_min"
    const val KEY_LAST_SEEN_TS = "last_seen_ts"

    // 前台服务轮询短信库的间隔（秒）+ 上次轮询时刻（供运行日志显示）
    const val KEY_POLL_SEC = "poll_sec"
    const val KEY_LAST_POLL_TS = "last_poll_ts"

    // 运行日志（诊断用，JSON 数组，最新在前，有上限）
    const val KEY_DEBUG_LOG = "debug_log"

    // 本地上传日志（只记"是否成功上传到后端"，各渠道投递结果在服务端 admin 看）
    const val KEY_UPLOAD_LOG = "upload_log"
    const val KEY_LOG_KEEP = "log_keep"

    // 去重集合（跨广播与对账两条路径共用）
    private const val KEY_SEEN = "seen_keys"
    private const val SEEN_MAX = 500

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String =
        get(ctx).getString(KEY_BASE_URL, "")?.trim()?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL

    fun token(ctx: Context): String = get(ctx).getString(KEY_TOKEN, "").orEmpty()
    fun username(ctx: Context): String = get(ctx).getString(KEY_USERNAME, "").orEmpty()
    fun isLoggedIn(ctx: Context): Boolean = token(ctx).isNotBlank()

    fun setSession(ctx: Context, token: String, username: String) {
        get(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_USERNAME, username).apply()
    }

    fun clearSession(ctx: Context) {
        get(ctx).edit().remove(KEY_TOKEN).remove(KEY_USERNAME).apply()
    }

    fun isRunning(ctx: Context) = get(ctx).getBoolean(KEY_RUNNING, false)

    fun pollSeconds(ctx: Context) = get(ctx).getInt(KEY_POLL_SEC, 5).coerceIn(3, 120)

    fun reconcileEnabled(ctx: Context) = get(ctx).getBoolean(KEY_RECONCILE_ENABLED, true)
    fun reconcileMinutes(ctx: Context) = get(ctx).getInt(KEY_RECONCILE_MIN, 15).coerceAtLeast(15)

    // ---- 去重 ----
    // 键 = 发件人|正文哈希|分钟桶；检测容忍前后各 1 分钟的桶偏移。此键也作为上传的
    // client_msg_id 交给后端做幂等去重。
    fun dedupKey(sender: String, body: String, tMs: Long): String =
        "$sender|${body.hashCode()}|${tMs / 60_000L}"

    @Synchronized
    fun seenContains(ctx: Context, sender: String, body: String, tMs: Long): Boolean {
        val set = get(ctx).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val b = tMs / 60_000L
        return (b - 1..b + 1).any { set.contains("$sender|${body.hashCode()}|$it") }
    }

    @Synchronized
    fun seenAdd(ctx: Context, sender: String, body: String, tMs: Long) {
        val p = get(ctx)
        val cur = LinkedHashSet(p.getStringSet(KEY_SEEN, emptySet()) ?: emptySet())
        cur.add(dedupKey(sender, body, tMs))
        while (cur.size > SEEN_MAX) {
            val it = cur.iterator(); it.next(); it.remove()
        }
        p.edit().putStringSet(KEY_SEEN, cur).apply()
    }
}
