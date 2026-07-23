package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置与去重游标的唯一数据源（沿用 NetPulse 的「SharedPreferences 即单一真相」思路）。
 * 服务、Worker、UI 都通过这里读写状态。
 */
object Prefs {
    const val STORE = "smsforwarder"

    // 运行状态
    const val KEY_RUNNING = "running"

    // HTTP 渠道
    const val KEY_HTTP_ENABLED = "http_enabled"
    const val KEY_HTTP_URL = "http_url"
    const val KEY_HTTP_HEADERS = "http_headers"   // 每行一个 "Key: Value"

    // Telegram 渠道
    const val KEY_TG_ENABLED = "tg_enabled"
    const val KEY_TG_TOKEN = "tg_token"
    const val KEY_TG_CHAT = "tg_chat_id"
    const val KEY_TG_API = "tg_api_base"          // 可选：反代地址，默认官方

    // 兜底对账
    const val KEY_RECONCILE_ENABLED = "reconcile_enabled"
    const val KEY_RECONCILE_MIN = "reconcile_min"
    const val KEY_LAST_SEEN_TS = "last_seen_ts"

    // 转发日志（JSON 数组字符串，最新在前）
    const val KEY_FORWARD_LOG = "forward_log"
    const val KEY_LOG_KEEP = "log_keep"

    // 去重集合（跨「广播」与「对账」两条路径共用）
    private const val KEY_SEEN = "seen_keys"
    private const val SEEN_MAX = 500

    const val CH_HTTP = "http"
    const val CH_TG = "telegram"

    const val DEFAULT_TG_API = "https://api.telegram.org"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context) = get(ctx).getBoolean(KEY_RUNNING, false)

    fun httpEnabled(ctx: Context) = get(ctx).getBoolean(KEY_HTTP_ENABLED, false)
    fun tgEnabled(ctx: Context) = get(ctx).getBoolean(KEY_TG_ENABLED, false)

    fun enabledChannels(ctx: Context): List<String> = buildList {
        val p = get(ctx)
        if (p.getBoolean(KEY_HTTP_ENABLED, false) &&
            !p.getString(KEY_HTTP_URL, "").isNullOrBlank()) add(CH_HTTP)
        if (p.getBoolean(KEY_TG_ENABLED, false) &&
            !p.getString(KEY_TG_TOKEN, "").isNullOrBlank() &&
            !p.getString(KEY_TG_CHAT, "").isNullOrBlank()) add(CH_TG)
    }

    fun reconcileEnabled(ctx: Context) = get(ctx).getBoolean(KEY_RECONCILE_ENABLED, true)
    fun reconcileMinutes(ctx: Context) = get(ctx).getInt(KEY_RECONCILE_MIN, 15).coerceAtLeast(15)

    // ---- 去重 ----
    // 用 发件人|正文哈希|分钟桶 作为键；广播用接收时刻、对账用短信库 date，二者通常在秒级内，
    // 检测时容忍前后各 1 分钟的桶偏移，既极少漏发也极少重发。
    private fun bucketKey(sender: String, body: String, tMs: Long, bucket: Long): String =
        "$sender|${body.hashCode()}|$bucket"

    @Synchronized
    fun seenContains(ctx: Context, sender: String, body: String, tMs: Long): Boolean {
        val set = get(ctx).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val b = tMs / 60_000L
        return (b - 1..b + 1).any { set.contains(bucketKey(sender, body, tMs, it)) }
    }

    @Synchronized
    fun seenAdd(ctx: Context, sender: String, body: String, tMs: Long) {
        val p = get(ctx)
        val cur = LinkedHashSet(p.getStringSet(KEY_SEEN, emptySet()) ?: emptySet())
        cur.add(bucketKey(sender, body, tMs, tMs / 60_000L))
        // 有界：超出上限时丢弃最旧的
        while (cur.size > SEEN_MAX) {
            val it = cur.iterator(); it.next(); it.remove()
        }
        p.edit().putStringSet(KEY_SEEN, cur).apply()
    }
}
