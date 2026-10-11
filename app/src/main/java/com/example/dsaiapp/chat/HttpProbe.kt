package com.example.dsaiapp.chat

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * App 内的 curl：发一个任意 HTTP 请求，把状态码、响应头和响应体原样拿回来。
 *
 * <p>用途：手机上直接验接口 —— 比如搜索 Key 对不对、TTS 令牌还能不能用、
 * DeepSeek 返回什么错，不用连着电脑看 logcat。
 */
object HttpProbe {

    private const val TAG = "DSAI-Curl"

    data class Result(
        val ok: Boolean,
        val status: Int,
        val headers: String,
        val body: String,
        val elapsedMs: Long,
        val error: String? = null,
    ) {
        /** 给界面显示的一段文本。 */
        fun render(): String {
            if (error != null) return "请求失败：$error"
            return buildString {
                append("HTTP ").append(status).append("   用时 ").append(elapsedMs).append(" ms\n")
                append("---- 响应头 ----\n").append(headers).append('\n')
                append("---- 响应体 ----\n").append(body)
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 发请求。**阻塞，必须在 IO 线程调用**。
     *
     * @param headersRaw 每行一个 `Key: Value`
     */
    fun request(
        method: String,
        url: String,
        headersRaw: String = "",
        body: String = "",
    ): Result {
        val started = System.currentTimeMillis()
        return try {
            val builder = Request.Builder().url(url.trim())

            headersRaw.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty()) builder.header(k, v)
                }
            }

            val m = method.trim().uppercase()
            val requestBody = if (m == "GET" || m == "HEAD") {
                null
            } else {
                body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            }
            builder.method(m, requestBody)

            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val hs = resp.headers.names().joinToString("\n") { "$it: ${resp.header(it)}" }
                Log.d(TAG, "$m $url -> ${resp.code}（${text.length} 字）")
                Result(
                    ok = resp.isSuccessful,
                    status = resp.code,
                    headers = hs,
                    body = text,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$method $url 失败", t)
            Result(
                ok = false,
                status = 0,
                headers = "",
                body = "",
                elapsedMs = System.currentTimeMillis() - started,
                error = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /** 预设：几种常用请求，省得手打。 */
    data class Preset(val label: String, val method: String, val url: String, val headers: String, val body: String)

    fun presets(
        deepseekKey: String,
        searchProvider: String,
        searchKey: String,
        ttsAppId: String,
        ttsToken: String,
    ): List<Preset> = listOf(
        Preset(
            label = "DeepSeek 对话",
            method = "POST",
            url = DeepSeekClient.ENDPOINT,
            headers = "Content-Type: application/json\nAuthorization: Bearer $deepseekKey",
            body = """{"model":"${DeepSeekClient.MODEL_CHAT}","stream":false,"messages":[{"role":"user","content":"你好"}]}""",
        ),
        Preset(
            label = if (searchProvider == SearchClient.PROVIDER_BOCHA) "博查搜索" else "Tavily 搜索",
            method = "POST",
            url = if (searchProvider == SearchClient.PROVIDER_BOCHA) {
                "https://api.bochaai.com/v1/web-search"
            } else {
                "https://api.tavily.com/search"
            },
            headers = if (searchProvider == SearchClient.PROVIDER_BOCHA) {
                "Content-Type: application/json\nAuthorization: Bearer $searchKey"
            } else {
                "Content-Type: application/json"
            },
            body = if (searchProvider == SearchClient.PROVIDER_BOCHA) {
                """{"query":"今天有什么新闻","count":3,"summary":true}"""
            } else {
                """{"api_key":"$searchKey","query":"today news","max_results":3}"""
            },
        ),
        Preset(
            label = "豆包 TTS 握手",
            method = "GET",
            url = "https://openspeech.bytedance.com/api/v3/tts/bidirection",
            headers = "Authorization: Bearer;$ttsToken\nX-Api-App-Key: $ttsAppId\n" +
                "X-Api-Access-Key: $ttsToken\nX-Api-Resource-Id: volc.megatts.default",
            body = "",
        ),
        Preset(
            label = "查本机出口 IP",
            method = "GET",
            url = "https://api.ipify.org?format=json",
            headers = "",
            body = "",
        ),
    )
}
