package com.nndai.myhome.data.di

import android.content.Context
import android.content.SharedPreferences
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.remote.ChannelKind
import com.nndai.myhome.data.remote.HybridDeviceChannel
import com.nndai.myhome.data.remote.MqttDeviceChannel
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.remote.WebSocketDeviceChannel
import com.nndai.myhome.data.repository.LogRepository
import com.nndai.myhome.data.repository.PumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI singleton cho PumpRepository và LogRepository.
 */
object PumpRepositoryProvider {

    private const val PREFS_NAME = "mqtt_config"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_MQTT_PORT = "mqtt_port"
    private const val KEY_MQTT_USER = "mqtt_user"
    private const val KEY_MQTT_PASS = "mqtt_pass"
    private const val KEY_MQTT_TOPIC = "mqtt_topic"
    private const val KEY_WS_URL = "ws_url"

    @Volatile
    private var repository: PumpRepository? = null

    @Volatile
    private var logRepository: LogRepository? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null

    /** Phải gọi init() trong Application hoặc MainActivity trước provide(). */
    fun init(context: Context) {
        appContext = context.applicationContext
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
     * Lưu MQTT config do user nhập. Gọi reconnect() sau khi save.
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
        // Rebuild repository with new config
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
        ?.takeIf { it.isNotBlank() } ?: BuildConfig.MQTT_USERNAME

    fun getMqttPass(): String = getPrefs().getString(KEY_MQTT_PASS, null)
        ?.takeIf { it.isNotBlank() } ?: BuildConfig.MQTT_PASSWORD

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

    private fun buildRepositories(): Pair<PumpRepository, LogRepository> {
        val ctx = appContext ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
        val mqttChannel = MqttDeviceChannel(
            hostProvider = { getMqttHost() },
            portProvider = { getMqttPort() },
            usernameProvider = { getMqttUser() },
            passwordProvider = { getMqttPass() },
            baseTopicProvider = { getMqttTopic() },
            scope = appScope
        )
        val wsChannel = WebSocketDeviceChannel(
            urlProvider = { getWsUrl() },
            scope = appScope
        )
        val hybrid = HybridDeviceChannel(
            entries = listOf(
                HybridDeviceChannel.ChannelEntry(ChannelKind.MQTT, mqttChannel),
                HybridDeviceChannel.ChannelEntry(ChannelKind.WEBSOCKET, wsChannel)
            ),
            scope = appScope
        )
        val dataSource = PumpCommandDataSource(hybrid, appScope)
        val pumpRepo = PumpRepository(dataSource, hybrid, appScope)
        val logRepo = LogRepository(ctx, dataSource, appScope)
        logRepository = logRepo
        repository = pumpRepo
        return Pair(pumpRepo, logRepo)
    }

    private fun getPrefs(): SharedPreferences {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("PumpRepositoryProvider.init() not called")
    }
}
