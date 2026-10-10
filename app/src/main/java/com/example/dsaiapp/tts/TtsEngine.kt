package com.example.dsaiapp.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 豆包（火山引擎）**双向流式 TTS** 的 WebSocket 客户端。
 *
 * 官方文档：https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-text-to-speech-websocket
 *
 * 协议是「二进制帧 + 事件机」，帧格式（大端）：
 * ```
 *   字节 0    : 协议版本 0x11
 *   字节 1    : 消息类型 0x14=客户端请求 0x94=服务端响应 0xB4=服务端音频
 *   字节 2    : 序列化|压缩 0x10=JSON 0x00=不压缩
 *   字节 3    : 保留 0
 *   字节 4-7  : event（i32 大端）
 *   [会话级]  : session_id 长度（u32）+ session_id 字节
 *              payload 长度（u32）+ payload 字节
 * ```
 *
 * 会话流程：
 * StartConnection(1) → ConnectionStarted(50) → StartSession(100) → SessionStarted(150)
 * → TaskRequest(200，带文本) → 一串 350/352/351（音频在 0xB4 帧里）→ FinishSession(102)
 * → SessionFinished(152) → FinishConnection(2)
 */
class TtsEngine {

    companion object {
        private const val TAG = "DSAI-TTS"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"

        // 帧头常量
        private const val PROTOCOL_VERSION = 0x11
        private const val MSG_FULL_CLIENT = 0x14
        private const val MSG_FULL_SERVER = 0x94
        private const val MSG_AUDIO_ONLY = 0xB4
        private const val SERIALIZATION_JSON = 0x10
        private const val NO_COMPRESSION = 0x00

        // 事件号
        private const val EV_START_CONNECTION = 1
        private const val EV_FINISH_CONNECTION = 2
        private const val EV_CONNECTION_STARTED = 50
        private const val EV_CONNECTION_FAILED = 51
        private const val EV_START_SESSION = 100
        private const val EV_FINISH_SESSION = 102
        private const val EV_SESSION_STARTED = 150
        private const val EV_SESSION_FINISHED = 152
        private const val EV_SESSION_FAILED = 153
        private const val EV_TASK_REQUEST = 200
        private const val EV_TTS_SENTENCE_START = 350
        private const val EV_TTS_SENTENCE_END = 351
        private const val EV_TTS_RESPONSE = 352

        private const val NAMESPACE = "BidirectionalTTS"
        private const val SAMPLE_RATE = 24000
    }

    /** 豆包 TTS 的接入参数（都在设置里填）。 */
    data class Config(
        val appId: String,
        val accessToken: String,
        val speaker: String,
        val resourceId: String,
    ) {
        fun isComplete(): Boolean =
            appId.isNotBlank() && accessToken.isNotBlank() && speaker.isNotBlank()
    }

    /** 开始出声（主线程回调）。 */
    var onStart: (() -> Unit)? = null

    /** 一段话播完了（主线程回调）。 */
    var onDone: (() -> Unit)? = null

    /** 出错，msg 是可以直接显示给人看的话。 */
    var onError: ((String) -> Unit)? = null

