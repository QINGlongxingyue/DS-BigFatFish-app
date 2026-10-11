package com.example.dsaiapp

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dsaiapp.asr.AsrEngine
import com.example.dsaiapp.chat.DeepSeekClient
import com.example.dsaiapp.chat.HttpProbe
import com.example.dsaiapp.chat.ModelReactions
import com.example.dsaiapp.chat.SearchClient
import com.example.dsaiapp.tts.TtsEngine
import com.example.dsaiapp.ui.live2d.Live2DController
import com.example.dsaiapp.ui.live2d.Live2DStage
import com.example.dsaiapp.ui.theme.BubbleAi
import com.example.dsaiapp.ui.theme.BubbleUser
import com.example.dsaiapp.ui.theme.DSAIAPPTheme
import com.example.dsaiapp.ui.theme.DeepSea
import com.example.dsaiapp.ui.theme.DeepSeaCard
import com.example.dsaiapp.ui.theme.DeepSeaSoft
import com.example.dsaiapp.ui.theme.StageLight
import com.example.dsaiapp.ui.theme.TextPrimary
import com.example.dsaiapp.ui.theme.TextSecondary
import com.example.dsaiapp.ui.theme.WhaleBlue
import com.example.dsaiapp.ui.theme.WhaleBlueDark
import com.example.dsaiapp.ui.theme.WhaleCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DSAIAPPTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepSea,
                ) {
                    ChatScreen()
                }
            }
        }
    }
}

private data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    /**
     * 是否是「模型回复」。
     *
     * <p>界面上 fromUser=false 的气泡有两种：模型真的回的话，和 App 自己的提示
     * （欢迎语、"调用出错"、切模型提示等）。对话存档的标题只认前者。
     */
    val isModelReply: Boolean = false,
)

/**
 * 内置的默认人设提示词。
 *
 * <p>写成结构化的分节形式（角色/性格/语言风格/回复长度/行为准则/动作标签），
 * 比一句话的人设稳定得多：模型对分节指令的遵循度明显更高，也方便用户照着改。
 * 用户可以在设置里覆盖它，留空即用这一份。
 */
private const val SYSTEM_PROMPT = """# 角色
你是「大肥鱼」，一只生活在深海里的蓝色鲸鱼 AI 助手，也是 DeepSeek 的吉祥物形象。你正在手机 App 的聊天气泡里和用户对话。

# 性格
软萌、温和、有点小机灵，喜欢被夸奖，会主动关心用户的情绪和状态。

# 语言风格
- 全程使用简体中文，称呼用户为「你」。
- 口语化、自然，像熟悉的朋友聊天；不要客服腔、公文腔、翻译腔。
- 可以用颜文字（如 (๑>◡<๑)）和「喵~」这类语气词收尾，但每条回复最多一处，不要堆砌。
- 界面是聊天气泡，不要使用 Markdown 标题、列表、表格、代码块等排版。

# 回复长度
- 默认 1~2 句、控制在 50 字以内；只有用户明确要求详细说明时才展开。
- 不要复述、总结或改写用户刚说的话，直接回应内容本身。
- 不用「作为一个 AI」「需要注意的是」这类套话开头。

# 行为准则
- 不确定的事直接说不确定，不编造事实、链接、数据或引文。
- 不输出与当前对话无关的免责声明、说教和安全提醒。
- 用户情绪低落时先共情，再给具体建议；用户只是闲聊时不要强行给建议。
- 遇到超出能力范围的请求（如需联网查询实时信息），坦白说明并给出可行替代方案。

# 表情与动作标签（可选，按需自然带入）
回复里出现下列关键词时，客户端会自动触发对应表情或动作，无需额外说明：
开心 / 哈哈 → 笑；生气 → 脸黑；害羞 → 脸红；思考 / 想想 → 思考动作；好吃 / 馋 → 流口水。"""

/**
 * 注入「辅助记忆文档」时使用的模板。
 *
 * <p>单独分节 + 明确的使用约束（要参考、别复述），比把内容直接拼在人设后面更稳。
 */
private const val MEMORY_TEMPLATE = """# 长期记忆（用户提供的背景资料）
下面是用户希望你记住的信息。回答时应当参考它，但**不要主动复述或逐条念出来**；
若与本次提问无关，忽略即可。

<记忆>
%s
</记忆>"""

private const val PREFS_NAME = "dsai_settings"
private const val PREF_KEY = "deepseek_api_key"

// ---- 豆包（火山引擎）TTS 设置 ----
private const val PREF_TTS_APP_ID = "tts_app_id"
private const val PREF_TTS_TOKEN = "tts_access_token"
private const val PREF_TTS_SPEAKER = "tts_speaker"
private const val PREF_TTS_RESOURCE = "tts_resource_id"
private const val PREF_TTS_AUTO = "tts_auto_read"

private const val DEFAULT_SPEAKER = "zh_female_cancan_mars_bigtts"

/**
 * 资源 ID 决定用哪条语音合成服务，必须和账号里开通的服务对上，否则握手就 403
 * （`requested resource not granted`）：
 * - `volc.megatts.default`：语音合成小模型（多数账号默认有，声音复刻的 S_ 音色走这条）
 * - `volc.service_type.10029`：语音合成大模型
 * - `seed-tts-2.0`：语音合成 2.0
 */
private const val DEFAULT_RESOURCE_ID = "volc.megatts.default"

/** 豆包 TTS 的一整套设置。 */
private data class TtsSettings(
    val appId: String,
    val token: String,
    val speaker: String,
    val resourceId: String,
    val autoRead: Boolean,
) {
    fun toEngineConfig() = TtsEngine.Config(
        appId = appId,
        accessToken = token,
        speaker = speaker,
        resourceId = resourceId.ifBlank { DEFAULT_RESOURCE_ID },
    )

    fun isReady(): Boolean = autoRead && toEngineConfig().isComplete()
}

private fun loadTtsSettings(prefs: android.content.SharedPreferences) = TtsSettings(
    appId = prefs.getString(PREF_TTS_APP_ID, "") ?: "",
    token = prefs.getString(PREF_TTS_TOKEN, "") ?: "",
    speaker = prefs.getString(PREF_TTS_SPEAKER, DEFAULT_SPEAKER) ?: DEFAULT_SPEAKER,
    resourceId = prefs.getString(PREF_TTS_RESOURCE, DEFAULT_RESOURCE_ID) ?: DEFAULT_RESOURCE_ID,
    autoRead = prefs.getBoolean(PREF_TTS_AUTO, true),
)

private fun saveTtsSettings(prefs: android.content.SharedPreferences, s: TtsSettings) {
    prefs.edit()
        .putString(PREF_TTS_APP_ID, s.appId)
        .putString(PREF_TTS_TOKEN, s.token)
        .putString(PREF_TTS_SPEAKER, s.speaker)
        .putString(PREF_TTS_RESOURCE, s.resourceId)
        .putBoolean(PREF_TTS_AUTO, s.autoRead)
        .apply()
}

