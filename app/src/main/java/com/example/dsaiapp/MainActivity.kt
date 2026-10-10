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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dsaiapp.asr.AsrEngine
import com.example.dsaiapp.chat.DeepSeekClient
import com.example.dsaiapp.chat.ModelReactions
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
)

private const val SYSTEM_PROMPT =
    "你是「大肥鱼」，一只住在深海里的蓝色鲸鱼AI助手（DeepSeek 吉祥物）。" +
        "性格软萌、爱撒娇，回复要简短口语化（一般不超过 50 个字），可以偶尔用颜文字。全程用中文。"

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
    var showSettings by remember { mutableStateOf(false) }
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

    // TTS 播放期间用音量驱动 Live2D 口型（顺带把"口型同步"这个 TODO 做掉）
    LaunchedEffect(tts) {
        tts.onStart = { ttsOn = true }
        tts.onDone = {
            ttsOn = false
            Live2DController.setMouthOpen(0f)
        }
        tts.onLevel = { level -> if (level > 0.02f) Live2DController.setMouthOpen(level) }
        tts.onError = { msg -> messages.add(ChatMessage(nextId++, msg, fromUser = false)) }
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
            if (asrOn) asr.start()
        }
    }

    val stopAsr: () -> Unit = {
        asrOn = false
        micLevel = 0f
        asrBusy = true
        asr.stopAndDecode()
    }

    // 识别结果接到输入框后面（可以一句话一句话地攒）；出错就在聊天里说一声。
    // 放在 stopAsr 之后注册，因为「静音自动断句」要复用同一套收尾流程。
    LaunchedEffect(asr) {
        asr.onResult = { text ->
            asrBusy = false
            input = if (input.isBlank()) text else input.trimEnd() + " " + text
        }
        asr.onError = { msg ->
            asrBusy = false
            messages.add(ChatMessage(nextId++, msg, fromUser = false))
        }
        asr.onLevel = { level -> micLevel = level }
        asr.onAutoStop = { if (asrOn) stopAsr() }
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
        )

        Live2DStage(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            backgroundColor = StageLight,
            onReady = { view -> Live2DController.attach(view) },
        )

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

        InputBar(
            value = input,
            onValueChange = { input = it },
            asrOn = asrOn,
            asrLoading = asrBusy,
            micLevel = micLevel,
            onMicClick = {
                if (asrOn) {
                    stopAsr()
                } else if (asr.hasPermission()) {
                    startAsr()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    // 发送时把语音输入停掉，免得边发边录；同时打断正在朗读的上一句
                    if (asrOn) stopAsr()
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
                        return@InputBar
                    }

                    // 说话动画 + 请求（历史里带最近 12 条，让模型有上下文）
                    Live2DController.speak()
                    isTyping = true
                    scope.launch {
                        val history = buildList {
                            add(DeepSeekClient.Msg("system", SYSTEM_PROMPT))
                            messages.takeLast(12).forEach {
                                add(
                                    DeepSeekClient.Msg(
                                        if (it.fromUser) "user" else "assistant",
                                        it.text,
                                    )
                                )
                            }
                        }
                        val result = DeepSeekClient.chat(apiKey, history)
                        isTyping = false
                        result.onSuccess { reply ->
                            messages.add(ChatMessage(nextId++, reply, fromUser = false))
                            Live2DController.speak()
                            // 回复里的关键字 → 表情/动作
                            ModelReactions.react(reply, Live2DController.currentModelName())
                            // 豆包 TTS 朗读（配好了才读）；没配就用简单的张嘴动画
                            if (ttsSettings.isReady()) {
                                tts.speak(reply, ttsSettings.toEngineConfig())
                            } else {
                                scope.launch {
                                    delay(900)
                                    Live2DController.setMouthOpen(0f)
                                }
                            }
                        }.onFailure { e ->
                            messages.add(
                                ChatMessage(nextId++, "呜…调用出错了：${e.message}", fromUser = false)
                            )
                        }
                    }
                }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            deepseekKey = apiKey,
            tts = ttsSettings,
            onSave = { newKey, newTts ->
                apiKey = newKey
                prefs.edit().putString(PREF_KEY, newKey).apply()
                ttsSettings = newTts
                saveTtsSettings(prefs, newTts)
                showSettings = false
            },
            onTest = { draft ->
                // 试听：用草稿里的参数念一句，方便当场验证有没有填错
                if (draft.toEngineConfig().isComplete()) {
                    tts.speak("你好呀，我是大肥鱼，语音已经接通啦", draft.toEngineConfig())
                }
            },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * 顶栏：左上角设置按钮，应用名，右上角切换模型。
 *
 * <p>按钮必须放在舞台【外面】：GLSurfaceView 是 setZOrderOnTop，
 * 舞台区域内的 Compose 控件会被 GL 表面盖住，点不到。
 */
@Composable
private fun TopBar(modelName: String, onSwitchModel: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSeaSoft)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左上角：设置（填 API Key）
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "设置",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
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
    tts: TtsSettings,
    onSave: (String, TtsSettings) -> Unit,
    onTest: (TtsSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(deepseekKey) }
    var draft by remember { mutableStateOf(tts) }

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
            TextButton(onClick = { onSave(key.trim(), draft) }) {
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
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, color = TextSecondary, fontSize = 13.sp) },
            singleLine = true,
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
    micLevel: Float,
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

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = when {
                        asrLoading -> "识别中…"
                        asrOn -> "正在听…停 2 秒就自动识别"
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
