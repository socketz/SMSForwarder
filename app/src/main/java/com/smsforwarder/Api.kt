package com.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一条短信（timestamp 为毫秒） */
data class Sms(val sender: String, val body: String, val timestamp: Long)

/** API 调用结果 */
sealed class ApiResp {
    data class Ok(val body: JSONObject) : ApiResp()
    data class Err(val code: Int, val message: String) : ApiResp()

    val isOk get() = this is Ok
}

/**
 * 后端 API 客户端。全部用 HttpURLConnection + org.json，无第三方库。
 * 所有方法都会阻塞网络，必须在后台线程/Worker 中调用。
 */
object Api {
    private const val TIMEOUT_MS = 20000

    // ---- 鉴权 ----

    fun register(ctx: Context, username: String, password: String): ApiResp =
        request(ctx, "POST", "/api/register", JSONObject().put("username", username).put("password", password), auth = false)

    /** 成功时返回体含 device_token；调用方负责存储会话 */
    fun login(ctx: Context, username: String, password: String, deviceLabel: String): ApiResp =
        request(ctx, "POST", "/api/login",
            JSONObject().put("username", username).put("password", password).put("device_label", deviceLabel),
            auth = false)

    /** token 传入时用它鉴权（退出登录场景：本地会话可能已清空，用捕获的旧 token 吊销） */
    fun logout(ctx: Context, token: String? = null): ApiResp =
        request(ctx, "POST", "/api/logout", null, auth = true, authToken = token)

    // ---- 渠道 ----

    fun listChannels(ctx: Context): ApiResp =
        request(ctx, "GET", "/api/channels", null, auth = true)

    fun createChannel(ctx: Context, type: String, config: JSONObject, enabled: Boolean): ApiResp =
        request(ctx, "POST", "/api/channels",
            JSONObject().put("type", type).put("config", config).put("enabled", enabled), auth = true)

    fun updateChannel(ctx: Context, id: Long, enabled: Boolean?, config: JSONObject?): ApiResp {
        val body = JSONObject()
        if (enabled != null) body.put("enabled", enabled)
        if (config != null) body.put("config", config)
        return request(ctx, "PATCH", "/api/channels/$id", body, auth = true)
    }

    fun deleteChannel(ctx: Context, id: Long): ApiResp =
        request(ctx, "DELETE", "/api/channels/$id", null, auth = true)

    fun testChannel(ctx: Context, id: Long): ApiResp =
        request(ctx, "POST", "/api/channels/$id/test", null, auth = true)

    fun bindCode(ctx: Context, id: Long): ApiResp =
        request(ctx, "POST", "/api/channels/$id/bind-code", null, auth = true)

    // ---- 短信上传 ----

    fun uploadSms(ctx: Context, sms: Sms, clientMsgId: String): ApiResp =
        request(ctx, "POST", "/api/sms",
            JSONObject()
                .put("sender", sms.sender)
                .put("content", sms.body)
                .put("timestamp", sms.timestamp)
                .put("client_msg_id", clientMsgId),
            auth = true)

    // ---- 核心请求 ----

    private fun request(ctx: Context, method: String, path: String, body: JSONObject?, auth: Boolean, authToken: String? = null): ApiResp {
        val url = Prefs.baseUrl(ctx).trimEnd('/') + path
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = method
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (auth) setRequestProperty("Authorization", "Bearer " + (authToken ?: Prefs.token(ctx)))
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code in 200..299) {
                ApiResp.Ok(if (text.isBlank()) JSONObject() else JSONObject(text))
            } else {
                ApiResp.Err(code, extractError(text) ?: "HTTP $code")
            }
        } catch (e: Exception) {
            // 网络层失败：code = -1，交由调用方决定重试
            ApiResp.Err(-1, e.message ?: "network error")
        }
    }

    private fun extractError(text: String): String? = try {
        JSONObject(text).optString("error").ifBlank { null }
    } catch (_: Exception) {
        text.ifBlank { null }
    }

    // 便捷解析
    fun channelsArray(body: JSONObject): JSONArray = body.optJSONArray("channels") ?: JSONArray()
}
