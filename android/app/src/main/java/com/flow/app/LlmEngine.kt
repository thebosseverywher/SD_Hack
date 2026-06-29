package com.flow.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around LiteRT-LM's [Engine]/[Session] for on-device grounded RAG
 * generation. Replaces the maintenance-mode MediaPipe tasks-genai runtime.
 *
 * Backend selection (best available wins; init is the only reliable probe):
 *   GPU (Adreno OpenCL, generic ekv4096 model)  ->  CPU (arm64, generic ekv4096 model).
 * NPU is intentionally NOT attempted: on this AAR Backend.NPU without the gated
 * QAIRT dispatch lib SIGABRTs inside Engine.initialize() (uncatchable). It is only
 * tried when the dispatch lib + V75 skel are physically present (see npuReady()).
 *
 * Model resolution (NEVER bundled in the APK):
 *   generic:  {filesDir}/llm/<generic>.litertlm  ||  /data/local/tmp/flow/llm/<generic>.litertlm
 *   npu:      same dirs, <sm8650>.litertlm   (only used if npuReady())
 *
 * The LiteRT-LM Session.generateContentStream callback is BLOCKING until done and
 * delivers INCREMENTAL deltas via onNext — so this maps directly to the streaming
 * generate() contract. createOrNull() and generate() MUST run off the main thread.
 */
class LlmEngine private constructor(
    private val engine: Engine,
    val backendLabel: String,
) {
    val available: Boolean get() = true

    fun generate(
        prompt: String,
        maxTokens: Int,
        stop: List<String>,
        onToken: (String) -> Unit
    ): Int {
        // topK/topP/temperature only — LiteRT-LM has NO native maxTokens or stop fields;
        // both are enforced caller-side below via cancelProcess(). topP/temperature are Double.
        val session = engine.createSession(
            SessionConfig(SamplerConfig(topK = 64, topP = 0.95, temperature = 0.9, seed = 0))
        )
        val sb = StringBuilder()
        var emitted = 0
        val stopped = AtomicReference(false)
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        try {
            session.generateContentStream(
                listOf(InputData.Text(prompt)),   // raw, pre-templated prompt — no double-template
                object : ResponseCallback {
                    override fun onNext(response: String) {       // response == incremental delta
                        if (stopped.get() || response.isEmpty()) return
                        sb.append(response)
                        val cut = stop.firstOrNull { it.isNotEmpty() && sb.contains(it) }
                        if (cut != null) {
                            val safe = response.substringBefore(cut)
                            if (safe.isNotEmpty()) { onToken(safe); emitted++ }
                            stopped.set(true)
                            session.cancelProcess()   // -> onError(CancellationException), treated as done
                        } else {
                            onToken(response); emitted++
                            if (emitted >= maxTokens) {
                                stopped.set(true)
                                session.cancelProcess()
                            }
                        }
                    }
                    override fun onDone() { latch.countDown() }
                    override fun onError(t: Throwable) {
                        // cancelProcess() surfaces a CancellationException — that's normal completion.
                        if (!stopped.get() &&
                            t !is kotlin.coroutines.cancellation.CancellationException
                        ) {
                            errorRef.set(t)
                        }
                        latch.countDown()
                    }
                }
            )
            latch.await()
        } finally {
            try { session.close() } catch (t: Throwable) { Log.w(TAG, "session close failed", t) }
        }
        errorRef.get()?.let { if (emitted == 0) throw it }  // let Inference.kt stub-fallback decide
        return emitted
    }

    fun close() {
        try { engine.close() } catch (t: Throwable) { Log.w(TAG, "engine close failed", t) }
    }

    companion object {
        private const val TAG = "LlmEngine"
        // Gemma3-1B on GPU is the proven, fast config (~20s init, snappy generation).
        // NOTE: gemma-4-E2B-it.litertlm (~2B, 2.59GB) was tried but its GPU OpenCL init
        // HANGS on this Adreno (8 Gen 3) — 0% CPU, never loads, never errors (so it can't
        // even fall through). Kept as an opt-in escape hatch: if a future LiteRT-LM/driver
        // build fixes it, set GENERIC_MODEL = BIG_MODEL. The file remains on-device.
        private const val GENERIC_MODEL = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
        private const val FALLBACK_MODEL = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
        @Suppress("unused")
        private const val BIG_MODEL = "gemma-4-E2B-it.litertlm"
        private const val NPU_MODEL = "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm"

        private fun resolve(context: Context, name: String): File? = listOf(
            File(context.filesDir, "llm/$name"),
            File("/data/local/tmp/flow/llm/$name")
        ).firstOrNull { it.exists() && it.length() > 0L }

        /**
         * NPU is safe to attempt ONLY if the (gated, currently-unavailable) QAIRT dispatch
         * lib + V75 Hexagon skel are physically present in nativeLibraryDir; otherwise
         * Engine.initialize() with Backend.NPU SIGABRTs and kills the process. Gate hard.
         */
        private fun npuReady(context: Context): Boolean {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            return File(dir, "libLiteRtDispatch_Qualcomm.so").exists() &&
                File(dir, "libQnnHtpV75Skel.so").exists() &&
                File(dir, "libQnnSystem.so").exists()
        }

        fun createOrNull(context: Context): LlmEngine? {
            // Try NPU only when its libs are actually present (never speculatively).
            if (npuReady(context)) {
                resolve(context, NPU_MODEL)?.let { f ->
                    runCatching {
                        // QAIRT loader needs these on the lib path before Backend.NPU is built.
                        val dir = context.applicationInfo.nativeLibraryDir
                        android.system.Os.setenv("LD_LIBRARY_PATH", dir, true)
                        android.system.Os.setenv("ADSP_LIBRARY_PATH", dir, true)
                        build(f, Backend.NPU(dir), maxNumTokens = 1280, context, "NPU")
                    }.getOrNull()?.let { return it }
                }
            }

            // Generic model for GPU then CPU: prefer the larger Gemma-4-E2B, else Gemma3-1B.
            val generic = resolve(context, GENERIC_MODEL) ?: resolve(context, FALLBACK_MODEL) ?: run {
                Log.i(TAG, "No .litertlm model found; using stub generation.")
                return null
            }
            Log.i(TAG, "LLM model file: ${generic.name}")
            runCatching { build(generic, Backend.GPU(), maxNumTokens = 4096, context, "GPU") }
                .getOrNull()?.let { Log.i(TAG, "LLM runtime=LiteRT-LM backend=GPU ready."); return it }
            runCatching { build(generic, Backend.CPU(), maxNumTokens = 4096, context, "CPU") }
                .getOrNull()?.let { Log.i(TAG, "LLM runtime=LiteRT-LM backend=CPU ready."); return it }
            return null
        }

        private fun build(
            f: File, backend: Backend, maxNumTokens: Int, context: Context, label: String
        ): LlmEngine? = try {
            val engine = Engine(
                EngineConfig(
                    modelPath = f.absolutePath,
                    backend = backend,
                    maxNumTokens = maxNumTokens,          // context window cap (NOT per-call)
                    cacheDir = context.cacheDir.absolutePath,   // CRITICAL for GPU/NPU JIT compile
                )
            )
            engine.initialize()      // slow (up to ~10s) — caller is off-main-thread
            LlmEngine(engine, label)
        } catch (t: Throwable) {
            Log.w(TAG, "LLM init failed (backend=$label)", t)
            null
        }
    }
}
