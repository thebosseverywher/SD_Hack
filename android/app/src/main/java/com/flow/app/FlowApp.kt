package com.flow.app

import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons. WorkManager is configured manually here (the default
 * initializer is removed in the manifest) so the backfill Worker can reach these.
 *
 * Flow is mobile-only: there is NO laptop, NO pairing, NO federation. Everything —
 * capture, retrieval, ambient consolidation, and generation — runs on-device.
 */
class FlowApp : Application(), Configuration.Provider {

    lateinit var inference: Inference
        private set
    lateinit var index: Index
        private set
    var troveIndexer: TroveIndexer? = null
        private set
    lateinit var ambientMemory: AmbientMemory
        private set
    lateinit var travis: Travis
        private set
    lateinit var deviceId: String
        private set

    /** Application-scoped coroutines for the always-on ambient-memory consolidation loop. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        deviceId = loadOrCreateDeviceId()
        Trail.selfDeviceId = deviceId

        // Disk-backed so Travis's ambient memory survives restarts/reboots (loads on init).
        index = PersistentIndex(this)
        inference = Inference(this).also { it.warmUp() }
        troveIndexer = TroveIndexer(this, inference, index, deviceId)
        ambientMemory = AmbientMemory(inference, index, deviceId)
        // Disk-backed conversational memory makes Travis fully stateful: the chat thread
        // (turns + rolling summary) persists across app restarts and reboots.
        travis = Travis(inference, index, ambientMemory, ConversationStore(this))

        // Every captured activity Item is BOTH ingested into the local index (with its text
        // embedding) AND forwarded to the ambient memory layer for recency + consolidation.
        Trail.bindSink { item ->
            index.ingest(item, textVec = inference.embedText(item.text))
            ambientMemory.onActivity(item)
        }

        // Kick off the always-on consolidation loop (distills raw events into memory notes).
        ambientMemory.start(appScope)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /** Stable device id stored in encrypted prefs (security-crypto). */
    private fun loadOrCreateDeviceId(): String {
        val prefs = securePrefs()
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = deriveDeviceId(java.util.UUID.randomUUID().toString())
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        "flow_secure_prefs",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
