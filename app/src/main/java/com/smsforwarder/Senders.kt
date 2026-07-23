package com.smsforwarder

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单条短信 */
data class Sms(val sender: String, val body: String, val timestamp: Long)

/** 发送结果：ok=false 时 detail 为错误原因，交由 Worker 决定是否降级 / 重试 */
data class SendResult(val ok: Boolean, val detail: String)

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

/**
 * 把一条短信以 JSON POST 到指定接口。主接口和备用接口都走这里，只是 URL 不同。
 * 服务器收到后自行决定再转发到 Telegram / 邮件 / 其他渠道。
 */
object HttpForwarder {

    fun post(url: String, headersRaw: String, sms: Sms): SendResult {
        if (url.isBlank()) return SendResult(false, "URL 未配置")

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
                // 可选自定义请求头，每行 "Key: Value"（主备接口共用）
                headersRaw.lineSequence().forEach { line ->
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