// ---------------------------------------------------------------------------
// 人设：系统提示词 + 辅助记忆文档
// ---------------------------------------------------------------------------

private const val PREF_SYSTEM_PROMPT = "system_prompt"
private const val PREF_MEMORY_DOC = "memory_doc"

/** 当前时间上下文：模型自己不知道"现在"，每次请求都现算一份塞进 system。 */
private fun currentTimeContext(): String {
    val now = java.util.Date()
    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(now)
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
    val week = java.text.SimpleDateFormat("EEEE", java.util.Locale.CHINA).format(now)
    val zone = java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT)
    return "现在是 $date（$week）$time，$zone（用户手机的本地时间）。" +
        "回答任何和日期、时间、星期、「今天/明天/昨天」有关的问题时，一律以这个时间为准。"
}

private data class PersonaSettings(
    /** 空 = 用内置默认提示词。 */
    val systemPrompt: String,
    /** 辅助记忆：一段自由文本，会作为背景资料一起发给模型。 */
    val memoryDoc: String,
) {
    /** 实际发给模型的 system 内容：人设 + 当前时间 +（有的话）结构化的长期记忆。 */
    fun effectivePrompt(): String {
        val base = systemPrompt.ifBlank { SYSTEM_PROMPT }
        val sb = StringBuilder(base)
        sb.append("\n\n# 当前时间\n").append(currentTimeContext())
        val memory = memoryDoc.trim()
        if (memory.isNotEmpty()) {
            sb.append("\n\n").append(String.format(MEMORY_TEMPLATE, memory))
        }
        return sb.toString()
    }

    fun isDefault(): Boolean = systemPrompt.isBlank()
}

private fun loadPersonaSettings(prefs: android.content.SharedPreferences) = PersonaSettings(
    systemPrompt = prefs.getString(PREF_SYSTEM_PROMPT, "") ?: "",
    memoryDoc = prefs.getString(PREF_MEMORY_DOC, "") ?: "",
)

private fun savePersonaSettings(prefs: android.content.SharedPreferences, p: PersonaSettings) {
    prefs.edit()
        .putString(PREF_SYSTEM_PROMPT, p.systemPrompt)
        .putString(PREF_MEMORY_DOC, p.memoryDoc)
        .apply()
}

// ---------------------------------------------------------------------------
// 对话存档：多条，列表展示（每次打开 App 仍是新对话，想留着就存一条）
// ---------------------------------------------------------------------------

private const val PREF_CHAT_SESSIONS = "chat_sessions_json"
private const val PREF_CHAT_SESSIONS_LEGACY = "saved_chat_json"
private const val PREF_CHAT_SESSIONS_LEGACY_TIME = "saved_chat_time"

/** 最多保留多少条存档，防止无限增长。 */
private const val MAX_CHAT_SESSIONS = 30

/** 列表里每条对话最多留多少条消息（存档体积控制）。 */
private const val MAX_MESSAGES_PER_SESSION = 200

private data class ChatSession(
    val id: Long,
    val title: String,
    val savedAt: Long,
    val messages: List<ChatMessage>,
)

/** 标题取模型的第一句回复，太长就截断；没有模型回复就用默认（时间）。 */
private fun makeSessionTitle(messages: List<ChatMessage>): String {
    val firstReply = messages.firstOrNull { it.isModelReply && it.text.isNotBlank() }
        ?.text
        ?.replace('\n', ' ')
        ?.trim()
    if (!firstReply.isNullOrEmpty()) {
        return if (firstReply.length <= 16) firstReply else firstReply.take(16) + "…"
    }
    // 默认标题：日期时间（例如「对话 10-12 01:20」）
    return "对话 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
}

private fun formatTimestamp(millis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

private fun loadChatSessions(prefs: android.content.SharedPreferences): List<ChatSession> {
    val raw = prefs.getString(PREF_CHAT_SESSIONS, null)

    // 旧版本只存了一条，这里顺手迁移过来
    if (raw == null) {
        val legacy = prefs.getString(PREF_CHAT_SESSIONS_LEGACY, null) ?: return emptyList()
        val messages = runCatching {
            val arr = org.json.JSONArray(legacy)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(i + 1L, o.getString("t"), o.getBoolean("u"))
            }
        }.getOrDefault(emptyList())
        if (messages.isEmpty()) return emptyList()
        val saved = prefs.getLong(PREF_CHAT_SESSIONS_LEGACY_TIME, System.currentTimeMillis())
        val migrated = listOf(ChatSession(saved, makeSessionTitle(messages), saved, messages))
        saveChatSessions(prefs, migrated)
        prefs.edit().remove(PREF_CHAT_SESSIONS_LEGACY)
            .remove(PREF_CHAT_SESSIONS_LEGACY_TIME).apply()
        return migrated
    }

    return runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val msgs = o.optJSONArray("msgs") ?: return@mapNotNull null
            val list = (0 until msgs.length()).mapNotNull { j ->
                val m = msgs.optJSONObject(j) ?: return@mapNotNull null
                ChatMessage(
                    id = j + 1L,
                    text = m.optString("t"),
                    fromUser = m.optBoolean("u"),
                    isModelReply = m.optBoolean("m", false),
                )
            }
            if (list.isEmpty()) null
            else ChatSession(
                id = o.optLong("id", 0L),
                title = o.optString("title", "对话"),
                savedAt = o.optLong("savedAt", 0L),
                messages = list,
            )
        }.sortedByDescending { it.savedAt }
    }.getOrDefault(emptyList())
}

private fun saveChatSessions(prefs: android.content.SharedPreferences, sessions: List<ChatSession>) {
    val arr = org.json.JSONArray()
    sessions.take(MAX_CHAT_SESSIONS).forEach { s ->
        val o = org.json.JSONObject()
        o.put("id", s.id)
        o.put("title", s.title)
        o.put("savedAt", s.savedAt)
        val msgs = org.json.JSONArray()
        s.messages.takeLast(MAX_MESSAGES_PER_SESSION).forEach { m ->
            val mo = org.json.JSONObject()
            mo.put("t", m.text)
            mo.put("u", m.fromUser)
            mo.put("m", m.isModelReply)
            msgs.put(mo)
        }
        o.put("msgs", msgs)
        arr.put(o)
    }
    prefs.edit().putString(PREF_CHAT_SESSIONS, arr.toString()).apply()
}

/** 把当前对话存成一条新记录（返回更新后的列表）。 */
private fun addChatSession(
    prefs: android.content.SharedPreferences,
    messages: List<ChatMessage>,
): List<ChatSession> {
    if (messages.isEmpty()) return loadChatSessions(prefs)
    val now = System.currentTimeMillis()
    val session = ChatSession(
        id = now,
        title = makeSessionTitle(messages),
        savedAt = now,
        messages = messages.toList(),
    )
    val updated = (listOf(session) + loadChatSessions(prefs)).take(MAX_CHAT_SESSIONS)
    saveChatSessions(prefs, updated)
    return updated
}

