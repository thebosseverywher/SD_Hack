package com.flow.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service (spec §7.1) that owns the always-on parts of Flow:
 *   - Trove live MediaStore observing + enqueues the WorkManager backfill
 *   - the federation peer (so QUERY/RESULTS work while the app is backgrounded)
 *
 * It shows the persistent "capturing" indicator required by the consent model.
 * foregroundServiceType=dataSync is declared in the manifest.
 */
class IndexingService : Service() {

    private val app get() = application as FlowApp

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // Bring up Trove + backfill (only does work once permissions are granted).
        app.troveIndexer?.apply {
            startObserving()
            enqueueBackfill()
        }
        // Trail already routes into the index via FlowApp's sink; nothing to start here.
        return START_STICKY
    }

    override fun onDestroy() {
        app.troveIndexer?.stopObserving()
        app.federation.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flow is capturing on-device")
            .setContentText("Indexing photos & activity locally. Passwords excluded.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "Flow indexing", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows when Flow is capturing and indexing on-device." }
        mgr.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "flow_indexing"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, IndexingService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IndexingService::class.java))
        }
    }
}
