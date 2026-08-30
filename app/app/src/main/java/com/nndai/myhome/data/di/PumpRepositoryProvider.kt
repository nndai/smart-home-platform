package com.nndai.myhome.data.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.local.MqttKeystoreStorage
import com.nndai.myhome.data.remote.DeviceCommandEnvelope
import com.nndai.myhome.data.remote.MqttCredentialRemoteDataSource
import com.nndai.myhome.data.remote.MqttDeviceChannel
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.repository.CredentialSyncResult
import com.nndai.myhome.data.repository.LogRepository
import com.nndai.myhome.data.repository.MqttCredentialRepository
import com.nndai.myhome.data.repository.PumpRepository
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates the entire data and communication stack for a single deviceId.
 * Guarantees 100% data isolation between distinct devices.
 */
data class DeviceRepositoryBundle(
    val deviceId: String,
    val channel: MqttDeviceChannel,
    val dataSource: PumpCommandDataSource,
    val pumpRepository: PumpRepository,
    val logRepository: LogRepository
)

/**
 * DI Provider managing Per-Device Repository Bundles, MQTT Connection Manager,
 * and MqttCredentialRepository.
 */
object PumpRepositoryProvider {

    private const val PREFS_NAME = "mqtt_config"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_MQTT_PORT = "mqtt_port"
    private const val KEY_MQTT_USER = "mqtt_user"
    private const val KEY_MQTT_PASS = "mqtt_pass"
    private const val KEY_MQTT_TOPIC = "mqtt_topic"
    private const val KEY_WS_URL = "ws_url"
    private const val KEY_DEVICE_ID = "active_device_id"

    private val deviceBundles = ConcurrentHashMap<String, DeviceRepositoryBundle>()

    @Volatile
    private var credentialRepository: MqttCredentialRepository? = null

    @Volatile
    private var mqttConnectionManager: com.nndai.myhome.data.remote.MqttConnectionManager? = null

    @Volatile
    private var deviceHandshakeManager: com.nndai.myhome.data.remote.DeviceHandshakeManager? = null

    @Volatile
    private var deviceShareRepository: com.nndai.myhome.data.repository.DeviceShareRepository? = null

    /**
     * DeviceManagerRepository instance registered by the UI layer so the
     * session listener can reset its in-memory state on sign-out.
     */
    @Volatile
    var deviceManagerRepository: com.nndai.myhome.data.repository.DeviceManagerRepository? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    /** Must call init() in Application or MainActivity before provide(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        val repo = provideCredentialRepository()

        val cached = repo.getCachedCredential()
        if (cached != null && cached.isValid()) {
            Log.d(TAG, "init(): Loaded MQTT credentials from Android Keystore. Ready for instant connect.")
        } else {
            Log.d(TAG, "init(): Android Keystore empty or invalid. Awaiting Supabase sync.")
        }

        // Start background MQTT transport connection
        provideMqttConnectionManager().connect()

        // Init per-device handshake manager
        provideDeviceHandshakeManager()

        startBackgroundCredentialSync()
    }

    fun provideMqttConnectionManager(): com.nndai.myhome.data.remote.MqttConnectionManager {
        return mqttConnectionManager ?: synchronized(this) {
            mqttConnectionManager ?: com.nndai.myhome.data.remote.MqttConnectionManager(
                context = appContext,
                hostProvider = { getMqttHost() },
                portProvider = { getMqttPort() },
                usernameProvider = { getMqttUser() },
                passwordProvider = { getMqttPass() },
                scope = appScope
            ).also { mqttConnectionManager = it }
        }
    }

    fun provideDeviceHandshakeManager(): com.nndai.myhome.data.remote.DeviceHandshakeManager {
        return deviceHandshakeManager ?: synchronized(this) {
            deviceHandshakeManager ?: run {
                val ctx = appContext ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
                val connMgr = provideMqttConnectionManager()
                com.nndai.myhome.data.remote.DeviceHandshakeManager(ctx, connMgr, appScope).also { deviceHandshakeManager = it }
            }
        }
    }

    fun provideCredentialRepository(): MqttCredentialRepository {
        return credentialRepository ?: synchronized(this) {
            credentialRepository ?: run {
                val ctx = appContext ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
                val keystore = MqttKeystoreStorage(ctx)
                val remoteDs = MqttCredentialRemoteDataSource()
                MqttCredentialRepository(keystore, remoteDs).also { credentialRepository = it }
            }
        }
    }

    /** Stateless repository for device sharing (invites + member management). */
    fun provideDeviceShareRepository(): com.nndai.myhome.data.repository.DeviceShareRepository {
        return deviceShareRepository ?: synchronized(this) {
            deviceShareRepository
                ?: com.nndai.myhome.data.repository.DeviceShareRepository().also { deviceShareRepository = it }
        }
    }

