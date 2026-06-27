package com.flow.app

import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Configuration

/**
 * Process-wide singletons. WorkManager is configured manually here (the default
 * initializer is removed in the manifest) so the backfill Worker can reach these.
 */
class FlowApp : Application(), Configuration.Provider {

    lateinit var inference: Inference
        private set
    lateinit var index: Index
        private set
    var troveIndexer: TroveIndexer? = null
        private set
    lateinit var federation: FederationManager
        private set
    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()

        deviceId = loadOrCreateDeviceId()
        Trail.selfDeviceId = deviceId

        // In-memory fallbacks keep the skeleton coherent; swap behind the interfaces.
        index = InMemoryIndex()
        inference = Inference(this).also { it.warmUp() }
        troveIndexer = TroveIndexer(this, inference, index, deviceId)

        federation = FederationManager(
            selfDeviceId = deviceId,
            selfName = android.os.Build.MODEL ?: "phone",
            index = index,
            caps = Caps(tops = 45.0, has_llm = true, battery = -1)
        )

        // Let Federation embed inbound peer QUERY text without depending on Inference.
        StubEmbedHook.embed = { text -> inference.embedText(text) }

        // Trail emits Items straight into the local index.
        Trail.bindSink { item -> index.ingest(item, textVec = inference.embedText(item.text)) }
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
