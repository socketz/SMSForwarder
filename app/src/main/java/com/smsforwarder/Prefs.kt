package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置与去重游标的唯一数据源（沿用 NetPulse 的「SharedPreferences 即单一真相」思路）。
 * 瘦客户端设计：APK 只把短信 POST 到「你自己的服务器接口」，主接口优先、失败降级到备用接口；
 * 至于再转发到 Telegram / 邮件 / 其他渠道，全部由服务器端决定，APK 不关心。
 */
object Prefs {
    const val STORE = "smsforwarder"

    // 运行状态
    const val KEY_RUNNING = "running"

    // 转发接口：主 + 备（备用可空）；请求头两个接口共用
    const val KEY_PRIMARY_URL = "primary_url"
    const val KEY_BACKUP_URL = "backup_url"
    const val KEY_HTTP_HEADERS = "http_headers"   // 每行一个 "Key: Value"

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

    // 日志里标记这条是通过主接口还是备用接口投递成功的
    const val CH_PRIMARY = "primary"
    const val CH_BACKUP = "backup"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context) = get(ctx).getBoolean(KEY_RUNNING, false)

    fun primaryUrl(ctx: Context) = get(ctx).getString(KEY_PRIMARY_URL, "")?.trim().orEmpty()
    fun backupUrl(ctx: Context) = get(ctx).getString(KEY_BACKUP_URL, "")?.trim().orEmpty()
    fun headers(ctx: Context) = get(ctx).getString(KEY_HTTP_HEADERS, "").orEmpty()

    /** 至少配了主接口才算可转发 */
    fun isConfigured(ctx: Context) = primaryUrl(ctx).isNotBlank()

    fun reconcileEnabled(ctx: Context) = get(ctx).getBoolean(KEY_RECONCILE_ENABLED, true)
    fun reconcileMinutes(ctx: Context) = get(ctx).getInt(KEY_RECONCILE_MIN, 15).coerceAtLeast(15)

    // ---- 去重 ----
    // 用 发件人|正文哈希|分钟桶 作为键；广播用接收时刻、对账用短信库 date，二者通常在秒级内，
    // 检测时容忍前后各 1 分钟的桶偏移，既极少漏发也极少重发。
    private fun bucketKey(sender: String, body: String, bucket: Long): String =
        "$sender|${body.hashCode()}|$bucket"

    @Synchronized
    fun seenContains(ctx: Context, sender: String, body: String, tMs: Long): Boolean {
        val set = get(ctx).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val b = tMs / 60_000L
        return (b - 1..b + 1).any { set.contains(bucketKey(sender, body, it)) }
    }

    @Synchronized
    fun seenAdd(ctx: Context, sender: String, body: String, tMs: Long) {
        val p = get(ctx)
        val cur = LinkedHashSet(p.getStringSet(KEY_SEEN, emptySet()) ?: emptySet())
        cur.add(bucketKey(sender, body, tMs / 60_000L))
        // 有界：超出上限时丢弃最旧的
        while (cur.size > SEEN_MAX) {
            val it = cur.iterator(); it.next(); it.remove()
        }
        p.edit().putStringSet(KEY_SEEN, cur).apply()
    }
}