    /** 播放音量 0~1，用来驱动 Live2D 口型。 */
    var onLevel: ((Float) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接不能有读超时
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    private var speaking = false

    private var sessionId: String? = null
    private var uid: String? = null
    private var pendingText: String? = null
    private var pendingConfig: Config? = null

    // ---- 音频播放 ----
    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val poison = ByteArray(0)

    /** 本次朗读累计收到的音频（成功路径也要有日志，出问题才知道卡在哪一步）。 */
    private var audioBytes = 0L
    private var audioChunks = 0

    fun isSpeaking(): Boolean = speaking

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** 朗读一段文本。会打断上一段（barge-in）。 */
    fun speak(text: String, config: Config) {
        val clean = text.trim()
        if (clean.isEmpty() || !config.isComplete()) return

        stop()

        speaking = true
        sessionId = UUID.randomUUID().toString()
        uid = UUID.randomUUID().toString()
        pendingText = clean
        pendingConfig = config
        audioBytes = 0
        audioChunks = 0

        Log.d(TAG, "开始朗读 ${clean.length} 字：speaker=${config.speaker} resource=${config.resourceId}")

        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer;${config.accessToken}")
            .header("X-Api-App-Key", config.appId)
            .header("X-Api-Access-Key", config.accessToken)
            .header("X-Api-Resource-Id", config.resourceId)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        Log.d(TAG, "连接豆包 TTS：speaker=${config.speaker} resource=${config.resourceId}")
        webSocket = http.newWebSocket(request, listener)
    }

    /** 打断当前朗读并释放播放器。 */
    fun stop() {
        val wasSpeaking = speaking
        speaking = false

        webSocket?.let {
            runCatching { it.close(1000, "bye") }
        }
        webSocket = null

        audioQueue.clear()
        // ⚠️ 毒丸只在「已经有播放线程在阻塞等待」时才需要，用来把它唤醒。
        // 早先无条件塞一颗，结果 speak() 开头的 stop() 留下一颗毒丸，
        // 等第一段音频到达、播放线程刚启动就把它取走 → 线程立刻退出 → 全程没声音也不报错。
        val thread = playThread
        if (thread != null) {
            audioQueue.offer(poison)
            thread.join(500)
        }
        playThread = null

        audioTrack?.let {
            runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() }
            runCatching { it.release() }
        }
        audioTrack = null

        if (wasSpeaking) {
            main {
                onLevel?.invoke(0f)
                onDone?.invoke()
            }
        }
    }

    fun release() = stop()

