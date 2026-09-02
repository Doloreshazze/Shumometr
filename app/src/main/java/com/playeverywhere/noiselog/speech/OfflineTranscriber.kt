package com.playeverywhere.noiselog.speech

import android.content.Context
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
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
    @Volatile private var closed = false

    fun prepare() {
        if (closed || !ModelManager.isReady(appContext)) return
        try {
            executor.execute {
                try {
                    if (recognizer == null) recognizer = createRecognizer()
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to prepare speech model", error)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun submit(samples: ShortArray, callback: (Result) -> Unit) {
        if (closed || !ModelManager.isReady(appContext) || samples.isEmpty()) return
        try {
            executor.execute {
                try {
                    val engine = recognizer ?: createRecognizer().also { recognizer = it }
                    val stream = engine.createStream()
                    try {
                        val normalized = FloatArray(samples.size) { index ->
                            samples[index].toFloat() / 32768f
                        }
                        stream.acceptWaveform(normalized, 16_000)
                        engine.decode(stream)
                        val result = engine.getResult(stream)
                        val text = result.text.trim()
                        if (text.isNotEmpty()) callback(Result(text, result.lang.orEmpty()))
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

    private fun createRecognizer(): OfflineRecognizer {
        val modelConfig = OfflineModelConfig(
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                model = ModelManager.modelFile(appContext).absolutePath
            ),
            tokens = ModelManager.tokensFile(appContext).absolutePath,
            numThreads = 1,
            debug = false
        )
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
            }
        }, "NoiseLog-Transcriber-Cleanup").start()
    }
}
