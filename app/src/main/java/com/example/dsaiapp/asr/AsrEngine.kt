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
         * 想更灵敏就调小（比如 1200），想更宽容就调大。
         */
        private const val SILENCE_MS = 2000

        /** RMS 低于这个值算「没声音」。安静环境约 0.005，底噪大的环境要调高。 */
        private const val SILENCE_RMS = 0.012

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

    private var recognizer: OfflineRecognizer? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    /** 录音分块累积（解码时拼起来）。 */
    private val chunks = ArrayList<FloatArray>()
    private var sampleCount = 0

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

        chunks.clear()
        sampleCount = 0
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
            post { onError?.invoke("没录到声音呀，再试一次～") }
            return
        }
        val samples = FloatArray(total)
        var offset = 0
        for (c in chunks) {
            val n = minOf(c.size, total - offset)
            if (n <= 0) break
            System.arraycopy(c, 0, samples, offset, n)
            offset += n
        }
        chunks.clear()
        sampleCount = 0

        Log.d(TAG, "停止录音，共 ${total / SAMPLE_RATE.toFloat()} 秒，开始解码")

        Thread({ decode(samples) }, "dsai-asr-dec").start()
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

            val rms = sqrt(sum / n) / 32768.0
            val level = (rms * 12).coerceIn(0.0, 1.0).toFloat()
            post { onLevel?.invoke(level) }

            // ---- 电话式断句：连续静音 2 秒 = 这句说完了 ----
            val blockMs = n * 1000L / SAMPLE_RATE
            if (rms > SILENCE_RMS) {
                if (!speechDetected) {
                    speechDetected = true
                    Log.d(TAG, "检测到说话")
                }
                silenceMs = 0
            } else {
                silenceMs += blockMs
            }

            if (silenceMs >= SILENCE_MS && sampleCount >= minSamples) {
                Log.d(TAG, "静音 ${silenceMs}ms（说了话=$speechDetected），自动结束这一句")
                post { onAutoStop?.invoke() }
                break
            }

            if (sampleCount >= maxSamples) {
                Log.d(TAG, "达到 ${MAX_SECONDS}s 上限，自动停止")
                post {
                    onError?.invoke("录满 ${MAX_SECONDS} 秒了，先帮你转成文字～")
                    onAutoStop?.invoke()
                }
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
                    onError?.invoke("没听清，再说一次试试？")
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