    // ------------------------------------------------------------------
    // WebSocket 回调
    // ------------------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "已连接，logid=${response.header("X-Tt-Logid")}")
            ws.send(ByteString.of(*buildFrame(EV_START_CONNECTION, null, "{}")))
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            handleFrame(ws, bytes.toByteArray())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.w(TAG, "收到意外的文本消息：$text")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 失败", t)
            // 服务端错误体信息量很大，比如：
            //   {"error":"load grant: requested grant not found in SaaS storage"}
            //   {"error":"app key not found in header or query"}
            val serverBody = runCatching { response?.body?.string()?.take(200) }.getOrNull()
            val hint = when {
                serverBody == null -> ""
                serverBody.contains("not granted") ->
                    "（这个资源 ID 没给该应用开通：小模型/复刻音色试 volc.megatts.default，" +
                        "大模型试 volc.service_type.10029，2.0 试 seed-tts-2.0）"
                serverBody.contains("grant not found") ->
                    "（App ID 与 Access Token 必须来自同一个应用，且令牌重新生成过旧的就失效了）"
                else -> ""
            }
            val detail = when {
                response == null -> t.message ?: "未知错误"
                response.code == 401 || response.code == 403 ->
                    "HTTP ${response.code} 鉴权被拒，logid=${response.header("X-Tt-Logid")}" +
                        (if (!serverBody.isNullOrBlank()) "，服务端说明：$serverBody" else "") + hint
                else -> "HTTP ${response.code}，logid=${response.header("X-Tt-Logid")}" +
                    (if (!serverBody.isNullOrBlank()) "，服务端说明：$serverBody" else "") + hint
            }
            speaking = false
            main {
                onLevel?.invoke(0f)
                onError?.invoke("语音服务连接失败：$detail")
                onDone?.invoke()
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket 关闭 code=$code reason=$reason")
        }
    }

    private fun handleFrame(ws: WebSocket, data: ByteArray) {
        if (data.size < 8) return
        val msgType = data[1].toInt() and 0xFF
        val event = readInt(data, 4)

        when (msgType) {
            MSG_AUDIO_ONLY -> {
                // 音频帧：头(4)+event(4)+sid长度(4)+sid+payload长度(4)+音频
                var offset = 8
                if (data.size >= 12) {
                    val sidLen = readInt(data, 8)
                    // 我们的 session_id 是 UUID（36 字符），据此校验，
                    // 不匹配就当作这帧没有 session_id（长度字段就在 8..12）
                    if (sidLen == 36 && data.size >= 12 + sidLen) {
                        offset = 12 + sidLen
                    }
                }
                if (data.size <= offset) return

                var audioStart = offset
                if (data.size >= offset + 4) {
                    val payloadLen = readInt(data, offset)
                    if (payloadLen > 0 && offset + 4 + payloadLen <= data.size) {
                        audioStart = offset + 4
                    }
                    // payloadLen 不合理就认为这帧没带长度字段，从 offset 开始全是音频
                }
                if (audioStart >= data.size) return
                val pcm = data.copyOfRange(audioStart, data.size)
                if (pcm.isNotEmpty()) enqueueAudio(pcm)
            }

            MSG_FULL_SERVER -> {
                val payload = extractJsonPayload(data, event)
                when (event) {
                    EV_CONNECTION_STARTED -> sendStartSession(ws)
                    EV_SESSION_STARTED -> {
                        main { onStart?.invoke() }
                        sendTaskRequest(ws)
                    }
                    EV_SESSION_FINISHED -> {
                        ws.send(ByteString.of(*buildFrame(EV_FINISH_CONNECTION, null, "{}")))
                        Log.d(
                            TAG,
                            "合成完成：${audioChunks} 块 / ${audioBytes} 字节 ≈ " +
                                "${audioBytes / 2f / SAMPLE_RATE} 秒",
                        )
                        speaking = false
                        audioQueue.offer(poison)
                        main {
                            onLevel?.invoke(0f)
                            onDone?.invoke()
                        }
                    }
                    EV_TTS_SENTENCE_START, EV_TTS_SENTENCE_END, EV_TTS_RESPONSE -> Unit
                    EV_CONNECTION_FAILED, EV_SESSION_FAILED -> reportServerError(payload, event)
                    else -> {
                        // 服务端报错也走 0x94：payload 里通常有 code/message
                        if (payload != null && (payload.contains("\"code\"") || payload.contains("message"))) {
                            reportServerError(payload, event)
                        } else {
                            Log.d(TAG, "未知事件 $event，payload=${payload?.take(200)}")
                        }
                    }
                }
            }

            else -> Log.d(TAG, "未知消息类型 0x${Integer.toHexString(msgType)}")
        }
    }

    private fun sendStartSession(ws: WebSocket) {
        val cfg = pendingConfig ?: return
        val payload = JSONObject().apply {
            put("user", JSONObject().put("uid", uid))
            put("event", EV_START_SESSION)
            put("namespace", NAMESPACE)
            put(
                "req_params",
                JSONObject().apply {
                    put("speaker", cfg.speaker)
                    put(
                        "audio_params",
                        JSONObject().apply {
                            put("format", "pcm")
                            put("sample_rate", SAMPLE_RATE)
                        },
                    )
                },
            )
        }
        ws.send(ByteString.of(*buildFrame(EV_START_SESSION, sessionId, payload.toString())))
        Log.d(TAG, "已发 StartSession")
    }

    private fun sendTaskRequest(ws: WebSocket) {
        val cfg = pendingConfig ?: return
        val text = pendingText ?: return
        val payload = JSONObject().apply {
            put("user", JSONObject().put("uid", uid))
            put("event", EV_TASK_REQUEST)
            put("namespace", NAMESPACE)
            put(
                "req_params",
                JSONObject().apply {
                    put("speaker", cfg.speaker)
                    put(
                        "audio_params",
                        JSONObject().apply {
                            put("format", "pcm")
                            put("sample_rate", SAMPLE_RATE)
                        },
                    )
                    put("text", text)
                },
            )
        }
        ws.send(ByteString.of(*buildFrame(EV_TASK_REQUEST, sessionId, payload.toString())))
        ws.send(ByteString.of(*buildFrame(EV_FINISH_SESSION, sessionId, "{}")))
        Log.d(TAG, "已发 TaskRequest（${text.length} 字）+ FinishSession")
    }

    private fun reportServerError(payload: String?, event: Int) {
        val msg = try {
            val json = JSONObject(payload ?: "{}")
            val code = json.optInt("code", 0)
            val message = json.optString("message", "")
            "语音服务报错（event $event）：$code ${if (message.isBlank()) payload else message}"
        } catch (t: Throwable) {
            "语音服务报错（event $event）：${payload?.take(200)}"
        }
        Log.e(TAG, msg)
        speaking = false
        audioQueue.offer(poison)
        main {
            onLevel?.invoke(0f)
            onError?.invoke(msg)
            onDone?.invoke()
        }
    }

    // ------------------------------------------------------------------
    // 帧编解码
    // ------------------------------------------------------------------

    private fun buildFrame(event: Int, sessionId: String?, payloadJson: String): ByteArray {
        val payload = payloadJson.toByteArray(Charsets.UTF_8)
        val sid = sessionId?.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        out.write(PROTOCOL_VERSION)
        out.write(MSG_FULL_CLIENT)
        out.write(SERIALIZATION_JSON or NO_COMPRESSION)
        out.write(0x00)
        writeInt(out, event)
        if (sid != null) {
            writeInt(out, sid.size)
            out.write(sid)
        }
        writeInt(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * 从服务端 JSON 帧里取 payload。
     *
     * ⚠️ 关键：**连接级事件（1/2/50/51/52）没有 session_id 字段**，
     * 帧结构直接是 头(4) + event(4) + payload长度(4) + payload。
     * 早先没区分这一点，会把 payload 的头几个字节当成 session_id 读走，
     * 导致后面 StartSession 带着一个假的 session_id 发出去、被服务端拒绝。
     */
    private fun extractJsonPayload(data: ByteArray, event: Int): String? {
        var offset = 8
        val sessionLevel = event >= 100
        if (sessionLevel && data.size >= 12) {
            val sidLen = readInt(data, 8)
            if (sidLen in 0..1024 && data.size >= 12 + sidLen) {
                if (sidLen > 0) sessionId = String(data, 12, sidLen, Charsets.UTF_8)
                offset = 12 + sidLen
            }
        }
        if (data.size < offset + 4) return null
        val len = readInt(data, offset)
        offset += 4
        if (len <= 0 || data.size < offset + len) return null
        return String(data, offset, len, Charsets.UTF_8)
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (data.size < offset + 4) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    // ------------------------------------------------------------------
    // 音频播放（独立线程，边收边放）
    // ------------------------------------------------------------------

    private fun enqueueAudio(pcm: ByteArray) {
        if (!speaking) return
        audioBytes += pcm.size
        audioChunks++
        if (audioChunks == 1) {
            Log.d(TAG, "收到首块音频 ${pcm.size} 字节，开始播放")
        }
        if (audioTrack == null) startPlayer()
        audioQueue.offer(pcm)
    }

    private fun startPlayer() {
        // 启动前清空：把可能残留的毒丸/旧数据丢掉，保证线程第一个取到的就是音频
        audioQueue.clear()

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 用媒体音量通道：部分机型上 USAGE_ASSISTANT 走的是"助手音量"，
                    // 那条通道音量常常是 0，表现就是"播放正常但没声音"
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 初始化失败，state=${track.state}")
            main { onError?.invoke("音频播放器初始化失败（state=${track.state}）") }
            return
        }

        track.setVolume(1.0f)
        track.play()
        audioTrack = track
        Log.d(TAG, "播放器已启动：${SAMPLE_RATE}Hz 单声道，缓冲 ${maxOf(minBuf * 2, SAMPLE_RATE)} 字节")

        playThread = Thread({
            var written = 0L
            while (true) {
                val chunk = try {
                    audioQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (chunk === poison) break
                if (chunk.isEmpty()) continue

                runCatching { track.write(chunk, 0, chunk.size) }
                written += chunk.size
                if (written == chunk.size.toLong()) {
                    Log.d(TAG, "首块音频已写入播放器（${chunk.size} 字节）")
                }

                // 顺便算个音量给口型用
                var sum = 0.0
                var i = 0
                while (i + 1 < chunk.size) {
                    val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                    sum += sample.toDouble() * sample.toDouble()
                    i += 2
                }
                val samples = (chunk.size / 2).coerceAtLeast(1)
                val rms = sqrt(sum / samples) / 32768.0
                val level = (rms * 6).coerceIn(0.0, 1.0).toFloat()
                main { onLevel?.invoke(level) }
            }
            Log.d(TAG, "播放线程结束，共写入 $written 字节")
        }, "dsai-tts-play").apply { start() }
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
