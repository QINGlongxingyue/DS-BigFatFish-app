package com.example.dsaiapp.chat

import android.util.Log
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

    private const val TAG = "DSAI-DeepSeek"

    const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    const val MODEL_CHAT = "deepseek-chat"
    const val MODEL_REASONER = "deepseek-reasoner"
    const val MODEL_FLASH = "deepseek-flash"

    val MODELS = listOf(MODEL_CHAT, MODEL_REASONER, MODEL_FLASH)

    /** 一条对话消息：role = "system" / "user" / "assistant"。 */
    data class Msg(val role: String, val content: String)

    /** 暴露给模型的工具（function calling）。 */
    data class ToolSpec(
        val name: String,
        val description: String,
        /** JSON Schema 对象（type/properties/required）。 */
        val parameters: JSONObject,
    )

    /** 模型请求调用某个工具。 */
    data class ToolCall(val id: String, val name: String, val argumentsJson: String)

    /**
     * 带工具的「智能体」一轮对话：模型自己决定要不要调工具、调几次。
     *
     * <p>流程：请求 → 模型返回 tool_calls → 本地执行 → 结果以 role=tool 回填 →
     * 再请求……直到模型给出最终回答（或达到 [maxRounds] 上限）。
     *
     * @param toolRunner 执行工具，返回给模型看的文本结果
     */
    suspend fun chatWithTools(
        apiKey: String,
        history: List<Msg>,
        model: String,
        tools: List<ToolSpec>,
        maxRounds: Int = 3,
        toolRunner: suspend (ToolCall) -> String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // 可变的消息列表：工具调用与结果要追加进去
            val msgs = org.json.JSONArray()
            history.forEach {
                msgs.put(JSONObject().put("role", it.role).put("content", it.content))
            }

            val toolsJson = org.json.JSONArray().apply {
                tools.forEach { t ->
                    put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", t.name)
                                    .put("description", t.description)
                                    .put("parameters", t.parameters),
                            ),
                    )
                }
            }

            var lastText = ""
            for (round in 0 until maxRounds) {
                val body = JSONObject().apply {
                    put("model", model.ifBlank { MODEL_CHAT })
                    put("stream", false)
                    put("messages", msgs)
                    put("tools", toolsJson)
                    put("tool_choice", "auto")
                }.toString()

                val (code, text) = postJson(apiKey, body)
                if (code !in 200..299) {
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrDefault("HTTP $code")
                    // 模型/账号不支持工具调用：交给上层回退成普通对话
                    if (code == 400 && msg.contains("tool", ignoreCase = true)) {
                        throw UnsupportedOperationException("该模型不支持工具调用：$msg")
                    }
                    throw IOException("DeepSeek 接口返回 $code：$msg")
                }

                val message = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message")
                val toolCalls = message.optJSONArray("tool_calls")

                if (toolCalls == null || toolCalls.length() == 0) {
                    // 没有工具调用 = 最终回答
                    lastText = message.optString("content").trim().ifBlank {
                        message.optString("reasoning_content").trim()
                    }
                    return@runCatching lastText
                }

                // 把 assistant 的 tool_calls 原样回填（顺序和 id 必须一致）
                msgs.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", message.optString("content"))
                        .put("tool_calls", toolCalls),
                )

                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: continue
                    val id = call.optString("id")
                    val name = fn.optString("name")
                    val args = fn.optString("arguments").ifBlank { "{}" }
                    Log.d(TAG, "模型调用工具：$name($args)")

                    val resultText = try {
                        toolRunner(ToolCall(id, name, args))
                    } catch (t: Throwable) {
                        Log.e(TAG, "工具 $name 执行失败", t)
                        "工具执行失败：${t.message}"
                    }

                    msgs.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", resultText.take(6000)),
                    )
                }
            }
            // 轮次用完还没收尾：把最后一次的文本用上（或给个兜底）
            lastText.ifBlank { "（想查的资料太多，先说到这儿～）" }
        }
    }

    /** POST 一段 JSON，返回 (状态码, 响应体)。 */
    private fun postJson(apiKey: String, body: String): Pair<Int, String> {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

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
