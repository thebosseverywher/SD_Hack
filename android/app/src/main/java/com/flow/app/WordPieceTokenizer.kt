package com.flow.app

import android.content.Context
import java.text.Normalizer

/**
 * Minimal BERT-uncased WordPiece tokenizer for all-MiniLM-L6-v2 (spec §1.1).
 *
 * Loads the standard 30522-line BERT-uncased vocab (assets/models/vocab.txt) into a
 * String -> id map. [encode] reproduces the HuggingFace BasicTokenizer +
 * WordpieceTokenizer pipeline closely enough for embedding parity:
 *   lowercase -> strip accents -> split on whitespace -> isolate punctuation ->
 *   greedy longest-match WordPiece (## continuation for non-leading subwords).
 *
 * Special ids (from the provisioned vocab): [PAD]=0, [UNK]=100, [CLS]=101,
 * [SEP]=102, [MASK]=103. Unknown subwords map to [UNK].
 */
class WordPieceTokenizer private constructor(private val vocab: Map<String, Int>) {

    /** Tokenized output, all padded/truncated to the same length, ready for OnnxTensor. */
    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
        val length: Int
    )

    /**
     * Encode [text] to fixed-length [maxLen] BERT inputs. Wraps with [CLS]…[SEP],
     * truncates the WordPiece stream so the special tokens always fit, then right-pads
     * with [PAD]. attention_mask is 1 for real tokens then 0 for pad; token_type_ids
     * is all 0 (single-segment).
     */
    fun encode(text: String, maxLen: Int = 128): Encoded {
        val ids = ArrayList<Int>(maxLen)
        ids.add(CLS)
        // Reserve room for the trailing [SEP].
        val budget = maxLen - 1
        outer@ for (word in basicTokenize(text)) {
            for (piece in wordpiece(word)) {
                if (ids.size >= budget) break@outer
                ids.add(piece)
            }
        }
        ids.add(SEP)

        val inputIds = LongArray(maxLen)
        val attn = LongArray(maxLen)
        val types = LongArray(maxLen)
        val real = ids.size.coerceAtMost(maxLen)
        for (i in 0 until real) {
            inputIds[i] = ids[i].toLong()
            attn[i] = 1L
            // token_type_ids stays 0 (single segment); already zero-initialized.
        }
        // Remaining slots are [PAD]=0 with attention 0 (already zero-initialized).
        return Encoded(inputIds, attn, types, real)
    }

    /** BasicTokenizer: lowercase, strip accents, whitespace-split, isolate punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val lowered = text.lowercase()
        val deaccented = stripAccents(lowered)
        val out = ArrayList<String>()
        for (chunk in deaccented.split(*WHITESPACE)) {
            if (chunk.isEmpty()) continue
            // Split out punctuation into standalone tokens (BERT treats punct as its own token).
            val sb = StringBuilder()
            for (c in chunk) {
                if (isPunctuation(c)) {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                    out.add(c.toString())
                } else {
                    sb.append(c)
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    /** Greedy longest-match WordPiece over a single (already basic-tokenized) word. */
    private fun wordpiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(UNK)
        val pieces = ArrayList<Int>()
        var start = 0
        val n = word.length
        while (start < n) {
            var end = n
            var curId: Int? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                          else "##" + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { curId = id; break }
                end--
            }
            if (curId == null) {
                // Any unmatched subword poisons the whole word -> [UNK].
                return listOf(UNK)
            }
            pieces.add(curId)
            start = end
        }
        return pieces
    }

    /** Basic accent stripping via NFD decomposition + combining-mark removal. */
    private fun stripAccents(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        }
        return sb.toString()
    }

    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        // ASCII punctuation ranges that BERT isolates, plus Unicode punctuation classes.
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        const val PAD = 0
        const val UNK = 100
        const val CLS = 101
        const val SEP = 102
        const val MASK = 103
        private const val MAX_WORD_CHARS = 100
        private val WHITESPACE = charArrayOf(' ', '\t', '\n', '\r', '\u000C', '\u000B')

        /** Load vocab.txt (one token per line; line index == id) from assets. */
        fun fromAssets(context: Context, path: String = "models/vocab.txt"): WordPieceTokenizer {
            val map = HashMap<String, Int>(32_768)
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                var i = 0
                for (line in lines) {
                    // vocab tokens have no surrounding whitespace; trailing \r is stripped by reader.
                    map[line] = i
                    i++
                }
            }
            return WordPieceTokenizer(map)
        }
    }
}
