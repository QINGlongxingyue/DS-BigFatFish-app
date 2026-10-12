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

    /**
     * 内置搜索：直接解析 cn.bing.com 的网页结果，**不需要任何 Key**。
     *
     * <p>模型只负责给查询词，怎么请求、怎么解析都由 App 干 —— 这也是默认档，
     * 让"联网搜索"开箱即用。缺点是解析网页，Bing 改版可能失效（那时换 Tavily/博查）。
     */
    const val PROVIDER_BING = "bing"

    /**
     * 百度 AI 搜索（千帆）：[文档](https://cloud.baidu.com/doc/qianfan-api/s/Wmbq4z7e5)
     * `POST https://qianfan.baidubce.com/v2/ai_search/web_search`，`Authorization: Bearer <API Key>`。
     * 申请门槛低、国内直连，中文检索是主场。
     */
    const val PROVIDER_BAIDU = "baidu"

    /** 一条搜索结果。 */
    data class Item(val title: String, val snippet: String, val url: String)

    /** 搜索配置。 */
    data class Config(
        val enabled: Boolean,
        val provider: String,
        val apiKey: String,
    ) {
        /** 内置搜索不需要 Key，所以只看开关。 */
        fun isReady(): Boolean = enabled &&
            (provider == PROVIDER_BING || apiKey.isNotBlank())

        fun providerName(): String = when (provider) {
            PROVIDER_BOCHA -> "博查"
            PROVIDER_BAIDU -> "百度"
            PROVIDER_BING -> "内置搜索"
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
            PROVIDER_BAIDU -> searchBaidu(query, config.apiKey, max)
            PROVIDER_BING -> searchBing(query, max)
            else -> searchTavily(query, config.apiKey, max)
        }
        Log.d(TAG, "搜索「$query」-> ${items.size} 条结果（${config.providerName()}）")
        Result.success(items)
    } catch (t: Throwable) {
        Log.e(TAG, "搜索失败", t)
        Result.failure(t)
    }

    // ------------------------------------------------------------------
    // 百度 AI 搜索（千帆）
    // ------------------------------------------------------------------
    private fun searchBaidu(query: String, key: String, max: Int): List<Item> {
        val body = JSONObject()
            .put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", query),
                ),
            )
            .put("search_source", "baidu_search_v2")
            .put(
                "resource_type_filter",
                org.json.JSONArray().put(
                    JSONObject().put("type", "web").put("top_k", max),
                ),
            )
            .toString()

        val req = Request.Builder()
            .url("https://qianfan.baidubce.com/v2/ai_search/web_search")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("百度 HTTP ${resp.code}：${text.take(160)}")
            val root = JSONObject(text)

            // 官方返回是 references[]；字段名做多重兜底，改版也不至于全废
            val refs = root.optJSONArray("references")
                ?: root.optJSONArray("search_results")
                ?: root.optJSONArray("items")
                ?: return emptyList()

            return (0 until refs.length()).mapNotNull { i ->
                val o = refs.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").ifBlank { o.optString("link") }
                val stamp = o.optString("date").trim()
                val text = o.optString("content")
                    .ifBlank { o.optString("snippet") }
                    .ifBlank { o.optString("summary") }
                    .ifBlank { o.optString("abstract") }
                    .replace('\n', ' ')
                    .trim()
                Item(
                    title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { url },
                    // 带上发布时间：模型好判断这条是不是最新的（百度返回里有 date 字段）
                    snippet = if (stamp.isNotEmpty()) "（$stamp）$text" else text,
                    url = url,
                )
            }.filter { it.url.isNotEmpty() || it.snippet.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // 内置搜索（免 Key）：GET cn.bing.com 的网页结果，正则抠出标题/链接/摘要
    // ------------------------------------------------------------------
    private fun searchBing(query: String, max: Int): List<Item> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://cn.bing.com/search?q=$encoded&setlang=zh-CN&ensearch=0"

        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()

        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Bing HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

        // 结果块形如：<li class="b_algo"> … <h2><a href="…">标题</a></h2> … <p>摘要</p> …</li>
        val blockRe = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
        val linkRe = Regex(
            "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val snippetRe = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

        val items = ArrayList<Item>()
        for (block in blockRe.findAll(html)) {
            val text = block.value
            val link = linkRe.find(text) ?: continue
            val href = link.groupValues[1]
            if (!href.startsWith("http")) continue
            items.add(
                Item(
                    title = stripTags(link.groupValues[2]),
                    snippet = snippetRe.find(text)?.groupValues?.get(1)?.let { stripTags(it) } ?: "",
                    url = href,
                ),
            )
            if (items.size >= max) break
        }
        if (items.isEmpty()) {
            // 没抠到结果多半是页面结构变了，别让上层以为是"查到了但没有内容"
            error("内置搜索没解析出结果（Bing 页面结构可能变了），可以改用 Tavily/博查")
        }
        return items
    }

    /** 去 HTML 标签与常见实体。 */
    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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
     * 给「智能体」用的工具定义：模型自己决定要不要搜。
     */
    fun toolSpec(): DeepSeekClient.ToolSpec = DeepSeekClient.ToolSpec(
        name = "web_search",
        description = "联网搜索，用来查最新的、你不确定的信息（新闻、天气、价格、赛事、" +
            "某个人的近况等）。需要时效性或事实性资料时主动调用它，不要凭记忆编造。",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "搜索关键词，尽量具体，例如「北京今天天气」"),
                ),
            )
            .put("required", org.json.JSONArray().put("query")),
    )

    /**
     * 执行模型发起的 web_search 调用，返回给模型看的文本。
     */
    fun runToolCall(argumentsJson: String, config: Config, max: Int = 5): String {
        val query = runCatching {
            val o = JSONObject(argumentsJson)
            o.optString("query").ifBlank { o.optString("q") }
        }.getOrDefault("").trim()

        if (query.isEmpty()) return "搜索失败：没有给出 query 参数。"
        if (!config.isReady()) {
            return "搜索不可用：用户还没在设置里配置搜索服务的 API Key，" +
                "请直接告诉用户去设置里填一下，不要编造结果。"
        }

        return search(query, config, max).fold(
            onSuccess = { formatForPrompt(query, it, config.providerName()) },
            onFailure = { "搜索失败：${it.message}。请如实告诉用户这次没查成。" },
        )
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