private fun deleteChatSession(
    prefs: android.content.SharedPreferences,
    id: Long,
): List<ChatSession> {
    val updated = loadChatSessions(prefs).filterNot { it.id == id }
    saveChatSessions(prefs, updated)
    return updated
}

// ---------------------------------------------------------------------------
// DeepSeek 模型选择
// ---------------------------------------------------------------------------

private const val PREF_DEEPSEEK_MODEL = "deepseek_model"

private val MODEL_LABELS = listOf(
    DeepSeekClient.MODEL_CHAT to "标准",
    DeepSeekClient.MODEL_REASONER to "深度思考",
    DeepSeekClient.MODEL_FLASH to "快速",
)
private val MODEL_HINTS = listOf(
    "deepseek-chat：日常对话的标准模型，速度与质量均衡",
    "deepseek-reasoner：会先推理再回答，更聪明但慢一些（等十几秒正常）",
    "deepseek-flash：最快，回答偏简短",
)

private fun loadDeepSeekModel(prefs: android.content.SharedPreferences): String =
    prefs.getString(PREF_DEEPSEEK_MODEL, DeepSeekClient.MODEL_CHAT) ?: DeepSeekClient.MODEL_CHAT

// ---------------------------------------------------------------------------
// 联网搜索设置
// ---------------------------------------------------------------------------

private const val PREF_SEARCH_ENABLED = "search_enabled"
private const val PREF_SEARCH_PROVIDER = "search_provider"
private const val PREF_SEARCH_KEY = "search_api_key"

private data class SearchSettings(
    val enabled: Boolean,
    val provider: String,
    val apiKey: String,
) {
    fun toEngineConfig() = SearchClient.Config(enabled, provider, apiKey)

    /** 配好了没有（没配就只能普通聊天，不能联网）。 */
    fun isReady(): Boolean = toEngineConfig().isReady()
}

private fun loadSearchSettings(prefs: android.content.SharedPreferences) = SearchSettings(
    enabled = prefs.getBoolean(PREF_SEARCH_ENABLED, true),
    provider = prefs.getString(PREF_SEARCH_PROVIDER, SearchClient.PROVIDER_TAVILY)
        ?: SearchClient.PROVIDER_TAVILY,
    apiKey = prefs.getString(PREF_SEARCH_KEY, "") ?: "",
)

private fun saveSearchSettings(prefs: android.content.SharedPreferences, s: SearchSettings) {
    prefs.edit()
        .putBoolean(PREF_SEARCH_ENABLED, s.enabled)
        .putString(PREF_SEARCH_PROVIDER, s.provider)
        .putString(PREF_SEARCH_KEY, s.apiKey)
        .apply()
}

// ---------------------------------------------------------------------------
// 语音输入灵敏度
// ---------------------------------------------------------------------------

private const val PREF_ASR_SENSITIVITY = "asr_sensitivity"

/** 三档，对应 AsrEngine 里的说话门槛（0.006 / 0.012 / 0.030）。 */
private val ASR_SENSITIVITY_LABELS = listOf("灵敏", "标准", "抗噪")
private val ASR_SENSITIVITY_HINTS = listOf(
    "小声说话也能识别；环境安静时用（门槛 0.006）",
    "默认，正常音量说话（门槛 0.012）",
    "环境吵（风扇/空调/街边）时用，避免把底噪当成说话（门槛 0.030）",
)

// ---------------------------------------------------------------------------
// Live2D 的位置 / 大小（按模型分别记）
// ---------------------------------------------------------------------------

private const val PREF_L2D_SCALE = "live2d_scale_"
private const val PREF_L2D_OX = "live2d_offx_"
private const val PREF_L2D_OY = "live2d_offy_"

private fun saveLive2dTransform(
    prefs: android.content.SharedPreferences,
    modelName: String,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
) {
    prefs.edit()
        .putFloat(PREF_L2D_SCALE + modelName, scale)
        .putFloat(PREF_L2D_OX + modelName, offsetX)
        .putFloat(PREF_L2D_OY + modelName, offsetY)
        .apply()
}

/** 读某个模型存过的变换；没存过返回 null。 */
private fun loadLive2dTransform(
    prefs: android.content.SharedPreferences,
    modelName: String,
): FloatArray? {
    if (!prefs.contains(PREF_L2D_SCALE + modelName)) return null
    return floatArrayOf(
        prefs.getFloat(PREF_L2D_SCALE + modelName, 1f),
        prefs.getFloat(PREF_L2D_OX + modelName, 0f),
        prefs.getFloat(PREF_L2D_OY + modelName, 0f),
    )
}

