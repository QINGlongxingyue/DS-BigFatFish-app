package com.example.dsaiapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dsaiapp.chat.DeepSeekClient
import com.example.dsaiapp.chat.ModelReactions
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var showSettings by remember { mutableStateOf(false) }
    var isTyping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        onDispose { Live2DController.detach() }
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
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
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
                            scope.launch {
                                delay(900)
                                Live2DController.setMouthOpen(0f)
                            }
                            // 回复里的关键字 → 表情/动作
                            ModelReactions.react(reply, Live2DController.currentModelName())
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
            currentKey = apiKey,
            onSave = { newKey ->
                apiKey = newKey
                prefs.edit().putString(PREF_KEY, newKey).apply()
                showSettings = false
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
            imageVector = Icons.Filled.SwapHoriz,
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
 * 设置对话框：填 DeepSeek API Key，只存本机 SharedPreferences。
 */
@Composable
private fun SettingsDialog(currentKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepSeaCard,
        title = {
            Text("DeepSeek API Key", color = TextPrimary, fontSize = 17.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-…", color = TextSecondary, fontSize = 14.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = WhaleBlue,
                        unfocusedBorderColor = DeepSeaSoft,
                        cursorColor = WhaleBlue,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在 platform.deepseek.com 申请，只保存在本机，不会上传到任何别的地方。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSeaSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("跟大肥鱼说点什么…", color = TextSecondary, fontSize = 14.sp) },
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = WhaleBlue,
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
