package com.flow.app

import android.accessibilityservice.AccessibilityService
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Trail (spec §3.2) — a private, on-device timeline of phone activity.
 *
 * Captures:
 *   - TYPE_WINDOW_STATE_CHANGED   -> foreground app (package + label)
 *   - TYPE_VIEW_TEXT_CHANGED      -> typed text  (password/OTP fields are SKIPPED)
 *   - TYPE_WINDOW_CONTENT_CHANGED -> confirmation / booking screens
 *
 * PRIVACY (non-negotiable, spec §3.2 / consent screen):
 *   Password and OTP fields are NEVER captured. We drop any node that is a password
 *   input (isPassword / TYPE_TEXT_VARIATION_PASSWORD / VISIBLE_PASSWORD) and any
 *   field that looks like an OTP. Everything stays on-device; only encrypted search
 *   snippets the user explicitly queries leave the phone.
 */
class FlowAccessibilityService : AccessibilityService() {

    private var lastPackage: String? = null
    private var lastEmitMs = 0L

    /** Emits an activity Item into the index. Wired by IndexingService when running. */
    var sink: ((Item) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Trail.activeService = this
        Log.i(TAG, "Trail accessibility service connected")
    }

    override fun onDestroy() {
        if (Trail.activeService === this) Trail.activeService = null
        super.onDestroy()
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!Trail.enabled) return   // honor the per-sensor toggle / consent state

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onForegroundChanged(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(event)
        }
    }

    private fun onForegroundChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return                  // ignore ourselves
        if (pkg == lastPackage) return                  // coalesce repeats
        lastPackage = pkg
        val label = event.className?.toString()
        emit(
            type = "activity",
            appContext = pkg,
            text = "Opened ${label?.let { "$pkg ($it)" } ?: pkg}"
        )
    }

    private fun onTextChanged(event: AccessibilityEvent) {
        val src = event.source
        try {
            if (src != null && isSensitive(src)) return  // SKIP passwords / OTP
            val text = event.text?.joinToString(" ")?.trim().orEmpty()
            if (text.isBlank()) return
            if (looksLikeOtp(text)) return
            emit(
                type = "note",
                appContext = event.packageName?.toString(),
                text = "Typed: $text"
            )
        } finally {
            src?.recycle()
        }
    }

    private fun onContentChanged(event: AccessibilityEvent) {
        // Confirmation / booking screens are valuable (e.g. an IRCTC PNR, an order id).
        // We sample lightly to avoid flooding the index.
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (now - lastEmitMs < CONTENT_THROTTLE_MS) return
        val root = rootInActiveWindow ?: return
        val snippet = try {
            harvestVisibleText(root, budget = 400)
        } finally {
            // rootInActiveWindow hands back an owned node; recycle it to avoid leaking
            // node objects under heavy TYPE_WINDOW_CONTENT_CHANGED traffic.
            @Suppress("DEPRECATION") root.recycle()
        }
        if (snippet.isBlank()) return
        lastEmitMs = now
        emit(type = "activity", appContext = pkg, text = snippet)
    }

    /**
     * Recursively concatenate visible, non-sensitive text up to [budget] chars.
     *
     * Every child obtained via getChild() is recycled after it is walked. The root passed
     * in is owned/recycled by the caller (see onContentChanged), so it is not recycled here.
     */
    private fun harvestVisibleText(node: AccessibilityNodeInfo, budget: Int): String {
        val sb = StringBuilder()
        // `owned` = true when this node came from getChild() and we must recycle it.
        fun walk(n: AccessibilityNodeInfo?, owned: Boolean) {
            n ?: return
            try {
                if (sb.length >= budget) return
                if (!isSensitive(n)) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrBlank() && !looksLikeOtp(t)) {
                        if (sb.isNotEmpty()) sb.append(" · ")
                        sb.append(t)
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), owned = true)
            } finally {
                if (owned) @Suppress("DEPRECATION") n.recycle()
            }
        }
        walk(node, owned = false)
        return sb.toString().take(budget)
    }

    private fun emit(type: String, appContext: String?, text: String) {
        val item = Item(
            id = UUID.randomUUID().toString(),
            device_id = Trail.selfDeviceId,
            source = "trail",
            ts = System.currentTimeMillis() / 1000,
            app_context = appContext,
            text = text,
            type = type,
            fields = JsonObject(
                buildMap {
                    appContext?.let { put("app", JsonPrimitive(it)) }
                }
            )
        )
        val s = sink ?: Trail.fallbackSink
        s?.invoke(item)
    }

    companion object {
        private const val TAG = "Flow/Trail"
        private const val CONTENT_THROTTLE_MS = 1500L

        /** True if this node is a password/secure field that must never be captured. */
        fun isSensitive(node: AccessibilityNodeInfo): Boolean {
            if (node.isPassword) return true
            val it = node.inputType
            val variation = it and InputType.TYPE_MASK_VARIATION
            val cls = it and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_TEXT) {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) return true
                if (variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true
                if (variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) return true
            }
            if (cls == InputType.TYPE_CLASS_NUMBER &&
                (it and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0
            ) return true
            return false
        }

        /** Heuristic OTP guard: 4–8 digit codes typed into a field. */
        fun looksLikeOtp(text: String): Boolean {
            val t = text.trim()
            return t.length in 4..8 && t.all { it.isDigit() }
        }
    }
}

/** Process-wide Trail state so the service (created by the OS) can reach app singletons. */
object Trail {
    @Volatile var enabled: Boolean = true            // toggled from the consent / settings UI
    @Volatile var selfDeviceId: String = "phone"
    @Volatile var activeService: FlowAccessibilityService? = null

    /** Used until IndexingService binds its own sink. */
    @Volatile var fallbackSink: ((Item) -> Unit)? = null

    fun bindSink(sink: (Item) -> Unit) {
        fallbackSink = sink
        activeService?.sink = sink
    }
}
