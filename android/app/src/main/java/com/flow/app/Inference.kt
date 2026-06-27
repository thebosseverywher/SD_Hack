package com.flow.app

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

/**
 * On-device inference layer (spec §1).
 *
 * Every method here is a clearly-marked TODO stub that returns a DETERMINISTIC
 * placeholder so the rest of the app (index, federation, UI) is coherent and
 * testable before the real models are wired. Replace the bodies with ONNX Runtime
 * + QNN Execution Provider calls; the public signatures are the integration contract.
 *
 * == INTEGRATION POINTS (ONNX Runtime Android + QNN EP) ==
 *  1. Add the dependency in app/build.gradle.kts:
 *       implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
 *     (for the NPU/HTP EP you need a QNN-enabled ORT build — vendored .aar or the
 *      Qualcomm AI Hub / QNN SDK delegate libs.)
 *  2. Compile the models via Qualcomm AI Hub to QNN context binaries / ONNX
 *     (spec §0.4) and ship them under app/src/main/assets/models/.
 *  3. Build a session per model with the chosen EP (spec §1.0):
 *       val opts = OrtSession.SessionOptions()
 *       opts.addQnn(mapOf("backend_path" to "libQnnHtp.so"))   // NPU
 *       // or run on CPU for the telemetry NPU↔CPU toggle (spec §6.3)
 *     and record last-run latency for the telemetry panel (spec §0.6).
 */

/** Which execution provider a model runs on. Powers the telemetry NPU↔CPU toggle. */
enum class ExecutionProvider { QNN_NPU, CPU }

/** A single timed inference event for the telemetry feed (spec §0.6 / §6.3). */
data class InferenceTiming(
    val stage: String,        // "embed_text" | "ocr" | "embed_image" | "classify" | "generate"
    val ep: ExecutionProvider,
    val ms: Double,
    val tokensPerSec: Double? = null
)

/** OCR output (spec §1.2). */
data class OcrLine(val text: String, val box: IntArray, val conf: Float)

/** Zero-shot CLIP type classification (spec §1.3). */
data class TypeGuess(val type: String, val score: Float)

/**
 * The inference façade. Construct once (e.g. from [IndexingService]) and reuse;
 * real implementations should warm up sessions in [warmUp].
 */
