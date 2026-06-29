package com.flow.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import java.nio.LongBuffer
import java.util.EnumSet
import kotlin.math.sqrt

/**
 * Real on-device text embeddings (spec §1.1) via ONNX Runtime running
 * all-MiniLM-L6-v2 (assets/models/minilm.onnx, 384-d, standard BERT-uncased inputs).
 *
 * Execution provider strategy:
 *   1. Try the NNAPI EP (USE_FP16 + CPU_DISABLED). On the Snapdragon 8 Gen 3 NNAPI
 *      routes supported ops to the Hexagon NPU/DSP -> epLabel "NPU (NNAPI)".
 *   2. If building the NNAPI session throws (driver/op unsupported), rebuild a plain
 *      CPU session -> epLabel "CPU".
 *
 * Construction THROWS on hard failure (missing asset, corrupt model) so the caller
 * (Inference) can fall back to its deterministic pseudo-embedding path.
 */
class OrtTextEmbedder(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: WordPieceTokenizer = WordPieceTokenizer.fromAssets(context)

    /** Which EP actually built the session. Surfaced for telemetry. */
    val epLabel: String

    /** Cached input names of the loaded model (input_ids / attention_mask / token_type_ids). */
    private val inputNames: Set<String>

    init {
        val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }

        // Attempt NNAPI (NPU) first; fall back to CPU if the session won't build.
        var built: OrtSession? = null
        var label = "CPU"
        try {
            val opts = OrtSession.SessionOptions()
            opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
            built = env.createSession(modelBytes, opts)
            label = "NPU (NNAPI)"
            Log.i(TAG, "ORT text embedder using NNAPI EP (NPU/DSP).")
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI EP unavailable, falling back to CPU", t)
            built = null
        }
        if (built == null) {
            // Plain CPU session (default EP).
            built = env.createSession(modelBytes, OrtSession.SessionOptions())
            label = "CPU"
            Log.i(TAG, "ORT text embedder using CPU EP.")
        }
        session = built!!
        epLabel = label
        inputNames = session.inputNames
    }

    /** Embed a single string -> 384-d L2-normalized vector. */
    fun embed(text: String): FloatArray = embed(listOf(text))[0]

    /** Embed a batch (one session run per text) -> 384-d L2-normalized vectors. */
    fun embed(texts: List<String>): Array<FloatArray> =
        Array(texts.size) { i -> embedOne(texts[i]) }

    private fun embedOne(text: String): FloatArray {
        val enc = tokenizer.encode(text, MAX_LEN)
        val shape = longArrayOf(1L, MAX_LEN.toLong())

        val idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
        val typeT = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)

        val feeds = HashMap<String, OnnxTensor>(3)
        // Only feed inputs the model actually declares (robust to models lacking token_type_ids).
        if ("input_ids" in inputNames) feeds["input_ids"] = idsT
        if ("attention_mask" in inputNames) feeds["attention_mask"] = maskT
        if ("token_type_ids" in inputNames) feeds["token_type_ids"] = typeT

        try {
            session.run(feeds).use { result ->
                // Prefer "last_hidden_state"; otherwise fall back to the first output tensor.
                val value: OnnxValue =
                    result.get("last_hidden_state").orElse(null) ?: result.get(0)
                @Suppress("UNCHECKED_CAST")
                val hidden = (value as OnnxTensor).value as Array<Array<FloatArray>>
                return meanPoolAndNormalize(hidden[0], enc.attentionMask)
            }
        } finally {
            idsT.close()
            maskT.close()
            typeT.close()
        }
    }

    /** Mean-pool [seq, 384] over the attention mask, then L2-normalize to length 384. */
    private fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = if (hidden.isNotEmpty()) hidden[0].size else Config.TEXT_DIM
        val pooled = FloatArray(dim)
        var maskSum = 0.0
        val seq = minOf(hidden.size, mask.size)
        for (t in 0 until seq) {
            if (mask[t] == 0L) continue
            maskSum += 1.0
            val row = hidden[t]
            for (d in 0 until dim) pooled[d] += row[d]
        }
        if (maskSum > 0.0) {
            val inv = (1.0 / maskSum).toFloat()
            for (d in 0 until dim) pooled[d] *= inv
        }
        var norm = 0.0
        for (d in 0 until dim) norm += pooled[d].toDouble() * pooled[d]
        val n = sqrt(norm).toFloat()
        if (n > 0f) for (d in 0 until dim) pooled[d] /= n
        return pooled
    }

    fun close() {
        try { session.close() } catch (t: Throwable) { Log.w(TAG, "ORT session close failed", t) }
        // env is a process-wide singleton; do not close it here.
    }

    companion object {
        private const val TAG = "OrtTextEmbedder"
        private const val MODEL_PATH = "models/minilm.onnx"
        private const val MAX_LEN = 128
    }
}
