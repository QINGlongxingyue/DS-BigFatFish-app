package com.example.dsaiapp.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlin.math.sqrt

/**
 * 本地离线语音识别：**阿里达摩院 FunASR 的 Paraformer 中文模型**，
 * 跑在 sherpa-onnx 运行时上（模型和推理全部在本机，绝不联网）。
 *
 * <p>Paraformer 是「非流式」模型：录完整段再解码，所以流程是
 * 按下电话图标开始录音 → 再按一下停止 → 解码出文字。
 */
class AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "DSAI-ASR"
        private const val SAMPLE_RATE = 16000

        private const val MODEL = "asr/model.int8.onnx"
        private const val TOKENS = "asr/tokens.txt"

        /** 录音上限 60 秒，防止忘了关把内存撑爆（16k 单声道 60s ≈ 1.9MB）。 */
        private const val MAX_SECONDS = 60

        /**
         * 电话式自动断句：连续静音这么久就认为这一句说完了，自动结束并识别。
         * 想更灵敏就调小，想更宽容就调大。
         */
        private const val SILENCE_MS = 2500

        /** RMS 低于这个值算「没声音」。 */
        private const val SILENCE_RMS = 0.012

        /**
         * 动态范围下限：整段的**峰值**至少要是**平均值**的这么多倍。
         *
         * <p>只靠音量高低分不开"稳态底噪"和"人说话"：风扇/空调的电平又稳又平，
         * 峰值≈平均值；人说话有音节起伏和换气停顿，峰值往往是平均值的 3~10 倍。
         * 有了这一条，音量门槛就能压得比较低（小声说话也能过），而不怕底噪混进来。
         */
        private const val MIN_DYNAMIC_RANGE = 2.0

        /** 超过这么久的长录音不做动态范围判断（可能是连续说了很久）。 */
        private const val DYNAMIC_RANGE_MAX_MS = 20_000L

        /**
         * 说话判定的音量门槛，按灵敏度分档。
         *
         * <p>⚠️ 注意：**判"有声音"和判"说话时长"必须用同一个门槛**。
         * 早先前者用 0.012、后者用 0.025，于是音量落在中间的人（手机麦克风增益低时很常见）
         * 会陷入"静音计时被不断重置 → 永远不断句 → 到 60 秒上限 → 整段当噪音丢掉"，
         * 表现就是"正常说话却毫无反应"。
         *
         * <p>对着手机正常说话的 RMS 大致 0.02~0.25；笔记本风扇/空调底噪常在 0.005~0.03。
         * 所以留了三档给用户按自己设备选。
         */
        private val SPEECH_RMS_BY_LEVEL = floatArrayOf(
            0.006f, // 灵敏：小声说话 / 麦克风增益低
            0.012f, // 标准
            0.030f, // 抗噪：环境吵（风扇、空调、街边）
        )

        /** 灵敏度档位：0=灵敏 1=标准 2=抗噪。 */
        const val SENSITIVITY_SENSITIVE = 0
        const val SENSITIVITY_NORMAL = 1
        const val SENSITIVITY_NOISY = 2

        /** 一段话里至少要有这么久超过门槛，否则整段当噪音丢掉（短促的"嗯/好"也能过）。 */
        private const val MIN_VOICE_MS = 180L

        /** 至少录到这么久才允许被静音判定结束，免得刚按下就立刻收尾。 */
        private const val MIN_RECORD_MS = 600
    }

    /** 识别出的一句话（主线程回调）。 */
    var onResult: ((String) -> Unit)? = null

    /** 出错提示（主线程回调）。 */
    var onError: ((String) -> Unit)? = null

    /** 录音电平 0~1，给按钮做呼吸效果（主线程回调）。 */
    var onLevel: ((Float) -> Unit)? = null

    /** 检测到「说完了」（连续静音到点）时回调，UI 收到后走正常的收尾流程。 */
    var onAutoStop: (() -> Unit)? = null

    /** 识别结果为空（没听清）。电话模式下这不算错误，接着听就行。 */
    var onEmpty: (() -> Unit)? = null

    /** 灵敏度档位（0=灵敏 1=标准 2=抗噪），由设置界面写入。 */
    @Volatile
    var sensitivity: Int = SENSITIVITY_NORMAL

    /** 当前生效的说话门槛。 */
    private fun speechRms(): Double =
        SPEECH_RMS_BY_LEVEL[sensitivity.coerceIn(0, SPEECH_RMS_BY_LEVEL.size - 1)].toDouble()

    private var recognizer: OfflineRecognizer? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    /** 录音分块累积（解码时拼起来）。 */
    private val chunks = ArrayList<FloatArray>()
    private var sampleCount = 0

    /** 当前这段录音里，电平超过「说话下限」的累计时长（毫秒）。 */
    @Volatile
    private var voicedMs = 0L

    /** 当前这段录音的电平峰值（用来给用户调阈值：风扇底噪到底多响）。 */
    @Volatile
    private var peakRms = 0.0

    /** 当前这段录音的平方和（算整段平均值用，配合峰值判动态范围）。 */
    private var sumSquares = 0.0

    @Volatile
    private var running = false

    /** 模型是否已加载（加载一次约 1~3 秒）。 */
    @Volatile
    var isReady = false
        private set

    /** 正在录音。 */
    @Volatile
    var isRecording = false
        private set

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** 加载模型。**必须在工作线程调用**。 */
    fun prepare(): Boolean {
        if (isReady) return true
        return try {
            val started = System.currentTimeMillis()

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = MODEL),
                    tokens = TOKENS,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                    modelType = "paraformer",
                ),
            )

            recognizer = OfflineRecognizer(context.assets, config)
            isReady = true
            Log.d(TAG, "Paraformer 模型加载完成，耗时 ${System.currentTimeMillis() - started} ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ASR 模型加载失败", t)
            post { onError?.invoke("语音模型加载失败：${t.message}") }
            false
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 录音
    // ------------------------------------------------------------------

    /** 开始录音（不阻塞，音频在后台线程读取）。 */
    fun start() {
        if (isRecording) return
        if (!isReady && !prepare()) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2,
            )
        } catch (t: Throwable) {
            post { onError?.invoke("录音初始化失败：${t.message}") }
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            post { onError?.invoke("麦克风不可用（可能被别的应用占用）") }
            return
        }

        resetBuffers()
        recorder = rec
        running = true
        isRecording = true
        rec.startRecording()

        worker = Thread({ recordLoop(rec, bufSize) }, "dsai-asr-rec").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.d(TAG, "开始录音")
    }

    /**
     * 停止录音并解码。
     *
     * <p>解码要跑神经网络，放到后台线程，出结果后回调 [onResult]。
     */
    fun stopAndDecode() {
        if (!isRecording) return
        isRecording = false
        running = false
        worker?.join(1500)
        worker = null

        recorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Throwable) {
            }
            it.release()
        }
        recorder = null

        // 攒到的音频拼成一整段
        val total = sampleCount
        if (total <= 0) {
            resetBuffers()
            post { onEmpty?.invoke() }
            return
        }

        // 音量门槛：整段里"像人说话"的时间太短，直接当噪音丢掉，既省算力也不会发出去。
        // （风扇、空调、键盘这类底噪会被 Paraformer 幻觉成中文，必须在这里挡住）
        val voiced = voicedMs
        val gate = speechRms()
        val totalMs = total * 1000L / SAMPLE_RATE
        val avgRms = sqrt(sumSquares / total) / 32768.0
        val dynamicRange = if (avgRms > 1e-6) peakRms / avgRms else 0.0
        // 长时间连续录音（比如一口气说半分钟）不做动态范围判断，避免误伤
        val dynamicOk = totalMs > DYNAMIC_RANGE_MAX_MS || dynamicRange >= MIN_DYNAMIC_RANGE

        if (voiced < MIN_VOICE_MS || !dynamicOk) {
            Log.d(
                TAG,
                "当噪音丢掉：说话累计 ${voiced}ms（下限 $MIN_VOICE_MS），" +
                    "峰值 ${"%.4f".format(peakRms)} / 平均 ${"%.4f".format(avgRms)} " +
                    "= 动态 ${"%.2f".format(dynamicRange)}（下限 $MIN_DYNAMIC_RANGE），门槛 ${"%.4f".format(gate)}",
            )
            resetBuffers()
            post { onEmpty?.invoke() }
            return
        }
        Log.d(
            TAG,
            "判定有人在说话：累计 ${voiced}ms，峰值 ${"%.4f".format(peakRms)}，" +
                "动态 ${"%.2f".format(dynamicRange)}，门槛 ${"%.4f".format(gate)}",
        )

        val samples = FloatArray(total)
        var offset = 0
        for (c in chunks) {
            val n = minOf(c.size, total - offset)
            if (n <= 0) break
            System.arraycopy(c, 0, samples, offset, n)
            offset += n
        }
        resetBuffers()

        Log.d(TAG, "停止录音，共 ${total / SAMPLE_RATE.toFloat()} 秒（说话 $voiced ms），开始解码")

        Thread({ decode(samples) }, "dsai-asr-dec").start()
    }

    /** 清掉当前这段录音的缓冲。 */
    private fun resetBuffers() {
        chunks.clear()
        sampleCount = 0
        voicedMs = 0
        peakRms = 0.0
        sumSquares = 0.0
    }

    /** 丢掉当前录音，不解码。 */
    fun cancel() {
        if (!isRecording) return
        isRecording = false
        running = false
        worker?.join(1500)
        worker = null
        recorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Throwable) {
            }
            it.release()
        }
        recorder = null
        chunks.clear()
        sampleCount = 0
    }

    fun release() {
        cancel()
        recognizer?.release()
        recognizer = null
        isReady = false
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun recordLoop(rec: AudioRecord, bufSize: Int) {
        val shorts = ShortArray(bufSize)
        val maxSamples = SAMPLE_RATE * MAX_SECONDS
        val minSamples = SAMPLE_RATE * MIN_RECORD_MS / 1000

        /** 连续静音累计时长（毫秒），有声音就清零。 */
        var silenceMs = 0L
        var speechDetected = false

        while (running) {
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) {
                if (n < 0) {
                    post { onError?.invoke("录音读取失败（code $n）") }
                    break
                }
                continue
            }

            val block = FloatArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val v = shorts[i]
                block[i] = v / 32768.0f
                sum += (v.toDouble() * v.toDouble())
            }
            chunks.add(block)
            sampleCount += n
            sumSquares += sum

            val rms = sqrt(sum / n) / 32768.0
            val level = (rms * 12).coerceIn(0.0, 1.0).toFloat()
            post { onLevel?.invoke(level) }

            // ---- 单一门槛同时决定「有没有在说」和「说了多久」----
            // 用两个不同阈值会让中间那段音量永远不断句、最后又被丢掉（见 SPEECH_RMS_BY_LEVEL 注释）
            val gate = speechRms()
            val blockMs = n * 1000L / SAMPLE_RATE
            if (rms > gate) {
                if (!speechDetected) {
                    speechDetected = true
                    Log.d(TAG, "检测到说话（RMS ${"%.4f".format(rms)} > 门槛 ${"%.4f".format(gate)}）")
                }
                silenceMs = 0
                voicedMs += blockMs
            } else {
                silenceMs += blockMs
            }
            if (rms > peakRms) peakRms = rms

            if (silenceMs >= SILENCE_MS && sampleCount >= minSamples) {
                Log.d(
                    TAG,
                    "静音 ${silenceMs}ms（有说话=$speechDetected，说话累计 ${voicedMs}ms，" +
                        "峰值 ${"%.4f".format(peakRms)} / 门槛 ${"%.4f".format(gate)}），自动结束这一句",
                )
                post { onAutoStop?.invoke() }
                break
            }

            if (sampleCount >= maxSamples) {
                Log.d(TAG, "达到 ${MAX_SECONDS}s 上限，自动停止")
                post { onAutoStop?.invoke() }
                break
            }
        }
    }

    private fun decode(samples: FloatArray) {
        val r = recognizer
        if (r == null) {
            post { onError?.invoke("识别引擎没准备好") }
            return
        }
        try {
            val started = System.currentTimeMillis()
            val stream = r.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()

            Log.d(TAG, "解码完成（${System.currentTimeMillis() - started} ms）: $text")
            post {
                if (text.isEmpty()) {
                    // 没听清不是错误：电话模式下接着听下一句就行
                    onEmpty?.invoke()
                } else {
                    onResult?.invoke(text)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "解码失败", t)
            post { onError?.invoke("识别出错：${t.message}") }
        }
    }

    private fun post(block: () -> Unit) {
        Handler(context.mainLooper).post(block)
    }
}
