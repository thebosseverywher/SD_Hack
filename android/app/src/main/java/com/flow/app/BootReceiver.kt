package com.flow.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — keeps Flow always-on.
 *
 * On device boot we (re)start the foreground [IndexingService] so Travis resumes capturing
 * the user's activity into ambient memory without the app needing to be opened manually.
 * Everything stays on-device; no network is required to start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> IndexingService.start(context)
        }
    }
}
