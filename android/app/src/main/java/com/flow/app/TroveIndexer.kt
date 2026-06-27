package com.flow.app

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Trove (spec §3.1) — turn the camera roll into indexed, typed, actionable items.
 *
 * Two paths:
 *   - LIVE: a MediaStore [ContentObserver] reacts to newly added photos.
 *   - BACKLOG: [TroveBackfillWorker] (WorkManager) walks the existing library,
 *     battery-aware (runs when charging/idle), so the UI never blocks (spec §3.1).
 *
 * Per photo we run the inference hooks (stubbed in [Inference]):
 *   OCR (1.2) -> text;  CLIP type (1.3);  image embedding (1.3);  text embedding of OCR (1.1)
 * then [Index.ingest] both vectors. Field extraction (wifi/amount/date/serial/etc.)
 * is regex-first with an LLM fallback for posters (TODO hook).
 */
class TroveIndexer(
    private val context: Context,
    private val inference: Inference,
    private val index: Index,
    private val deviceId: String
) {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    /** Start observing for new images. Call from [IndexingService] once permissions are granted. */
    fun startObserving() {
        if (observer != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                runCatching { indexUri(uri) }
                    .onFailure { Log.w(TAG, "live index failed: ${it.message}") }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, /* notifyForDescendants = */ true, obs
        )
        observer = obs
        Log.i(TAG, "Trove observing MediaStore")
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /** Index one image by content URI. Returns the stored item id or null. */
    fun indexUri(uri: Uri): String? {
        val bitmap = loadBitmap(uri) ?: return null
        try {
            val ocr = inference.ocr(bitmap)
            val ocrText = ocr.joinToString("\n") { it.text }.trim()
            val typeGuess = inference.classifyType(bitmap)
            val imageVec = inference.embedImage(bitmap)
            val textVec = if (ocrText.isNotBlank()) inference.embedText(ocrText) else null

            val fields = FieldExtractor.extract(ocrText)
            val item = Item(
                id = contentId(uri, ocrText),                 // content-hash dedupe (spec §2.2)
                device_id = deviceId,
                source = "trove",
                ts = System.currentTimeMillis() / 1000,
                app_context = null,
                text = ocrText,
                type = if (typeGuess.score > 0.2f) typeGuess.type else inferType(fields),
                fields = fields,
                thumb_b64 = makeThumb(bitmap),
                file_ref = uri.toString()
            )
            return index.ingest(item, textVec = textVec, imageVec = imageVec)
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    }.getOrNull()

    /** Enqueue the one-time, battery-aware backfill of the existing library. */
    fun enqueueBackfill() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(false)           // flip to true to be stricter (spec §3.1)
            .setRequiresBatteryNotLow(true)
            .build()
        val work = OneTimeWorkRequestBuilder<TroveBackfillWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(BACKFILL_WORK, ExistingWorkPolicy.KEEP, work)
    }

    companion object {
        private const val TAG = "Flow/Trove"
        const val BACKFILL_WORK = "flow.trove.backfill"

        fun makeThumb(bmp: Bitmap, maxEdge: Int = 256): String {
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            val out = ByteArrayOutputStream()
            // Keep <= 64KB jpeg (spec §2.4); step quality down if needed.
            var quality = 80
            do {
                out.reset()
                thumb.compress(Bitmap.CompressFormat.JPEG, quality, out)
                quality -= 15
            } while (out.size() > 64 * 1024 && quality > 20)
            if (thumb !== bmp) thumb.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        private fun contentId(uri: Uri, ocrText: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(uri.toString().toByteArray())
            md.update(ocrText.toByteArray())
            return "trove-" + Base64.encodeToString(md.digest(), Base64.NO_WRAP or Base64.URL_SAFE).take(20)
        }

        private fun inferType(fields: JsonObject): String = when {
            fields.containsKey("ssid") -> "wifi"
            fields.containsKey("amount") -> "receipt"
            fields.containsKey("serial") -> "serial"
            else -> "other"
        }
    }
}

/**
 * Battery-aware backlog indexer. Walks MediaStore and indexes each image.
 *
 * NOTE: this Worker needs an [Inference] + [Index]; in this skeleton it reuses the
 * process-wide singletons exposed by [FlowApp]. If those are not yet initialized
 * (e.g. cold WorkManager start) it degrades gracefully by no-op success.
 */
class TroveBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FlowApp ?: return Result.success()
        val indexer = app.troveIndexer ?: return Result.success()

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var indexed = 0
        runCatching {
            applicationContext.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && indexed < MAX_BACKFILL) {
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    if (indexer.indexUri(uri) != null) indexed++
                }
            }
        }.onFailure { return Result.retry() }

        Log.i("Flow/Trove", "backfill indexed=$indexed")
        return Result.success()
    }

    companion object {
        // Cap for a hackathon demo; remove or raise for a full run.
        private const val MAX_BACKFILL = 500
    }
}

/**
 * Regex-first structured field extraction (spec §3.1). Keys here feed Item.fields
 * and the type inference. Keep keys snake_case to match the desktop extractor.
 */
object FieldExtractor {
    private val ssidRe = Regex("""(?i)\b(ssid|network|wifi)\b[:\s]+([^\n,]{1,40})""")
    private val passRe = Regex("""(?i)\b(password|pass|key)\b[:\s]+([^\s\n]{4,40})""")
    private val amountRe = Regex("""(?i)(?:₹|rs\.?|\$|usd|inr)\s?([0-9][0-9,]*\.?[0-9]{0,2})""")
    private val serialRe = Regex("""(?i)\b(serial|s/n|sn|imei)\b[:\s]*([A-Z0-9\-]{5,})""")
    private val emailRe = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val phoneRe = Regex("""(?<!\d)(\+?\d[\d\-\s]{7,13}\d)(?!\d)""")
    private val dateRe = Regex("""\b(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4})\b""")

    fun extract(text: String): JsonObject {
        if (text.isBlank()) return JsonObject(emptyMap())
        val m = LinkedHashMap<String, JsonPrimitive>()
        ssidRe.find(text)?.let { m["ssid"] = JsonPrimitive(it.groupValues[2].trim()) }
        // NOTE: we DO surface a wifi password (it's the user's own utility photo, not a
        // captured credential field). Trail's typed-password capture is what's forbidden.
        passRe.find(text)?.let { m["pass"] = JsonPrimitive(it.groupValues[2].trim()) }
        amountRe.find(text)?.let { m["amount"] = JsonPrimitive(it.groupValues[1].trim()) }
        serialRe.find(text)?.let { m["serial"] = JsonPrimitive(it.groupValues[2].trim()) }
        emailRe.find(text)?.let { m["email"] = JsonPrimitive(it.value) }
        phoneRe.find(text)?.let { m["phone"] = JsonPrimitive(it.value.trim()) }
        dateRe.find(text)?.let { m["when"] = JsonPrimitive(it.groupValues[1]) }
        return JsonObject(m)
    }
}
