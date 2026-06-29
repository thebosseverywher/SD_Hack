package com.flow.app

/** Maps capture package ids -> human app names. Shared by Travis prompts and the UI. */
object AppNames {
    private val KNOWN = mapOf(
        "com.android.settings" to "Settings",
        "com.oplus.wirelesssettings" to "Settings",
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "co.hinge.app" to "Hinge",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.messaging" to "Messages",
        "com.android.chrome" to "Chrome",
        "com.google.android.youtube" to "YouTube",
        "com.android.vending" to "Play Store",
        "com.google.android.apps.maps" to "Maps",
        "com.spotify.music" to "Spotify",
        "com.linkedin.android" to "LinkedIn",
        "com.twitter.android" to "X",
        "com.reddit.frontpage" to "Reddit",
        "com.slack" to "Slack",
        "org.telegram.messenger" to "Telegram"
    )
    private val SKIP = setOf("com", "co", "org", "net", "io", "android", "google",
        "oplus", "oppo", "app", "apps", "mobile", "client")

    fun friendly(pkg: String?): String? {
        val p = pkg?.substringBefore('/')?.trim()?.lowercase() ?: return null
        if (p.isBlank()) return null
        KNOWN[p]?.let { return it }
        val seg = p.split('.').filter { it.isNotBlank() && it !in SKIP }
            .maxByOrNull { it.length } ?: return null
        return seg.replaceFirstChar { it.uppercase() }
    }
}

/** Top-level convenience so existing call sites stay terse. */
fun friendlyApp(pkg: String?): String? = AppNames.friendly(pkg)
