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
import com.nndai.myhome.data.remote.WebSocketDeviceChannel
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

/**
 * Manual DI singleton for PumpRepository, LogRepository, and MqttCredentialRepository.
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

    @Volatile
    private var repository: PumpRepository? = null

    @Volatile
    private var logRepository: LogRepository? = null

    @Volatile
    private var credentialRepository: MqttCredentialRepository? = null

    @Volatile
    private var mqttConnectionManager: com.nndai.myhome.data.remote.MqttConnectionManager? = null

    @Volatile
    private var deviceHandshakeManager: com.nndai.myhome.data.remote.DeviceHandshakeManager? = null

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

    /**
     * Background sync loop to sync credentials from Supabase RPC with Android Keystore.
     * Waits until Supabase Auth session is Authenticated (restored/logged in) before calling RPC,
     * preventing 42501 (permission denied) errors during cold start session restoration.
     */
    private fun startBackgroundCredentialSync() {
        appScope.launch {
            SupabaseConfig.client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    Log.d(TAG, "startBackgroundCredentialSync(): Session authenticated. Syncing credentials...")
                    while (true) {
                        val repo = provideCredentialRepository()
                        when (val result = repo.syncWithRemote()) {
                            is CredentialSyncResult.Updated -> {
                                Log.d(TAG, "startBackgroundCredentialSync(): Credential updated! Reconnecting MQTT channel...")
                                repository?.reconnect()
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
                }
            }
        }
    }

    fun provide(): PumpRepository {
        return repository ?: synchronized(this) {
            repository ?: buildRepositories().first.also { repository = it }
        }
    }

    fun provideLogRepository(): LogRepository {
        return logRepository ?: synchronized(this) {
            logRepository ?: buildRepositories().second.also { logRepository = it }
        }
    }

    /**
     * Save custom MQTT config entered by user. Calls reconnect() after saving.
     */
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

        repository?.reconnect()
    }

    fun resetMqttConfigToDefaults() {
        getPrefs().edit().clear().apply()
        repository?.reconnect()
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

    /** Set active device ID for MQTT topic format devices/{deviceId}/cmd|up|log */
    fun setActiveDeviceId(deviceId: String) {
        getPrefs().edit().putString(KEY_DEVICE_ID, deviceId).apply()
        Log.d(TAG, "setActiveDeviceId(): $deviceId")
        repository?.reconnect()
    }

    fun getActiveDeviceId(): String = getPrefs().getString(KEY_DEVICE_ID, null)
        ?.takeIf { it.isNotBlank() } ?: ""

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

    private fun buildRepositories(): Pair<PumpRepository, LogRepository> {
        val ctx = appContext ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
        val envelope = DeviceCommandEnvelope(ctx)
        val mqttChannel = MqttDeviceChannel(
            hostProvider = { getMqttHost() },
            portProvider = { getMqttPort() },
            usernameProvider = { getMqttUser() },
            passwordProvider = { getMqttPass() },
            deviceIdProvider = { getActiveDeviceId() },
            envelopeProvider = { raw -> envelope.sign(getActiveDeviceId(), raw) },
            scope = appScope
        )
        val wsChannel = WebSocketDeviceChannel(
            urlProvider = { getWsUrl() },
            scope = appScope
        )
        val dataSource = PumpCommandDataSource(mqttChannel, appScope)
        val pumpRepo = PumpRepository(dataSource, mqttChannel, appScope)
        val logRepo = LogRepository(ctx, dataSource, appScope)
        logRepository = logRepo
        repository = pumpRepo
        return Pair(pumpRepo, logRepo)
    }

    private fun getPrefs(): SharedPreferences {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
    }

    private const val TAG = "PumpRepositoryProvider"
    private const val CREDENTIAL_RETRY_MS = 10_000L
}

