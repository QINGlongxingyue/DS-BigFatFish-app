package com.example.dsaiapp.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.dsaiapp.MainActivity
import com.example.dsaiapp.R
import com.example.dsaiapp.chat.DeepSeekClient
import com.example.dsaiapp.live2d.Live2DDelegate
import com.example.dsaiapp.live2d.Live2DGLView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面宠物：用悬浮窗把 Live2D 模型画在其它应用之上。
 *
 * <p>交互（按需求）：
 * - 右上角一个**常显的折叠按钮**（⋯），点它展开/收起三个小按钮
 * - 💬 打开一个**悬浮聊天框**（不跳 App），直接在这儿跟她聊
 * - 📞 进电话模式（这条需要主界面，会打开 App 并自动开始听）
 * - ✥ 按住拖动整个窗口
 * - **点模型本身不再打开 App**，只用来拖（想开 App 点通知或图标）
 *
 * <p>关键设计（踩坑点）：
 * 1. **Cubism 是单例**，一个进程只有一份模型状态。悬浮窗只在 App 退到后台时才挂 GL 视图，
 *    回前台就摘掉 —— 否则两边同时渲染会崩（裁剪蒙版缓冲按尺寸分配）。
 * 2. 悬浮窗里 GL 视图必须 `setZOrderOnTop(false)`，否则 Surface 会盖住同一窗口里的按钮。
 * 3. 透明背景清屏色必须是 (0,0,0,0)：Surface 缓冲按**预乘 alpha** 解释，清成 (1,1,1,0) 会变白底。
 * 4. 宠物区域要 `onInterceptTouchEvent` 返回 true，否则 GL 视图的 onTouchEvent 会把事件吃掉。
 */
class DesktopPetService : Service() {

    companion object {
        private const val TAG = "DSAI-Pet"
        private const val CHANNEL_ID = "dsai_desktop_pet"
        private const val NOTIFICATION_ID = 1001

        /** 打开 App 且直接进入电话模式的 Intent extra。 */
        const val EXTRA_START_VOICE = "start_voice"

        // 这几个 key 必须和 MainActivity 里的常量保持一致
        private const val PREFS_NAME = "dsai_settings"
        private const val PREF_KEY = "deepseek_api_key"
        private const val PREF_MODEL = "deepseek_model"
        private const val PREF_SYSTEM_PROMPT = "system_prompt"
        private const val PREF_MEMORY_DOC = "memory_doc"

        @Volatile
        var running = false
            private set

        /**
         * App 是否在前台（由 MainActivity 的生命周期更新）。
         *
         * <p>默认 false：服务被 START_STICKY 拉起时 App 通常不在前台，
         * 默认 true 会导致「进程重启后悬浮窗永远不出现」（踩过）。
         */
        @Volatile
        private var appForeground = false

        fun start(context: Context) {
            val intent = Intent(context, DesktopPetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopPetService::class.java))
        }

        /** 主界面切前后台时通知：前台就摘掉悬浮模型，后台再挂回去。 */
        fun onAppForegroundChanged(foreground: Boolean) {
            appForeground = foreground
            instance?.applyForegroundState()
        }