    private fun startBackgroundCredentialSync() {
        appScope.launch {
            var wasAuthenticated = false
            SupabaseConfig.client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    wasAuthenticated = true
                    Log.d(TAG, "startBackgroundCredentialSync(): Session authenticated. Syncing credentials...")
                    while (true) {
                        val repo = provideCredentialRepository()
                        when (val result = repo.syncWithRemote()) {
                            is CredentialSyncResult.Updated -> {
                                Log.d(TAG, "startBackgroundCredentialSync(): Credential updated! Reconnecting MQTT channels...")
                                deviceBundles.values.forEach { it.pumpRepository.reconnect() }
                                break
                            }
                            is CredentialSyncResult.Unchanged -> {
                                Log.d(TAG, "startBackgroundCredentialSync(): Credential verified and unchanged.")
                                break
                            }
                            is CredentialSyncResult.Failed -> {
                                Log.d(TAG, "startBackgroundCredentialSync(): Sync failed (retry in ${CREDENTIAL_RETRY_MS}ms): ${result.cause.message}")
                                delay(CREDENTIAL_RETRY_MS)
                            }
                        }
                    }
                } else if (status is SessionStatus.NotAuthenticated &&
                           status.isSignOut && wasAuthenticated) {
                    // Real sign-out transition (user logout): purge user-scoped
                    // secrets + cached device list so another account on this
                    // device cannot reuse the previous one's control keys.
                    // Device logs are stored separately and kept.
                    wasAuthenticated = false
                    runCatching {
                        val ctx = appContext ?: return@runCatching
                        com.nndai.myhome.data.pairing.ControlKeyStore(ctx).clearUserSecrets()
                        deviceManagerRepository?.clearLocalState()
                            ?: com.nndai.myhome.data.local.LocalDeviceCache(ctx).clearCache()
                        Log.d(TAG, "Session ended: cleared control keys + device cache.")
                    }
                } else {
                    // Initializing / RefreshFailure / transient states: NOT a
                    // sign-out. Purging here would wipe freshly synced keys
                    // right after login. Unauthorized leftovers are pruned by
                    // the next successful control-key sync anyway.
                    Log.d(TAG, "startBackgroundCredentialSync(): Non-signout session state ($status) — keeping local secrets.")
                }
            }
        }
    }

    /**
     * Retrieves the dedicated PumpRepository for a specific deviceId (or active deviceId).
     */
    fun provide(deviceId: String = getActiveDeviceId()): PumpRepository {
        return getOrCreateBundle(deviceId).pumpRepository
    }

    /**
     * Retrieves the dedicated LogRepository for a specific deviceId (or active deviceId).
     */
    fun provideLogRepository(deviceId: String = getActiveDeviceId()): LogRepository {
        return getOrCreateBundle(deviceId).logRepository
    }

    /**
     * Gets or creates a completely isolated DeviceRepositoryBundle for the specified deviceId.
     */
    fun getOrCreateBundle(targetDeviceId: String): DeviceRepositoryBundle {
        val id = targetDeviceId.ifBlank { getActiveDeviceId() }.ifBlank { "default_device" }
        return deviceBundles.getOrPut(id) {
            buildDeviceBundle(id)
        }
    }

    private fun buildDeviceBundle(targetDeviceId: String): DeviceRepositoryBundle {
        val ctx = appContext ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
        val envelope = DeviceCommandEnvelope(ctx)
        val mqttChannel = MqttDeviceChannel(
            connectionManager = provideMqttConnectionManager(),
            handshakeManager = provideDeviceHandshakeManager(),
            deviceId = targetDeviceId,
            envelope = envelope,
            scope = appScope
        )
        val dataSource = PumpCommandDataSource(mqttChannel, appScope)
        val pumpRepo = PumpRepository(dataSource, mqttChannel, appScope)
        val logRepo = LogRepository(ctx, targetDeviceId, dataSource, appScope)

        Log.d(TAG, "buildDeviceBundle(): Created isolated repository stack for deviceId=$targetDeviceId")
        return DeviceRepositoryBundle(
            deviceId = targetDeviceId,
            channel = mqttChannel,
            dataSource = dataSource,
            pumpRepository = pumpRepo,
            logRepository = logRepo
        )
    }

    /** Set active device ID and ensure its isolated channel is started. */
    fun setActiveDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        val oldId = getActiveDeviceId()
        getPrefs().edit().putString(KEY_DEVICE_ID, deviceId).apply()
        Log.d(TAG, "setActiveDeviceId(): $deviceId (previous was: $oldId)")

        val bundle = getOrCreateBundle(deviceId)
        bundle.channel.start()
    }

    fun getActiveDeviceId(): String = getPrefs().getString(KEY_DEVICE_ID, null)
        ?.takeIf { it.isNotBlank() } ?: ""

    fun saveMqttConfig(
        host: String,
        port: Int,
        user: String,
        pass: String,
        topic: String,
        wsUrl: String
    ) {
        getPrefs().edit()
            .putString(KEY_MQTT_HOST, host)
            .putInt(KEY_MQTT_PORT, port)
            .putString(KEY_MQTT_USER, user)
            .putString(KEY_MQTT_PASS, pass)
            .putString(KEY_MQTT_TOPIC, topic)
            .putString(KEY_WS_URL, wsUrl)
            .apply()

        deviceBundles.values.forEach { it.pumpRepository.reconnect() }
    }

    fun resetMqttConfigToDefaults() {
        getPrefs().edit().clear().apply()
        deviceBundles.values.forEach { it.pumpRepository.reconnect() }
    }

    fun getMqttHost(): String = getPrefs().getString(KEY_MQTT_HOST, null)
        ?.takeIf { it.isNotBlank() } ?: BuildConfig.MQTT_HOST

    fun getMqttPort(): Int = getPrefs().getInt(KEY_MQTT_PORT, 0)
        .takeIf { it > 0 } ?: BuildConfig.MQTT_PORT

    fun getMqttUser(): String = getPrefs().getString(KEY_MQTT_USER, null)
        ?.takeIf { it.isNotBlank() }
        ?: credentialRepository?.getCachedCredential()?.username
        ?: ""

    fun getMqttPass(): String = getPrefs().getString(KEY_MQTT_PASS, null)
        ?.takeIf { it.isNotBlank() }
        ?: credentialRepository?.getCachedCredential()?.password
        ?: ""

    fun getMqttTopic(): String = getPrefs().getString(KEY_MQTT_TOPIC, null)
        ?.takeIf { it.isNotBlank() } ?: BuildConfig.MQTT_TOPIC

    fun getWsUrl(): String {
        val saved = getPrefs().getString(KEY_WS_URL, null)?.takeIf { it.isNotBlank() }
        if (saved != null && !saved.contains("192.168.137.111")) {
            return saved
        }
        val host = getMqttHost()
        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
            return "ws://$host:82"
        }
        return BuildConfig.WEBSOCKET_URL
    }

    private fun getPrefs(): SharedPreferences {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
    }

    private const val TAG = "PumpRepositoryProvider"
    private const val CREDENTIAL_RETRY_MS = 10_000L
}
