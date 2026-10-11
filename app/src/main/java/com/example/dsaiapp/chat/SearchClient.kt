package com.example.dsaiapp.chat

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 联网搜索。
 *
 * <p>DeepSeek 的对话接口自己不能上网，所以要么靠 function calling（对模型/服务端支持有要求），
 * 要么在本地判断"这句话需要查资料"→ 先搜 → 把结果塞进 system 再问模型。
 * 这里走的是后者：不依赖模型是否支持工具调用，普通 chat 接口就能用。
 *
 * <p>支持两家服务商，设置里选：
 * - `tavily`：Tavily Search（英文内容强，专为 LLM 设计）
 * - `bocha`：博查 AI 搜索（中文内容强，国内直连）
 */
object SearchClient {

    private const val TAG = "DSAI-Search"

    const val PROVIDER_TAVILY = "tavily"
    const val PROVIDER_BOCHA = "bocha"

    /** 一条搜索结果。 */
    data class Item(val title: String, val snippet: String, val url: String)

    /** 搜索配置。 */
    data class Config(
        val enabled: Boolean,
        val provider: String,
        val apiKey: String,
    ) {
        fun isReady(): Boolean = enabled && apiKey.isNotBlank()

        fun providerName(): String = when (provider) {
            PROVIDER_BOCHA -> "博查"
            else -> "Tavily"
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 执行一次搜索。**会阻塞，必须在 IO 线程调用**。
     *
     * @return 成功返回结果列表（可能为空），失败返回错误信息。
     */
    fun search(query: String, config: Config, max: Int = 5): Result<List<Item>> = try {
        val items = when (config.provider) {
            PROVIDER_BOCHA -> searchBocha(query, config.apiKey, max)
            else -> searchTavily(query, config.apiKey, max)
        }
        Log.d(TAG, "搜索「$query」-> ${items.size} 条结果（${config.providerName()}）")
        Result.success(items)
    } catch (t: Throwable) {
        Log.e(TAG, "搜索失败", t)
        Result.failure(t)
    }

    // ------------------------------------------------------------------
    // Tavily: POST https://api.tavily.com/search
    // ------------------------------------------------------------------
    private fun searchTavily(query: String, key: String, max: Int): List<Item> {
        val body = JSONObject()
            .put("api_key", key)
            .put("query", query)
            .put("max_results", max)
            .put("search_depth", "basic")
            .put("include_answer", false)
            .toString()

        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Tavily HTTP ${resp.code}：${text.take(160)}")
            val arr = JSONObject(text).optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    title = o.optString("title").ifBlank { o.optString("url") },
                    snippet = o.optString("content").replace('\n', ' ').trim(),
                    url = o.optString("url"),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 博查: POST https://api.bochaai.com/v1/web-search
    // ------------------------------------------------------------------
    private fun searchBocha(query: String, key: String, max: Int): List<Item> {
        val body = JSONObject()
            .put("query", query)
            .put("count", max)
            .put("summary", true)
            .toString()

        val req = Request.Builder()
            .url("https://api.bochaai.com/v1/web-search")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("博查 HTTP ${resp.code}：${text.take(160)}")
            val root = JSONObject(text)
            val pages = root.optJSONObject("data")
                ?.optJSONObject("webPages")
                ?.optJSONArray("value")
                ?: return emptyList()
            return (0 until pages.length()).mapNotNull { i ->
                val o = pages.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    title = o.optString("name").ifBlank { o.optString("url") },
                    snippet = o.optString("summary").ifBlank { o.optString("snippet") }
                        .replace('\n', ' ').trim(),
                    url = o.optString("url"),
                )
            }
        }
    }

    /**
     * 把搜索结果整理成给模型看的资料块。
     */
    fun formatForPrompt(query: String, items: List<Item>, providerName: String): String {
        if (items.isEmpty()) {
            return "# 联网搜索结果\n刚刚用「$providerName」查了「$query」，没有返回可用结果。" +
                "请告诉用户没查到，不要凭空编造。"
        }
        val sb = StringBuilder()
        sb.append("# 联网搜索结果\n")
        sb.append("刚刚用「$providerName」查了「").append(query).append("」，资料如下：\n")
        items.forEachIndexed { i, it ->
            sb.append(i + 1).append(". ").append(it.title).append('\n')
            if (it.snippet.isNotEmpty()) sb.append("   ").append(it.snippet.take(500)).append('\n')
            if (it.url.isNotEmpty()) sb.append("   来源：").append(it.url).append('\n')
        }
        sb.append("\n请依据以上资料回答用户，可以用【1】【2】这样的编号标注来源；")
        sb.append("资料里没有的信息就说没查到，不要自己编。")
        return sb.toString()
    }

    /**
     * 本地判断"这句话是不是需要联网查"。
     *
     * <p>保守一点：只有出现明显的时效性/事实性词汇才触发，避免每句话都去搜
     * （既慢又费额度）。用户也可以手动开一次搜索。
     */
    fun needsSearch(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        return SEARCH_KEYWORDS.any { t.contains(it) }
    }

    private val SEARCH_KEYWORDS = listOf(
        "搜索", "搜一下", "搜下", "查一下", "查查", "帮我查", "百度", "谷歌",
        "最新", "近日", "最近", "现在", "今天", "昨天", "明天", "新闻", "时事",
        "天气", "气温", "股价", "汇率", "价格", "多少钱", "报价", "发售", "发布",
        "什么时候", "哪一年", "谁是", "现任", "目前", "截止", "版本", "更新日志",
        "2025", "2026", "赛程", "比分", "结果如何",
    )
}