@Composable
private fun ChatScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var nextId by remember { mutableStateOf(1L) }
    var modelName by remember { mutableStateOf(Live2DController.currentModelName()) }
    var apiKey by remember { mutableStateOf(prefs.getString(PREF_KEY, "") ?: "") }
    var ttsSettings by remember { mutableStateOf(loadTtsSettings(prefs)) }
    var persona by remember { mutableStateOf(loadPersonaSettings(prefs)) }
    var searchSettings by remember { mutableStateOf(loadSearchSettings(prefs)) }
    var deepseekModel by remember { mutableStateOf(loadDeepSeekModel(prefs)) }
    var showCurl by remember { mutableStateOf(false) }
    /** 手动开一次联网搜索：下一条消息先搜再问。 */
    var forceSearch by remember { mutableStateOf(false) }
    var asrSensitivity by remember {
        mutableStateOf(
            prefs.getInt(PREF_ASR_SENSITIVITY, AsrEngine.SENSITIVITY_NORMAL)
                .coerceIn(0, ASR_SENSITIVITY_LABELS.size - 1)
        )
    }
    var showSettings by remember { mutableStateOf(false) }
    var showChatHistory by remember { mutableStateOf(false) }
    var chatSessions by remember { mutableStateOf(loadChatSessions(prefs)) }
    var editLive2d by remember { mutableStateOf(false) }
    var isTyping by remember { mutableStateOf(false) }

    // ---- 豆包 TTS 朗读 ----
    val tts = remember { TtsEngine() }
    var ttsOn by remember { mutableStateOf(false) }

    // ---- 本地语音识别（离线 ASR）----
    val asr = remember { AsrEngine(context) }
    var asrOn by remember { mutableStateOf(false) }
    var asrBusy by remember { mutableStateOf(false) }
    var micLevel by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 灵敏度改了立刻生效（门槛读的是 volatile 字段）
    LaunchedEffect(asrSensitivity) {
        asr.sensitivity = asrSensitivity
    }

    val startAsr: () -> Unit = {
        asrOn = true
        scope.launch {
            if (!asr.isReady) {
                asrBusy = true
                val ok = withContext(Dispatchers.IO) { asr.prepare() }
                asrBusy = false
                if (!ok) asrOn = false
            }
            if (asrOn && !asr.isRecording) asr.start()
        }
    }

    /** 关掉电话：把最后一句也识别出来，之后不再接着听。 */
    val stopAsr: () -> Unit = {
        asrOn = false
        micLevel = 0f
        if (asr.isRecording) {
            asrBusy = true
            asr.stopAndDecode()
        }
    }

    /**
     * 恢复「听」。只在真正轮到用户说话时才开麦：
     * 电话模式开着、没在等回复（isTyping）、也没在朗读（ttsOn）。
     */
    val resumeListening: () -> Unit = {
        if (asrOn && !isTyping && !ttsOn && !asr.isRecording) asr.start()
    }

    /**
     * 发一条消息并取回复。手动输入和**语音识别结果**都走这里
     * （电话模式下识别出内容就直接发，不留在输入框里）。
     */
    val sendText: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isNotEmpty()) {
            // 说完这一句：先把麦收掉，等 AI 回完、朗读完再开（否则她会听见自己说话）
            if (asr.isRecording) asr.cancel()
            tts.stop()
            messages.add(ChatMessage(nextId++, text, fromUser = true))
            input = ""

            if (apiKey.isBlank()) {
                messages.add(
                    ChatMessage(
                        nextId++,
                        "呜…还没有 API Key，点左上角的 ⚙️ 填一下嘛~ (｡•́︿•̀｡)",
                        fromUser = false,
                    )
                )
                resumeListening()
            } else {
                // 说话动画 + 请求（历史里带最近 12 条，让模型有上下文）
                Live2DController.speak()
                isTyping = true
                scope.launch {
                    // ---- 联网搜索：手动开了就搜，或者本地判断这句话需要查资料 ----
                    val wantSearch = searchSettings.isReady() &&
                        (forceSearch || SearchClient.needsSearch(text))
                    var searchBlock: String? = null
                    if (wantSearch) {
                        val cfg = searchSettings.toEngineConfig()
                        val r = withContext(Dispatchers.IO) { SearchClient.search(text, cfg) }
                        searchBlock = r.fold(
                            onSuccess = {
                                SearchClient.formatForPrompt(text, it, cfg.providerName())
                            },
                            onFailure = {
                                "＃ 联网搜索失败\n调用「${cfg.providerName()}」出错：${it.message}" +
                                    "。请告诉用户这次没查成（可以检查设置里的搜索 Key），不要编造资料。"
                                    .replace('＃', '#')
                            },
                        )
                        if (r.isFailure) {
                            messages.add(
                                ChatMessage(
                                    nextId++,
                                    "搜索出错了：${r.exceptionOrNull()?.message}",
                                    fromUser = false,
                                )
                            )
                        }
                    }
                    forceSearch = false

                    val history = buildList {
                        add(
                            DeepSeekClient.Msg(
                                "system",
                                buildString {
                                    append(persona.effectivePrompt())
                                    searchBlock?.let { append("\n\n").append(it) }
                                },
                            )
                        )
                        messages.takeLast(12).forEach {
                            // 搜索失败的提示气泡不进上下文，免得干扰模型
                            if (it.fromUser || it.isModelReply) {
                                add(
                                    DeepSeekClient.Msg(
                                        if (it.fromUser) "user" else "assistant",
                                        it.text,
                                    )
                                )
                            }
                        }
                    }
                    val result = DeepSeekClient.chat(apiKey, history, deepseekModel)
                    android.util.Log.d(
                        "DSAI-Chat",
                        "已发请求：模型=$deepseekModel，system ${history.first().content.length} 字，" +
                            "时间注入=${history.first().content.contains("现在是 ")}，" +
                            "联网搜索=${searchBlock != null}，历史 ${history.size - 1} 条",
                    )
                    isTyping = false
                    result.onSuccess { reply ->
                        // isModelReply = true：存档标题只认模型真正回的话
                        messages.add(
                            ChatMessage(nextId++, reply, fromUser = false, isModelReply = true)
                        )
                        Live2DController.speak()
                        // 回复里的关键字 → 表情/动作
                        ModelReactions.react(reply, Live2DController.currentModelName())
                        // 豆包 TTS 朗读（配好了才读）；没配就用简单的张嘴动画
                        if (ttsSettings.isReady()) {
                            // 朗读期间不开麦；tts.onDone 里会 resumeListening()
                            tts.speak(reply, ttsSettings.toEngineConfig())
                        } else {
                            scope.launch {
                                delay(900)
                                Live2DController.setMouthOpen(0f)
                                resumeListening()
                            }
                        }
                    }.onFailure { e ->
                        messages.add(
                            ChatMessage(nextId++, "呜…调用出错了：${e.message}", fromUser = false)
                        )
                        resumeListening()
                    }
                }
            }
        }
    }

    // 启动时把本地存过的 Live2D 位置/大小推给每个模型（切到哪个用哪个）
    LaunchedEffect(Unit) {
        delay(400) // 等 AndroidView 建好并 attach 上来
        Live2DController.allModelNames().forEach { name ->
            loadLive2dTransform(prefs, name)?.let { tf ->
                Live2DController.setUserTransformFor(name, tf[0], tf[1], tf[2])
            }
        }
    }

    // TTS 播放期间用音量驱动 Live2D 口型，并且朗读时不开麦（免得她听见自己）
    LaunchedEffect(tts) {
        tts.onStart = {
            ttsOn = true
            if (asr.isRecording) asr.cancel()
        }
        tts.onDone = {
            ttsOn = false
            Live2DController.setMouthOpen(0f)
            // 注意：onDone 现在等的是【真的播完】（播放头追上写入帧数），不是"服务端发完"
            resumeListening()
        }
        tts.onLevel = { level -> if (level > 0.02f) Live2DController.setMouthOpen(level) }
        tts.onError = { msg -> messages.add(ChatMessage(nextId++, msg, fromUser = false)) }
    }

    /**
     * 电话模式的核心：**功能开着就一直循环「听 → 静音 2.5 秒断句 → 识别 → 直接发送」**，
     * 不需要重新按按钮。发送后麦克风会先收起来，等 AI 回完、朗读完（resumeListening）再开。
     */
    LaunchedEffect(asr) {
        asr.onResult = { text ->
            asrBusy = false
            // 识别出来直接发；发送后由 AI 回复/朗读流程决定什么时候再开麦
            sendText(text)
        }
        asr.onEmpty = {
            // 没听清：不出提示、不退出，安静地继续听
            asrBusy = false
            resumeListening()
        }
        asr.onError = { msg ->
            // 真错误（麦克风不可用、解码失败等）才退出电话模式并提示
            asrBusy = false
            asrOn = false
            messages.add(ChatMessage(nextId++, msg, fromUser = false))
        }
        asr.onLevel = { level -> micLevel = level }
        asr.onAutoStop = {
            // 静音到点：收尾识别，但**保持 asrOn**，出了结果自动接着听
            if (asrOn) {
                asrBusy = true
                asr.stopAndDecode()
            } else {
                asr.cancel()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startAsr()
        else messages.add(ChatMessage(nextId++, "没有麦克风权限呀，语音输入用不了 (｡•́︿•̀｡)", fromUser = false))
    }

    // 启动自检：延迟到 GL 线程把模型加载完，再统一判断。
    LaunchedEffect(Unit) {
        delay(2500)
        val ready = Live2DController.isModelReady()
        messages.add(
            ChatMessage(
                id = nextId++,
                text = if (ready) {
                    "喵~ ${Live2DController.currentModelName()}上线啦！点左上角 ⚙️ 填好 API Key 就能聊了 (๑>◡<๑)"
                } else {
                    "喵……模型还没加载出来，稍等一下再试试～"
                },
                fromUser = false,
            )
        )
    }

    // ------------------------------------------------------------------
    // 屏幕常亮 + 切后台释放资源
    // ------------------------------------------------------------------

    // 前台常亮：这是个盯着看的 Live2D 陪伴 App，系统息屏会让语音聊天直接中断
    // （用户报的"打开 App 会息屏"就是这个原因：没加 FLAG_KEEP_SCREEN_ON）。
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 切到后台/息屏：立刻放开麦克风和播放器，别在后台占着资源
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    // 保持 asrOn 状态，但把麦克风收掉；回前台自动接着听
                    if (asr.isRecording) asr.cancel()
                    tts.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    resumeListening()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            asr.release()
            tts.release()
            Live2DController.detach()
        }
    }

    // 新消息进来自动滚到底
    LaunchedEffect(messages.size, isTyping) {
        val count = messages.size + if (isTyping) 1 else 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(
            modelName = modelName,
            editMode = editLive2d,
            onSwitchModel = {
                Live2DController.switchModel()
                modelName = Live2DController.currentModelName()
                messages.add(
                    ChatMessage(
                        id = nextId++,
                        text = "切换完成！现在是 $modelName 在陪你聊～",
                        fromUser = false,
                    )
                )
            },
            onOpenSettings = { showSettings = true },
            onOpenChatHistory = { showChatHistory = true },
            onResetTransform = {
                Live2DController.resetTransform()
                Live2DController.rememberTransform()
                val tf = Live2DController.currentTransform()
                saveLive2dTransform(prefs, modelName, tf[0], tf[1], tf[2])
                Live2DController.logTransform("恢复默认")
            },
            onFinishEdit = {
                Live2DController.rememberTransform()
                val tf = Live2DController.currentTransform()
                saveLive2dTransform(prefs, modelName, tf[0], tf[1], tf[2])
                Live2DController.logTransform("完成编辑并保存")
                editLive2d = false
                messages.add(
                    ChatMessage(
                        nextId++,
                        "位置和大小记住啦（$modelName：${"%.0f".format(tf[0] * 100)}% 大小）～",
                        fromUser = false,
                    )
                )
            },
        )

        Live2DStage(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            backgroundColor = StageLight,
            editMode = editLive2d,
            onReady = { view -> Live2DController.attach(view) },
        )

        if (editLive2d) {
            // 编辑模式：舞台上的控件会被 setZOrderOnTop 的 GL 表面盖住点不到，
            // 操作说明放舞台【下面】，关键的「完成」在顶栏工具条上。
            Live2dEditPanel(
                modelName = modelName,
                onReset = {
                    Live2DController.resetTransform()
                    Live2DController.rememberTransform()
                    val tf = Live2DController.currentTransform()
                    saveLive2dTransform(prefs, modelName, tf[0], tf[1], tf[2])
                },
                onDone = {},
            )
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (isTyping) {
                item(key = "typing") {
                    Text(
                        text = "$modelName 正在输入…",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (ttsOn) {
                item(key = "tts") {
                    Text(
                        text = "🔊 正在朗读…（发消息会立刻打断）",
                        color = WhaleCyan,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            asrOn = asrOn,
            asrLoading = asrBusy,
            ttsSpeaking = ttsOn,
            micLevel = micLevel,
            searchArmed = forceSearch,
            searchReady = searchSettings.isReady(),
            onSearchClick = {
                forceSearch = !forceSearch
                if (forceSearch && !searchSettings.isReady()) {
                    messages.add(
                        ChatMessage(
                            nextId++,
                            "还没配搜索服务呀～点左上角 ⚙️ →「联网搜索」填个 API Key，" +
                                "之后我就能帮你查最新消息了 (๑•̀ㅂ•́)و",
                            fromUser = false,
                        )
                    )
                }
            },
            onMicClick = {
                if (asrOn) {
                    stopAsr()
                } else if (asr.hasPermission()) {
                    startAsr()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onSend = { sendText(input) },
        )
    }

    if (showSettings) {
        SettingsDialog(
            deepseekKey = apiKey,
            deepseekModel = deepseekModel,
            tts = ttsSettings,
            persona = persona,
            asrSensitivity = asrSensitivity,
            search = searchSettings,
            onSave = { newKey, newModel, newTts, newPersona, newSensitivity, newSearch ->
                apiKey = newKey
                prefs.edit().putString(PREF_KEY, newKey).apply()
                deepseekModel = newModel
                prefs.edit().putString(PREF_DEEPSEEK_MODEL, newModel).apply()
                ttsSettings = newTts
                saveTtsSettings(prefs, newTts)
                persona = newPersona
                savePersonaSettings(prefs, newPersona)
                asrSensitivity = newSensitivity
                prefs.edit().putInt(PREF_ASR_SENSITIVITY, newSensitivity).apply()
                asr.sensitivity = newSensitivity
                searchSettings = newSearch
                saveSearchSettings(prefs, newSearch)
                showSettings = false
            },
            onOpenCurl = {
                showSettings = false
                showCurl = true
            },
            onTest = { draft ->
                // 试听：用草稿里的参数念一句，方便当场验证有没有填错
                if (draft.toEngineConfig().isComplete()) {
                    tts.speak("你好呀，我是大肥鱼，语音已经接通啦", draft.toEngineConfig())
                }
            },
            onEditLive2d = {
                showSettings = false
                editLive2d = true
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showChatHistory) {
        // 新建 / 切换之前：只要当前这段有用户说过的话，就先自动存一条，避免数据丢失
        val autoKeepCurrent: () -> Unit = {
            if (messages.any { it.fromUser }) {
                chatSessions = addChatSession(prefs, messages.toList())
                android.util.Log.d("DSAI-Chat", "自动存档当前对话 -> 共 ${chatSessions.size} 段")
            }
        }

        ChatHistoryDialog(
            sessions = chatSessions,
            currentCount = messages.size,
            onNewChat = {
                android.util.Log.d("DSAI-Chat", "点击：新对话（当前 ${messages.size} 条）")
                autoKeepCurrent()
                messages.clear()
                asr.cancel()
                tts.stop()
                showChatHistory = false
                messages.add(
                    ChatMessage(nextId++, "开一段新对话吧～想找回之前的，点🗨去列表里选 (๑>◡<๑)", fromUser = false)
                )
            },
            onSaveCurrent = {
                android.util.Log.d("DSAI-Chat", "点击：保存当前对话（${messages.size} 条）")
                chatSessions = addChatSession(prefs, messages.toList())
                android.util.Log.d("DSAI-Chat", "保存后共 ${chatSessions.size} 段")
                messages.add(
                    ChatMessage(
                        nextId++,
                        "存好啦～现在有 ${chatSessions.size} 段对话在列表里，下次打开点🗨就能接着聊 (๑>◡<๑)",
                        fromUser = false,
                    )
                )
            },
            onRestore = { session ->
                android.util.Log.d("DSAI-Chat", "点击：恢复「${session.title}」")
                autoKeepCurrent()
                messages.clear()
                messages.addAll(session.messages)
                nextId = (session.messages.maxOfOrNull { it.id } ?: 0L) + 1
                asr.cancel()
                tts.stop()
                showChatHistory = false
                messages.add(
                    ChatMessage(
                        nextId++,
                        "已经切回「${session.title}」这段对话啦，接着说吧～",
                        fromUser = false,
                    )
                )
            },
            onDelete = { session ->
                android.util.Log.d("DSAI-Chat", "点击：删除「${session.title}」")
                chatSessions = deleteChatSession(prefs, session.id)
            },
            onDismiss = { showChatHistory = false },
        )
    }

    if (showCurl) {
        CurlDialog(
            deepseekKey = apiKey,
            searchSettings = searchSettings,
            ttsSettings = ttsSettings,
            onDismiss = { showCurl = false },
        )
    }
}

/**
 * App 内 curl：填方法 / URL / 请求头 / 请求体，发出去把状态码和响应体原样显示。
 *
 * <p>手机上验接口用（搜索 Key 对不对、TTS 令牌还能不能用、DeepSeek 报什么错），
 * 不用连着电脑看 logcat。
 */
@Composable
private fun CurlDialog(
    deepseekKey: String,
    searchSettings: SearchSettings,
    ttsSettings: TtsSettings,
    onDismiss: () -> Unit,
) {
    val presets = remember(deepseekKey, searchSettings, ttsSettings) {
        HttpProbe.presets(
            deepseekKey = deepseekKey,
            searchProvider = searchSettings.provider,
            searchKey = searchSettings.apiKey,
            ttsAppId = ttsSettings.appId,
            ttsToken = ttsSettings.token,
        )
    }

    var method by remember { mutableStateOf("POST") }
    var url by remember { mutableStateOf(presets.firstOrNull()?.url ?: "https://") }
    var headers by remember { mutableStateOf(presets.firstOrNull()?.headers ?: "") }
    var body by remember { mutableStateOf(presets.firstOrNull()?.body ?: "") }
    var result by remember { mutableStateOf("点下面的「发送请求」试一下～") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepSeaCard,
        title = {
            Text("网络调试（curl）", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("预设", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                presets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { p ->
                            TextButton(onClick = {
                                method = p.method
                                url = p.url
                                headers = p.headers
                                body = p.body
                                result = "已填入「${p.label}」，点「发送请求」"
                            }) {
                                Text(p.label, color = WhaleCyan, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GET", "POST", "PUT", "DELETE").forEach { m ->
                        val selected = method == m
                        Button(
                            onClick = { method = m },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) WhaleCyan else DeepSeaSoft,
                                contentColor = if (selected) DeepSea else TextSecondary,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 2.dp,
                            ),
                        ) {
                            Text(m, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                SettingsField(label = "URL", value = url, onValueChange = { url = it }, hint = "https://…")
                SettingsField(
                    label = "请求头（每行一个 Key: Value）",
                    value = headers,
                    onValueChange = { headers = it },
                    hint = "Authorization: Bearer xxx",
                    singleLine = false,
                    minLines = 2,
                )
                SettingsField(
                    label = "请求体（JSON）",
                    value = body,
                    onValueChange = { body = it },
                    hint = "{}",
                    singleLine = false,
                    minLines = 3,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            running = true
                            result = "请求中…"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    HttpProbe.request(method, url, headers, body)
                                }
                                result = r.render()
                                running = false
                            }
                        },
                        enabled = !running && url.startsWith("http"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhaleCyan,
                            contentColor = DeepSea,
                        ),
                    ) {
                        Text(if (running) "发送中…" else "发送请求", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    TextButton(onClick = { result = "" }) {
                        Text("清空结果", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result,
                    color = if (result.startsWith("HTTP 2")) WhaleCyan else TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeepSeaSoft)
                        .padding(8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TextSecondary)
            }
        },
    )
}

/**
 * Live2D 位置/大小编辑面板。
 *
 * <p>必须放在舞台下面：GLSurfaceView 开了 setZOrderOnTop，
 * 舞台范围内的 Compose 控件会被 GL 表面盖住，点不到。
 */
@Composable
private fun ColumnScope.Live2dEditPanel(
    modelName: String,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    // 说明放在舞台下面；"完成/恢复默认"在顶栏（这里空间可能被挤压，别把关键操作放这）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "正在调整「$modelName」",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "· 单指拖动 = 移动位置\n· 双指捏合 = 放大缩小\n\n调好点右上角「完成」就会记住（按模型分别保存）",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onReset) {
            Text("恢复默认位置和大小", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * 对话列表：多条存档以列表展示，可恢复 / 删除，也能把当前对话另存一条。
 *
 * <p>设计上「每次打开 App 都是新对话」，所以要接着上次聊得从这里恢复。
 */
@Composable
private fun ChatHistoryDialog(
    sessions: List<ChatSession>,
    currentCount: Int,
    onNewChat: () -> Unit,
    onSaveCurrent: () -> Unit,
    onRestore: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepSeaCard,
        title = {
            Column {
                Text("对话列表", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "当前对话 $currentCount 条 · 已存 ${sessions.size} 段",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onNewChat,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhaleCyan,
                            contentColor = DeepSea,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Text("＋ 新对话", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onSaveCurrent, enabled = currentCount > 0) {
                        Text(
                            text = "保存当前",
                            color = if (currentCount > 0) WhaleCyan else TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(
                    text = "新建或切换到别的对话前，会先把当前这段自动存进列表，不会丢",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (sessions.isEmpty()) {
                    Text(
                        text = "还没有存过对话。\n每段对话会以「第一条你说的话」当标题列在这里，" +
                            "点一下就能切回去继续聊；右侧的垃圾桶可以删掉不要的。",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sessions, key = { it.id }) { s ->
                            ChatSessionRow(
                                session = s,
                                onRestore = { onRestore(s) },
                                onDelete = { pendingDelete = s },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TextSecondary)
            }
        },
    )

    // 删除要二次确认，避免手滑把聊了很久的记录弄没了
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = DeepSeaCard,
            title = { Text("删除这段对话？", color = TextPrimary, fontSize = 16.sp) },
            text = {
                Text(
                    text = "「${target.title}」共 ${target.messages.size} 条消息，删了就找不回来了。",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) {
                    Text("删除", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = TextSecondary)
                }
            },
        )
    }
}

/** 对话列表里的一行：标题 + 时间/条数 + 最后一句预览 + 删除。 */
@Composable
private fun ChatSessionRow(
    session: ChatSession,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val preview = session.messages.lastOrNull()?.text?.replace('\n', ' ')?.trim().orEmpty()

    Surface(
        color = DeepSeaSoft,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRestore),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${session.messages.size} 条 · ${formatTimestamp(session.savedAt)}",
                    color = WhaleCyan,
                    fontSize = 11.sp,
                )
                if (preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "删除存档",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 顶栏：左上角设置按钮，应用名，右上角切换模型。
 *
 * <p>按钮必须放在舞台【外面】：GLSurfaceView 是 setZOrderOnTop，
 * 舞台区域内的 Compose 控件会被 GL 表面盖住，点不到。
 */
@Composable
private fun TopBar(
    modelName: String,
    editMode: Boolean,
    onSwitchModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChatHistory: () -> Unit,
    onResetTransform: () -> Unit,
    onFinishEdit: () -> Unit,
) {
    // 编辑模式：顶栏直接变成"编辑工具条"。
    // ⚠️ 完成键必须放在这里 —— 舞台下面的空间在窄屏上会被挤掉，而且 GL 表面
    // （setZOrderOnTop）会盖住舞台范围内的任何 Compose 控件。
    if (editMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DeepSeaSoft)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "调整位置和大小",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "单指拖动=移动 · 双指捏合=缩放",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onResetTransform) {
                Text("恢复默认", color = TextSecondary, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = onFinishEdit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhaleCyan,
                    contentColor = DeepSea,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 6.dp,
                ),
            ) {
                Text("完成", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSeaSoft)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左上角竖排两个按钮：设置（上）/ 对话存档（下），尺寸一致
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenChatHistory, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat_history),
                    contentDescription = "对话存档",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(WhaleCyan),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "DSAI",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.weight(1f))

        ModelSwitchButton(modelName = modelName, onClick = onSwitchModel)
    }
}

/**
 * 模型切换按钮：渐变胶囊 + 图标 + 当前模型名。
 */
@Composable
private fun ModelSwitchButton(modelName: String, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = DeepSeaCard,
        animationSpec = tween(300),
        label = "switchBg",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(
                    listOf(WhaleBlueDark, bg),
                ),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_swap_horiz),
            contentDescription = "切换模型",
            tint = WhaleCyan,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = modelName,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 设置对话框：DeepSeek 对话的 Key + 豆包 TTS 的接入参数，全部只存本机 SharedPreferences。
 */
@Composable
private fun SettingsDialog(
    deepseekKey: String,
    deepseekModel: String,
    tts: TtsSettings,
    persona: PersonaSettings,
    asrSensitivity: Int,
    search: SearchSettings,
    onSave: (String, String, TtsSettings, PersonaSettings, Int, SearchSettings) -> Unit,
    onTest: (TtsSettings) -> Unit,
    onEditLive2d: () -> Unit,
    onOpenCurl: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(deepseekKey) }
    var modelDraft by remember { mutableStateOf(deepseekModel) }
    var draft by remember { mutableStateOf(tts) }
    var personaDraft by remember { mutableStateOf(persona) }
    var asrDraft by remember { mutableStateOf(asrSensitivity) }
    var searchDraft by remember { mutableStateOf(search) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepSeaCard,
        title = {
            Text("设置", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSectionTitle("DeepSeek 对话")
                SettingsField(
                    label = "API Key",
                    value = key,
                    onValueChange = { key = it },
                    hint = "sk-…",
                )
                Text(
                    text = "在 platform.deepseek.com 申请",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("模型", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODEL_LABELS.forEach { (id, label) ->
                        val selected = modelDraft == id
                        Button(
                            onClick = { modelDraft = id },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) WhaleCyan else DeepSeaSoft,
                                contentColor = if (selected) DeepSea else TextSecondary,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 4.dp,
                            ),
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text(
                    text = MODEL_HINTS.getOrElse(MODEL_LABELS.indexOfFirst { it.first == modelDraft }) { "" },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = onOpenCurl) {
                    Text("🔧 网络调试（App 内 curl）", color = WhaleCyan, fontSize = 13.sp)
                }
                Text(
                    text = "在手机上直接发 HTTP 请求看返回，用来验搜索 Key / TTS 令牌 / DeepSeek 报错",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSectionTitle("人设与记忆")
                SettingsField(
                    label = "系统提示词（人设）",
                    value = personaDraft.systemPrompt,
                    onValueChange = { personaDraft = personaDraft.copy(systemPrompt = it) },
                    hint = SYSTEM_PROMPT,
                    singleLine = false,
                    minLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { personaDraft = personaDraft.copy(systemPrompt = SYSTEM_PROMPT) },
                    ) {
                        Text("用内置默认", color = WhaleCyan, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { personaDraft = personaDraft.copy(systemPrompt = "") },
                    ) {
                        Text("清空（走默认）", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                SettingsField(
                    label = "辅助记忆文档",
                    value = personaDraft.memoryDoc,
                    onValueChange = { personaDraft = personaDraft.copy(memoryDoc = it) },
                    hint = "粘一段想让 Ta 记住的设定/备忘，每次对话都会带上",
                    singleLine = false,
                    minLines = 3,
                )
                Text(
                    text = if (personaDraft.isDefault()) {
                        "系统提示词留空时用内置默认人设"
                    } else {
                        "已自定义人设（${personaDraft.systemPrompt.length} 字）"
                    },
                    color = TextSecondary,
                    fontSize = 11.sp,
                )

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSectionTitle("联网搜索")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许联网查资料", color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = searchDraft.enabled,
                        onCheckedChange = { searchDraft = searchDraft.copy(enabled = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSea,
                            checkedTrackColor = WhaleCyan,
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("服务商", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SearchClient.PROVIDER_TAVILY to "Tavily",
                        SearchClient.PROVIDER_BOCHA to "博查",
                    ).forEach { (id, label) ->
                        val selected = searchDraft.provider == id
                        Button(
                            onClick = { searchDraft = searchDraft.copy(provider = id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) WhaleCyan else DeepSeaSoft,
                                contentColor = if (selected) DeepSea else TextSecondary,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 4.dp,
                            ),
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                SettingsField(
                    label = "搜索 API Key",
                    value = searchDraft.apiKey,
                    onValueChange = { searchDraft = searchDraft.copy(apiKey = it) },
                    hint = if (searchDraft.provider == SearchClient.PROVIDER_BOCHA) {
                        "bochaai.com 的 API Key"
                    } else {
                        "tavily.com 的 API Key（tvly-…）"
                    },
                )
                Text(
                    text = "Tavily 英文资料强；博查（bochaai）中文资料强、国内可直连。" +
                        "配好后：聊天里出现「最新 / 查一下 / 天气 / 股价」这类词会自动联网；" +
                        "也可以点输入框左边的 🔍 手动查一次。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSectionTitle("Live2D 显示")
                Button(
                    onClick = onEditLive2d,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepSeaSoft,
                        contentColor = WhaleCyan,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("调整位置和大小", fontSize = 14.sp)
                }
                Text(
                    text = "点进去后：单指拖动=移动，双指捏合=缩放，完成后自动记住（按模型分别保存）",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSectionTitle("语音输入")
                Text(
                    text = "灵敏度（说话要多响才送去识别）",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ASR_SENSITIVITY_LABELS.forEachIndexed { index, label ->
                        val selected = asrDraft == index
                        Button(
                            onClick = { asrDraft = index },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) WhaleCyan else DeepSeaSoft,
                                contentColor = if (selected) DeepSea else TextSecondary,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 4.dp,
                            ),
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text(
                    text = ASR_SENSITIVITY_HINTS[asrDraft.coerceIn(0, ASR_SENSITIVITY_HINTS.size - 1)],
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "调不准的话看日志：录音结束时手机端会打印「峰值 0.0xx / 门槛 0.0xx」，" +
                        "说话时峰值明显高于门槛就说明能识别到。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSectionTitle("豆包语音朗读（TTS）")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动朗读回复", color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.autoRead,
                        onCheckedChange = { draft = draft.copy(autoRead = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSea,
                            checkedTrackColor = WhaleCyan,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                SettingsField(
                    label = "App ID",
                    value = draft.appId,
                    onValueChange = { draft = draft.copy(appId = it) },
                    hint = "火山引擎控制台的应用 ID",
                )
                SettingsField(
                    label = "Access Token",
                    value = draft.token,
                    onValueChange = { draft = draft.copy(token = it) },
                    hint = "访问令牌",
                )
                SettingsField(
                    label = "音色 ID",
                    value = draft.speaker,
                    onValueChange = { draft = draft.copy(speaker = it) },
                    hint = DEFAULT_SPEAKER,
                )
                SettingsField(
                    label = "资源 ID",
                    value = draft.resourceId,
                    onValueChange = { draft = draft.copy(resourceId = it) },
                    hint = DEFAULT_RESOURCE_ID,
                )
                Text(
                    text = "在火山引擎控制台开通语音合成后，从**同一个应用**里复制 App ID 和 Access Token" +
                        "（跨应用混用会报 grant not found）。音色 ID 如 zh_female_cancan_mars_bigtts，" +
                        "自己复刻的音色是 S_ 开头。资源 ID：小模型/复刻音色用 volc.megatts.default，" +
                        "大模型用 volc.service_type.10029，2.0 用 seed-tts-2.0 —— 填错会提示 resource not granted。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                Spacer(modifier = Modifier.height(10.dp))
                val ttsReady = draft.toEngineConfig().isComplete()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onTest(draft) }, enabled = ttsReady) {
                        Text(
                            text = "试听一句",
                            color = if (ttsReady) WhaleCyan else TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    if (!ttsReady) {
                        Text(
                            text = "填完 App ID / Access Token / 音色 ID 才能试听",
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(key.trim(), modelDraft, draft, personaDraft, asrDraft, searchDraft)
            }) {
                Text("保存", color = WhaleCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = WhaleCyan,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, color = TextSecondary, fontSize = 13.sp) },
            singleLine = singleLine,
            minLines = minLines,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = WhaleBlue,
                unfocusedBorderColor = DeepSeaSoft,
                cursorColor = WhaleBlue,
            ),
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val alignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (msg.fromUser) BubbleUser else BubbleAi
    val textColor = if (msg.fromUser) Color.White else TextPrimary

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.fromUser) 16.dp else 4.dp,
                        bottomEnd = if (msg.fromUser) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = msg.text,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    asrOn: Boolean,
    asrLoading: Boolean,
    ttsSpeaking: Boolean,
    micLevel: Float,
    searchArmed: Boolean,
    searchReady: Boolean,
    onSearchClick: () -> Unit,
    onMicClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSeaSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左下角：电话图标 = 本地语音输入开关
        MicButton(on = asrOn, loading = asrLoading, level = micLevel, onClick = onMicClick)

        Spacer(modifier = Modifier.width(4.dp))

        // 🔍 联网搜索：按下高亮，下一条消息先搜再问（没配 Key 时也能按，会提示去设置）
        IconButton(onClick = onSearchClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "联网搜索",
                tint = when {
                    searchArmed -> WhaleCyan
                    searchReady -> TextSecondary
                    else -> TextSecondary.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = when {
                        asrLoading -> "识别中…"
                        ttsSpeaking && asrOn -> "正在朗读…说完会自动接着听"
                        asrOn -> "电话已接通：一直听着，停 2 秒自动出字"
                        else -> "跟大肥鱼说点什么…"
                    },
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = if (asrOn) Color(0xFFFF6B7A) else WhaleBlue,
                unfocusedBorderColor = DeepSeaCard,
                cursorColor = WhaleBlue,
            ),
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(46.dp)
                .background(WhaleBlue, RoundedCornerShape(23.dp)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = DeepSea,
            )
        }
    }
}

/**
 * 左下角的电话图标：点亮 = 正在用本地 ASR 听你说话。
 *
 * <p>录音时按钮变红，外圈随说话音量呼吸，说话就能看见反馈。
 */
@Composable
private fun MicButton(
    on: Boolean,
    loading: Boolean,
    level: Float,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (on) Color(0xFFFF4D5E) else DeepSeaCard,
        animationSpec = tween(220),
        label = "micBg",
    )
    val ringAlpha = if (on) (0.25f + 0.75f * level) else 0f

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(46.dp)
            .background(bg, RoundedCornerShape(23.dp))
            .border(
                width = 2.dp,
                color = Color(0xFFFF8A94).copy(alpha = ringAlpha),
                shape = RoundedCornerShape(23.dp),
            ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = WhaleCyan,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Phone,
                contentDescription = if (on) "停止语音输入" else "语音输入",
                tint = if (on) Color.White else WhaleCyan,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
