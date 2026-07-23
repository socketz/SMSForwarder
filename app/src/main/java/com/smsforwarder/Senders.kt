package com.smsforwarder

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单条短信 */
data class Sms(val sender: String, val body: String, val timestamp: Long)

/** 发送结果：ok=false 时 detail 为错误原因，交由 Worker 决定是否重试 */
data class SendResult(val ok: Boolean, val detail: String)

/** 一个转发渠道 */
interface Sender {
    fun send(ctx: Context, sms: Sms): SendResult
}

private const val TIMEOUT_MS = 15000

private fun isoTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))

private fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

/** HTTP webhook：POST 一段 JSON 到用户自建接口 */
class HttpSender : Sender {
    override fun send(ctx: Context, sms: Sms): SendResult {
        val p = Prefs.get(ctx)
        val url = p.getString(Prefs.KEY_HTTP_URL, "")?.trim().orEmpty()
        if (url.isBlank()) return SendResult(false, "HTTP URL 未配置")

        val payload = """{"sender":"${jsonEscape(sms.sender)}",""" +
            """"content":"${jsonEscape(sms.body)}",""" +
            """"timestamp":${sms.timestamp},""" +
            """"receivedAt":"${jsonEscape(isoTime(sms.timestamp))}"}"""

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // 可选自定义请求头，每行 "Key: Value"
                p.getString(Prefs.KEY_HTTP_HEADERS, "")?.lineSequence()?.forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        if (k.isNotEmpty()) setRequestProperty(k, v)
                    }
                }
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) SendResult(true, "HTTP $code")
            else SendResult(false, "HTTP 返回 $code")
        } catch (e: Exception) {
            SendResult(false, e.message ?: "网络异常")
        }
    }
}

/** Telegram Bot：调用 sendMessage */
class TelegramSender : Sender {
    override fun send(ctx: Context, sms: Sms): SendResult {
        val p = Prefs.get(ctx)
        val token = p.getString(Prefs.KEY_TG_TOKEN, "")?.trim().orEmpty()
        val chat = p.getString(Prefs.KEY_TG_CHAT, "")?.trim().orEmpty()
        val base = p.getString(Prefs.KEY_TG_API, Prefs.DEFAULT_TG_API)
            ?.trim()?.trimEnd('/')?.ifBlank { Prefs.DEFAULT_TG_API } ?: Prefs.DEFAULT_TG_API
        if (token.isBlank() || chat.isBlank()) return SendResult(false, "Telegram 未配置")

        val text = "📩 新短信\n发件人: ${sms.sender}\n时间: ${isoTime(sms.timestamp)}\n\n${sms.body}"
        val form = "chat_id=" + URLEncoder.encode(chat, "UTF-8") +
            "&text=" + URLEncoder.encode(text, "UTF-8") +
            "&disable_web_page_preview=true"

        return try {
            val conn = (URL("$base/bot$token/sendMessage").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) SendResult(true, "OK")
            else SendResult(false, "TG $code: ${resp.take(200)}")
        } catch (e: Exception) {
            SendResult(false, e.message ?: "网络异常")
        }
    }
}

/** 按渠道标识取对应发送器 */
fun senderFor(channel: String): Sender? = when (channel) {
    Prefs.CH_HTTP -> HttpSender()
    Prefs.CH_TG -> TelegramSender()
    else -> null
}
