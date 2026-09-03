package com.playeverywhere.noiselog.speech

import android.content.Context
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class OfflineTranscriber(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "ShumometrRecognition"
    }

    data class Result(val text: String, val language: String)

    private val appContext = context.applicationContext
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        // One phrase may wait while one is decoded. Keeping a larger backlog
        // only increases memory and makes results arrive many seconds late.
        ArrayBlockingQueue(1),
        { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                runnable.run()
            }, "NoiseLog-Transcriber")
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recognizerLanguage = RecognitionLanguage.AUTO
    @Volatile private var closed = false

    fun prepare(language: String = RecognitionLanguage.AUTO) {
        if (closed || !ModelManager.isReady(appContext, language)) return
        try {
            executor.execute {
                try {
                    ensureRecognizer(language)
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to prepare speech model", error)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun submit(samples: ShortArray, language: String, callback: (Result) -> Unit) {
        if (closed || !ModelManager.isReady(appContext, language) || samples.isEmpty()) return
        try {
            executor.execute {
                try {
                    val engine = ensureRecognizer(language)
                    val stream = engine.createStream()
                    try {
                        val normalized = FloatArray(samples.size) { index ->
                            samples[index].toFloat() / 32768f
                        }
                        stream.acceptWaveform(normalized, 16_000)
                        engine.decode(stream)
                        val result = engine.getResult(stream)
                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            val detectedOrSelected = result.lang.orEmpty().ifBlank { language }
                            callback(Result(text, detectedOrSelected))
                        }
                    } finally {
                        stream.release()
                    }
                } catch (error: Throwable) {
                    // Measurement must continue even if a device cannot load the
                    // large optional model. The UI reports model state separately.
                    Log.e(TAG, "Speech decoding failed", error)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The service is stopping; dropping this last phrase is preferable
            // to blocking the UI while the native recognizer shuts down.
        }
    }

    private fun ensureRecognizer(language: String): OfflineRecognizer {
        val normalized = language.takeIf(RecognitionLanguage::isSupported) ?: RecognitionLanguage.AUTO
        recognizer?.takeIf { recognizerLanguage == normalized }?.let { return it }
        recognizer?.release()
        recognizer = null
        return createRecognizer(normalized).also {
            recognizerLanguage = normalized
            recognizer = it
        }
    }

    private fun createRecognizer(language: String): OfflineRecognizer {
        val targeted = language.isNotBlank()
        val modelConfig = if (targeted) {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = ModelManager.whisperEncoderFile(appContext).absolutePath,
                    decoder = ModelManager.whisperDecoderFile(appContext).absolutePath,
                    language = language,
                    task = "transcribe"
                ),
                tokens = ModelManager.whisperTokensFile(appContext).absolutePath,
                modelType = "whisper",
                numThreads = 1,
                debug = false
            )
        } else {
            OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = ModelManager.omnilingualModelFile(appContext).absolutePath
                ),
                tokens = ModelManager.omnilingualTokensFile(appContext).absolutePath,
                numThreads = 1,
                debug = false
            )
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.queue.clear()
        executor.shutdown()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            // Native decoding is not safely interruptible. RecognitionService
            // runs in a disposable process, so never keep a cleanup thread alive
            // forever when a vendor runtime gets stuck.
            if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
                recognizer?.release()
                recognizer = null
                recognizerLanguage = RecognitionLanguage.AUTO
            }
        }, "NoiseLog-Transcriber-Cleanup").start()
    }
}