class Inference(
    private val context: Context,
    var preferredEp: ExecutionProvider = ExecutionProvider.QNN_NPU
) {
    /** Last timing per stage, for the telemetry panel. Thread-safe enough for a demo. */
    val lastTimings = mutableMapOf<String, InferenceTiming>()

    // ----------------------------------------------------------------------
    // On-device LLM (MediaPipe Tasks-GenAI). Lazily built at most once. null =>
    // unavailable (no model file / init failed) -> generate() uses the stub.
    // ----------------------------------------------------------------------
    private val llmLock = Any()
    @Volatile private var llmTried = false
    @Volatile private var llm: LlmEngine? = null
    /** Serializes generate(): an LlmInference instance allows only one generation at a time. */
    private val genLock = Any()

    /** True once the one-time LLM init attempt has finished (loaded a model OR fell back to stub). */
    @Volatile var llmReady: Boolean = false
        private set
    /** True when a REAL on-device model is loaded (vs. the deterministic stub fallback). */
    val llmModelLoaded: Boolean get() = llmTried && llm != null
    @Volatile private var warmStarted = false

    /** Build the LLM engine once; returns null (and stays null) if model missing / init fails. */
    private fun llmOrNull(): LlmEngine? {
        if (llmTried) return llm
        synchronized(llmLock) {
            if (llmTried) return llm
            llm = LlmEngine.createOrNull(context)
            llmTried = true
            return llm
        }
    }

    /**
     * Warm up the LLM OFF the main thread — loading the .task model is heavy (hundreds of MB).
     * [llmReady] flips true once the attempt completes; the UI gates the Ask screen on it so
     * a query is only allowed after the model has finished loading. Idempotent.
     */
    fun warmUp() {
        synchronized(llmLock) {
            if (warmStarted) return
            warmStarted = true
        }
        Thread({
            try { llmOrNull() } catch (_: Throwable) { /* createOrNull already guards */ }
            finally { llmReady = true }
        }, "flow-llm-warmup").start()
    }

    // ----------------------------------------------------------------------
    // 1.1 Text embedding service -> 384-d, L2-normalized.
    // ----------------------------------------------------------------------
    fun embedText(texts: List<String>): Array<FloatArray> {
        val out: Array<FloatArray>
        val ns = measureNanoTime {
            out = Array(texts.size) { i -> deterministicVector(texts[i], Config.TEXT_DIM) }
        }
        record("embed_text", ns)
        return out
    }

    fun embedText(text: String): FloatArray = embedText(listOf(text))[0]

    // ----------------------------------------------------------------------
    // 1.2 OCR service -> text + boxes + confidence.
    // ----------------------------------------------------------------------
    fun ocr(image: Bitmap): List<OcrLine> {
        var lines: List<OcrLine>
        val ns = measureNanoTime {
            // TODO: detection + recognition via ORT-QNN. Stub returns nothing so the
            // pipeline degrades gracefully (an item with empty OCR is simply not text-indexed).
            lines = emptyList()
        }
        record("ocr", ns)
        return lines
    }

    // ----------------------------------------------------------------------
    // 1.3 Image embedding + zero-shot type (CLIP) -> 512-d image vector.
    // ----------------------------------------------------------------------
    fun embedImage(image: Bitmap): FloatArray {
        val out: FloatArray
        val ns = measureNanoTime {
            // Deterministic placeholder derived from a cheap image signature so that
            // identical bitmaps map to identical vectors (keeps the index sane).
            out = deterministicVector(imageSignature(image), Config.IMAGE_DIM)
        }
        record("embed_image", ns)
        return out
    }

    /** Zero-shot utility-photo typing. Stub returns "other"; real impl uses cached prompt embeddings. */
    fun classifyType(image: Bitmap): TypeGuess {
        var guess: TypeGuess
        val ns = measureNanoTime {
            guess = TypeGuess("other", 0f)
        }
        record("classify", ns)
        return guess
    }

    // ----------------------------------------------------------------------
    // 1.4 LLM runtime -> streaming grounded answer (spec §1.4, RISK #1).
    // ----------------------------------------------------------------------
    /**
     * Stream tokens for [prompt]. Stub emits a single deterministic sentence so the
     * Ask flow renders end-to-end. Real impl: Genie (QNN GenAI) / ORT-GenAI+QNN,
     * honoring [maxTokens] and [stop], exposing tokens/sec via [lastTimings].
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 256,
        stop: List<String> = emptyList(),
        onToken: (String) -> Unit
    ) {
        val engine = llmOrNull()
        if (engine == null) {
            // No model / init failed -> deterministic stub (with its own timing).
            val ns = measureNanoTime { generateStub(prompt, maxTokens, stop, onToken) }
            val secs = ns / 1_000_000_000.0
            record("generate", ns, tokensPerSec = if (secs > 0) 8.0 / secs else null)
            return
        }

        var emitted = 0
        var fellBackToStub = false
        // Only one generation at a time per LlmInference instance.
        val ns = synchronized(genLock) {
            measureNanoTime {
                try {
                    emitted = engine.generate(prompt, maxTokens, stop, onToken)
                } catch (t: Throwable) {
                    android.util.Log.w("Inference", "LLM generate failed, stub fallback", t)
                    // Only fall back if nothing was streamed; otherwise stop cleanly.
                    if (emitted == 0) {
                        generateStub(prompt, maxTokens, stop, onToken)
                        fellBackToStub = true
                    }
                }
            }
        }
        val secs = ns / 1_000_000_000.0
        val tps = when {
            fellBackToStub && secs > 0 -> 8.0 / secs
            secs > 0 && emitted > 0 -> emitted / secs
            else -> null
        }
        record("generate", ns, tokensPerSec = tps)
    }

    /** Deterministic offline fallback: streams a single sentence word-by-word into [onToken]. */
    private fun generateStub(
        @Suppress("unused") prompt: String,
        @Suppress("unused") maxTokens: Int,
        stop: List<String>,
        onToken: (String) -> Unit
    ) {
        val placeholder =
            "[stub LLM] On-device answer would be generated here, grounded in the cited items."
        for (tok in placeholder.split(" ")) {
            if (stop.any { it.isNotEmpty() && tok.contains(it) }) break
            onToken("$tok ")
        }
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------
    private fun record(stage: String, ns: Long, tokensPerSec: Double? = null) {
        lastTimings[stage] = InferenceTiming(stage, preferredEp, ns / 1_000_000.0, tokensPerSec)
    }

    companion object {
        /**
         * Deterministic, L2-normalized pseudo-embedding from a string. Same input ->
         * same vector, and similar-length/character strings land nearby, which is enough
         * for the in-memory cosine fallback to behave plausibly in a demo.
         */
        fun deterministicVector(seed: String, dim: Int): FloatArray {
            val v = FloatArray(dim)
            var h = 1125899906842597L // prime
            for (c in seed) h = 31 * h + c.code
            var state = h
            for (i in 0 until dim) {
                // xorshift64
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                v[i] = ((state and 0xFFFF).toFloat() / 0xFFFF) - 0.5f
            }
            val n = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()
            if (n > 0f) for (i in v.indices) v[i] /= n
            return v
        }

        /** Cheap, stable signature of a bitmap (downsampled luminance bucket). */
        private fun imageSignature(bmp: Bitmap): String {
            val sb = StringBuilder("img:${bmp.width}x${bmp.height}:")
            val steps = 8
            val sx = (bmp.width / steps).coerceAtLeast(1)
            val sy = (bmp.height / steps).coerceAtLeast(1)
            var x = 0
            while (x < bmp.width) {
                var y = 0
                while (y < bmp.height) {
                    val p = bmp.getPixel(x, y)
                    val lum = (((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3)
                    sb.append(abs(lum) / 32)
                    y += sy
                }
                x += sx
            }
            return sb.toString()
        }
    }
}