        @Volatile
        private var instance: DesktopPetService? = null
    }

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 宠物窗口 ----
    private var petRoot: FrameLayout? = null
    private var petHolder: FrameLayout? = null
    private var petGl: Live2DGLView? = null
    private var menuBar: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    /** 折叠菜单是否展开（默认收起，只留一个 ⋯ 按钮）。 */
    private var menuExpanded = false

    // ---- 聊天框 ----
    private var chatRoot: View? = null
    private var chatLog: TextView? = null
    private var chatScroll: ScrollView? = null
    private val chatHistory = ArrayList<DeepSeekClient.Msg>()

    // ---- 悬浮窗内的电话模式 ----
    private val asr by lazy { com.example.dsaiapp.asr.AsrEngine(this) }
    private var voiceMode = false
    private var asrWired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundSafe()
        applyForegroundState()
        Log.d(TAG, "桌面宠物服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyForegroundState()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "桌面宠物服务结束")
        running = false
        instance = null
        scope.cancel()
        runCatching { asr.release() }
        closeChatBox()
        removeOverlay()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------
    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌面宠物", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "让大肥鱼留在桌面上"
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        startForeground(
            NOTIFICATION_ID,
            builder
                .setContentTitle("大肥鱼在桌面上")
                .setContentText("点通知回到 App，或在设置里关掉桌面显示")
                .setSmallIcon(R.drawable.ic_chat_history)
                .setContentIntent(open)
                .setOngoing(true)
                .build(),
        )
    }

    // ------------------------------------------------------------------
    // 宠物窗口
    // ------------------------------------------------------------------

    /** 前台就暂停悬浮渲染（避免和主界面的 GL 抢单例模型），后台恢复。 */
    private fun applyForegroundState() {
        if (appForeground) {
            // ⚠️ 这里【不要】removeView：销毁 GL 线程的同时主界面的 GL 线程正在接管
            // 同一个单例模型，两边一起 release/重载就是"点按钮回 App 直接卡死"的原因。
            // 正确的做法是先让悬浮窗的 GL 线程停下来（onPause 会等它真的停），
            // 窗口本身留着（隐藏即可）。
            runCatching { petGl?.onPause() }
            runCatching { Live2DDelegate.getInstance().setTransparentBackground(false) }
            petRoot?.visibility = View.GONE
        } else {
            runCatching { Live2DDelegate.getInstance().setTransparentBackground(true) }
            ensureOverlay()
            petRoot?.visibility = View.VISIBLE
            runCatching { petGl?.onResume() }
        }
    }

    private fun ensureOverlay() {
        if (petRoot != null) return

        val rootView = FrameLayout(this)
        petRoot = rootView

        // ---- 宠物本体：透明背景，只留模型；拦截触摸后由容器处理拖动 ----
        val holder = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
        }
        petHolder = holder
        val gl = Live2DGLView(this).apply {
            isClickable = false
            isFocusable = false
            // 悬浮窗里 Surface 在普通层，否则会盖住按钮
            setZOrderOnTop(false)
        }
        gl.setTransparentBackground(true)
        petGl = gl
        holder.addView(
            gl,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rootView.addView(holder, FrameLayout.LayoutParams(px(190), px(250)))

        // 点模型不再打开 App，只支持拖动
        holder.setOnTouchListener(petDragListener)

        // ---- 右上角：常显 ⋯ 按钮 + 折叠的三个按钮 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.TOP
        }
        menuBar = bar

        bar.addView(makeButton("⋯", "菜单") { toggleMenu() })
        bar.addView(makeButton("💬", "聊天", show = false) { openChatBox() })
        bar.addView(makeButton("📞", "电话模式", show = false) { startVoiceInOverlay() })
        bar.addView(makeButton("✥", "拖动窗口", show = false) { Log.d(TAG, "拖动按钮按下") }
            .also { it.setOnTouchListener(dragListener) })

        rootView.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END },
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(24)
            y = px(180)
        }
        params = lp

        runCatching { windowManager.addView(rootView, lp) }
            .onFailure { Log.e(TAG, "悬浮窗添加失败", it) }

        applyMenuVisibility()
    }

    private fun removeOverlay() {
        // 先停 GL 线程再摘窗口，别让它在销毁过程中和主界面抢模型
        runCatching { petGl?.onPause() }
        petGl = null
        petRoot?.let { runCatching { windowManager.removeView(it) } }
        petRoot = null
        petHolder = null
        menuBar = null
    }

    /** 展开/收起菜单。 */
    private fun toggleMenu() {
        menuExpanded = !menuExpanded
        Log.d(TAG, "菜单 -> ${if (menuExpanded) "展开" else "收起"}")
        applyMenuVisibility()
    }

    private fun applyMenuVisibility() {
        val bar = menuBar ?: return
        // 第 0 个是 ⋯ 本身，永远显示；其余按展开状态切换
        for (i in 1 until bar.childCount) {
            bar.getChildAt(i).visibility = if (menuExpanded) View.VISIBLE else View.GONE
        }
    }

    /**
     * 小圆按钮。
     *
     * @param show 初始是否显示（折叠菜单里默认隐藏）
     */
    private fun makeButton(
        label: String,
        desc: String,
        show: Boolean = true,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        contentDescription = desc
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#E8F6FF"))

        // 水波：RippleDrawable 会自动从按下的位置扩散，mask 用圆形保证只在这个圆里
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC0E2338"))
            setStroke(px(1), Color.parseColor("#6696D8FF"))
        }
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.parseColor("#66B9E4FF")),
            base,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
        )
        background = ripple

        val size = px(34)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = px(4) }
        visibility = if (show) View.VISIBLE else View.GONE
        setOnClickListener { onClick() }
    }

    // ---- 拖动：宠物本体 和 ✥ 按钮 都能拖 ----
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f

    private fun moveWindow(rawX: Float, rawY: Float) {
        val lp = params ?: return
        lp.x = dragStartX + (rawX - dragDownRawX).toInt()
        lp.y = dragStartY + (rawY - dragDownRawY).toInt()
        runCatching { windowManager.updateViewLayout(petRoot, lp) }
    }

    private val dragListener = View.OnTouchListener { _, event ->
        val lp = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = lp.x
                dragStartY = lp.y
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                moveWindow(event.rawX, event.rawY)
                true
            }
            else -> false
        }
    }

    /**
     * 宠物本体：**只做视线跟随，不移动窗口**。
     *
     * <p>移动窗口只允许用 ✥ 按钮拖 —— 按在模型上跟着走会让人没法"戳她看反应"。
     */
    private val petDragListener = View.OnTouchListener { view, event ->
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val nx = (event.x / w) * 2f - 1f
        val ny = 1f - (event.y / h) * 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                runCatching { Live2DDelegate.getInstance().model?.setLook(nx, ny) }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                runCatching { Live2DDelegate.getInstance().model?.setLook(0f, 0f) }
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 悬浮聊天框
    // ------------------------------------------------------------------

    private fun openChatBox() {
        if (chatRoot != null) return

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(Color.parseColor("#F2102033"))
                setStroke(px(1), Color.parseColor("#6696D8FF"))
            }
        }

        // 标题行：标题 + 关闭
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = "和大肥鱼聊天"
                setTextColor(Color.parseColor("#E8F6FF"))
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            TextView(this).apply {
                text = "✕"
                contentDescription = "关闭聊天"
                setTextColor(Color.parseColor("#9FC4E8"))
                textSize = 16f
                setPadding(px(10), 0, px(4), 0)
                setOnClickListener { closeChatBox() }
            },
        )
        column.addView(titleRow)

        // 对话内容
        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#D8ECFF"))
            textSize = 13f
            setLineSpacing(px(3).toFloat(), 1f)
            movementMethod = ScrollingMovementMethod()
            text = "（这里直接聊，不用打开 App）"
        }
        chatLog = log
        val scroll = ScrollView(this).apply { addView(log) }
        chatScroll = scroll
        column.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(180),
            ).apply { topMargin = px(8) },
        )

        // 输入行：输入框 + 发送
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "说点什么…"
            setHintTextColor(Color.parseColor("#7FA6C8"))
            setTextColor(Color.parseColor("#E8F6FF"))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply {
                cornerRadius = px(14).toFloat()
                setColor(Color.parseColor("#660E2338"))
                setStroke(px(1), Color.parseColor("#5596D8FF"))
            }
            setPadding(px(10), px(6), px(10), px(6))
        }
        inputRow.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val sendNow: () -> Unit = {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                sendFromChatBox(text)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendNow()
                true
            } else {
                false
            }
        }
        inputRow.addView(
            TextView(this).apply {
                text = "发送"
                contentDescription = "发送"
                setTextColor(Color.parseColor("#8FD8FF"))
                textSize = 14f
                setPadding(px(12), px(8), px(6), px(8))
                setOnClickListener { sendNow() }
            },
        )
        column.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(8) })

        val lp = WindowManager.LayoutParams(
            px(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不加 NOT_FOCUSABLE：聊天框要能拿到输入焦点、弹出键盘
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        runCatching { windowManager.addView(column, lp) }
            .onFailure { Log.e(TAG, "聊天框添加失败", it) }
        chatRoot = column
        input.requestFocus()
    }

    private fun closeChatBox() {
        voiceMode = false
        runCatching { asr.cancel() }
        chatRoot?.let { runCatching { windowManager.removeView(it) } }
        chatRoot = null
        chatLog = null
        chatScroll = null
    }

    // ------------------------------------------------------------------
    // 悬浮窗内的电话模式（不跳 App）
    // ------------------------------------------------------------------

    /**
     * 点 📞：打开聊天框并开始听，说完停 2.5 秒自动识别、直接发出去，
     * 回复到达后继续听 —— 和 App 里那套手感一致，只是不用跳前台。
     */
    private fun startVoiceInOverlay() {
        openChatBox()
        voiceMode = true

        if (!asr.hasPermission()) {
            appendLine("系统：还没给麦克风权限。去 App 里点一次电话图标授权后再来。")
            voiceMode = false
            return
        }

        wireAsrOnce()
        if (!asr.isReady) {
            appendLine("（正在加载识别模型，几秒…）")
            scope.launch {
                val ok = withContext(Dispatchers.IO) { asr.prepare() }
                if (ok) {
                    appendLine("（电话模式已接通：说完停 2.5 秒自动发出去）")
                    if (voiceMode) asr.start()
                } else {
                    appendLine("识别模型加载失败，先打字吧。")
                    voiceMode = false
                }
            }
        } else {
            appendLine("（电话模式已接通：说完停 2.5 秒自动发出去）")
            asr.start()
        }
    }

    /** 只绑定一次 ASR 回调。 */
    private fun wireAsrOnce() {
        if (asrWired) return
        asrWired = true
        asr.onResult = { text ->
            if (chatRoot == null) {
                voiceMode = false
            } else {
                sendFromChatBox(text)
            }
        }
        asr.onEmpty = {
            if (voiceMode && chatRoot != null) asr.start() // 没听清，接着听
        }
        asr.onError = { msg ->
            appendLine("系统：$msg")
            voiceMode = false
        }
        asr.onLevel = { /* 悬浮窗没有呼吸灯，忽略 */ }
        asr.onAutoStop = {
            if (voiceMode && chatRoot != null) asr.stopAndDecode() else asr.cancel()
        }
    }

    private fun appendLine(line: String) {
        val log = chatLog ?: return
        val old = if (log.text.toString() == "（这里直接聊，不用打开 App）") "" else log.text.toString()
        log.text = if (old.isEmpty()) line else old + "\n" + line
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendFromChatBox(text: String) {
        appendLine("我：$text")
        val key = prefs().getString(PREF_KEY, "").orEmpty()
        if (key.isBlank()) {
            appendLine("系统：还没填 DeepSeek API Key，去 App 设置里填一下。")
            return
        }
        val model = prefs().getString(PREF_MODEL, DeepSeekClient.MODEL_CHAT)
            ?: DeepSeekClient.MODEL_CHAT

        chatHistory.add(DeepSeekClient.Msg("user", text))
        scope.launch {
            appendLine("大肥鱼：…")
            val result = withContext(Dispatchers.IO) {
                val history = buildList {
                    add(DeepSeekClient.Msg("system", petSystemPrompt()))
                    // 只带上桌面聊天的最近几轮，保持轻量
                    chatHistory.takeLast(10).forEach { add(it) }
                }
                DeepSeekClient.chat(key, history, model)
            }
            // 先把"…"那行删掉再写真正回复
            removeLastPlaceholder()
            result.onSuccess { reply ->
                chatHistory.add(DeepSeekClient.Msg("assistant", reply))
                appendLine("大肥鱼：$reply")
                // 电话模式：念完接着听（这里没有 TTS，直接续上）
                if (voiceMode && chatRoot != null) runCatching { asr.start() }
            }.onFailure { e ->
                appendLine("出错：${e.message}")
                if (voiceMode && chatRoot != null) runCatching { asr.start() }
            }
        }
    }

    private fun removeLastPlaceholder() {
        val log = chatLog ?: return
        val lines = log.text.toString().lines().toMutableList()
        if (lines.isNotEmpty() && lines.last() == "大肥鱼：…") {
            lines.removeAt(lines.size - 1)
            log.text = lines.joinToString("\n")
        }
    }

    /** 桌面聊天用的 system 提示词：人设（可自定义）+ 当前时间 + 记忆。 */
    private fun petSystemPrompt(): String {
        val p = prefs()
        val custom = p.getString(PREF_SYSTEM_PROMPT, "").orEmpty().trim()
        val base = custom.ifBlank {
            "你是「大肥鱼」，一只住在手机桌面上的蓝色鲸鱼 AI 助手，性格软萌、爱撒娇。" +
                "用简体中文、口语化回复，1~2 句、50 字以内，可以偶尔用颜文字。"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder(base)
        sb.append("\n\n# 当前时间\n现在是 ").append(time).append("（用户手机的本地时间）。")
        val memory = p.getString(PREF_MEMORY_DOC, "").orEmpty().trim()
        if (memory.isNotEmpty()) {
            sb.append("\n\n# 长期记忆（用户提供的背景资料，回答时参考但不要复述）\n").append(memory)
        }
        return sb.toString()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    /** 打开主界面；voice=true 时进去直接开电话模式。 */
    private fun openApp(voice: Boolean) {
        // CLEAR_TOP + SINGLE_TOP：复用已有实例，避免反复创建 Activity 导致的多实例/卡死
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            if (voice) putExtra(EXTRA_START_VOICE, true)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "打开 App 失败", it) }
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
