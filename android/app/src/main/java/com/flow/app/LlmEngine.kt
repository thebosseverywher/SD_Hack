package com.flow.app

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around MediaPipe Tasks-GenAI's [LlmInference] for on-device,
 * grounded RAG generation (spec §1.4). Loaded lazily and at most once; if no
 * model file is present, or the engine fails to initialize, the wrapper stays
 * [available] == false and the caller (Inference.generate) falls back to its
 * deterministic stub.
 *
 * Model file resolution order (NEVER bundled in the APK):
 *   1. {filesDir}/llm/model.task          (runtime-provisioned, preferred)
 *   2. /data/local/tmp/flow/llm/model.task (dev fallback via `adb push`)
 *
 * The MediaPipe API is async (generateResponseAsync + ProgressListener). We bridge
 * it to the synchronous, streaming generate() contract Ask.kt depends on using a
 * CountDownLatch — so this MUST be called off the main thread (Ask runs on IO).
 */
class LlmEngine private constructor(private val engine: LlmInference) {

    /** True once a usable engine has been constructed. */
    val available: Boolean get() = true

    /**
     * Stream a completion for [prompt]. [partialDeltas] are INCREMENTAL (not
     * cumulative). [maxTokens] is enforced client-side (the engine's context budget
     * is fixed at init); [stop] sequences are scanned against the accumulated buffer.
     *
     * @return number of partial chunks emitted (used for a tokens/sec readout).
     * @throws Throwable if generation fails — caller decides on stub fallback.
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        stop: List<String>,
        onToken: (String) -> Unit
    ): Int {
        var emitted = 0
        val sb = StringBuilder()
        val stopped = AtomicReference(false)
        val latch = CountDownLatch(1)

        engine.generateResponseAsync(prompt) { partialResult: String, done: Boolean ->
            if (!stopped.get() && partialResult.isNotEmpty()) {
                sb.append(partialResult)
                // Client-side stop-sequence enforcement: emit only up to the stop marker.
                val cut = stop.firstOrNull { it.isNotEmpty() && sb.contains(it) }
                if (cut != null) {
                    val safeDelta = partialResult.substringBefore(cut)
                    if (safeDelta.isNotEmpty()) {
                        onToken(safeDelta)
                        emitted++
                    }
                    stopped.set(true)
                } else {
                    onToken(partialResult)
                    emitted++
                    // Client-side maxTokens cap (API has no per-call limit).
                    if (emitted >= maxTokens) stopped.set(true)
                }
            }
            if (done || stopped.get()) latch.countDown()
        }
        // Block this (background) thread until generation completes / is stopped.
        latch.await()
        return emitted
    }

    fun close() {
        try {
            engine.close()
        } catch (t: Throwable) {
            Log.w(TAG, "LLM close failed", t)
        }
    }

    companion object {
        private const val TAG = "LlmEngine"

        /** Resolve the model file: filesDir/llm/model.task, then /data/local/tmp/...task. */
        fun resolveModelFile(context: Context): File? {
            val candidates = listOf(
                File(context.filesDir, "llm/model.task"),
                File("/data/local/tmp/flow/llm/model.task")
            )
            return candidates.firstOrNull { it.exists() && it.length() > 0L }
        }

        /**
         * Build the engine once. Returns null (gracefully) if no model file is found
         * or [LlmInference.createFromOptions] throws (corrupt/incompatible .task, OOM,
         * unsupported arch). The caller then uses the deterministic stub.
         */
        fun createOrNull(context: Context): LlmEngine? {
            return try {
                val f = resolveModelFile(context) ?: run {
                    Log.i(TAG, "No LLM model file found; using stub generation.")
                    return null
                }
                val opts = LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(1024)   // total context budget (prompt + output), fixed at init
                    .setMaxTopK(64)       // upper bound for sampling
                    .build()
                val inf = LlmInference.createFromOptions(context, opts)
                Log.i(TAG, "LLM engine initialized from ${f.absolutePath} (${f.length()} bytes).")
                LlmEngine(inf)
            } catch (t: Throwable) {
                Log.w(TAG, "LLM init failed, using stub", t)
                null
            }
        }
    }
}
