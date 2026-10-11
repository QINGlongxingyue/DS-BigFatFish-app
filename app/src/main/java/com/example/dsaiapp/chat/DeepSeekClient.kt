package com.example.dsaiapp.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 官方 API 的最小客户端（OpenAI 兼容格式，零第三方依赖）。
 *
 * 端点 / 认证 / 模型名按官方文档 https://api-docs.deepseek.com/ ：
 *   POST https://api.deepseek.com/chat/completions
 *   Authorization: Bearer <apiKey>
 *   body: { "model": "deepseek-chat", "messages": [...], "stream": false }
 *   响应: choices[0].message.content
 *
 * 模型可在设置里选：deepseek-chat（标准）/ deepseek-reasoner（深度思考，更聪明但更慢）/
 * deepseek-flash（快，早期版本用的就是这个）。
 */
object DeepSeekClient {

    const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    const val MODEL_CHAT = "deepseek-chat"
    const val MODEL_REASONER = "deepseek-reasoner"
    const val MODEL_FLASH = "deepseek-flash"

    val MODELS = listOf(MODEL_CHAT, MODEL_REASONER, MODEL_FLASH)

    /** 一条对话消息：role = "system" / "user" / "assistant"。 */
    data class Msg(val role: String, val content: String)

    /**
     * 发一轮对话。网络出错 / 业务报错统一包在 Result.failure 里，
     * message 已经是能直接给人看的中文。
     */
    suspend fun chat(
        apiKey: String,
        history: List<Msg>,
        model: String = MODEL_CHAT,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("model", model.ifBlank { MODEL_CHAT })
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        history.forEach {
                            put(JSONObject().put("role", it.role).put("content", it.content))
                        }
                    })
                }.toString()

                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 120_000 // reasoner 要想一会儿，给足时间
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }

                try {
                    conn.outputStream.use { out ->
                        out.write(body.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

                    if (code !in 200..299) {
                        val msg = runCatching {
                            JSONObject(text).getJSONObject("error").getString("message")
                        }.getOrDefault("HTTP $code")
                        throw IOException("DeepSeek 接口返回 $code：$msg")
                    }

                    val message = JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")

                    // reasoner 的正文在 content，思考过程在 reasoning_content（不展示）
                    message.optString("content").trim().ifBlank {
                        message.optString("reasoning_content").trim()
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
}
