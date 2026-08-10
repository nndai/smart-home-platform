package com.nndai.myhome.data.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PairingDevice(
    val model: String,
    val ssid: String,
    val rssi: Int,
    val bssid: String
)

class WifiDeviceScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _devices = MutableStateFlow<List<PairingDevice>>(emptyList())
    val devices: StateFlow<List<PairingDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                _scanning.value = false
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (updated) refreshDevices()
            }
        }
    }
    private var receiverRegistered = false

    fun startScan() {
        if (!receiverRegistered) {
            appContext.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        _scanning.value = true
        val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (!started) {
            _scanning.value = false
            refreshDevices()
        }
    }

    fun refreshDevices() {
        val scanResults: List<android.net.wifi.ScanResult> = wifiManager.scanResults
        val devices = scanResults
            .filter { r -> r.SSID != null }
            .mapNotNull { r ->
                val match = MYHOME_PATTERN.matchEntire(r.SSID)
                if (match == null) return@mapNotNull null
                PairingDevice(
                    model = match.groupValues[1].lowercase(),
                    ssid = r.SSID,
                    rssi = r.level,
                    bssid = r.BSSID ?: ""
                )
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.rssi }
        Log.d(TAG, "refreshDevices(): total=${scanResults.size} matched=${devices.size}")
        devices.forEach { Log.d(TAG, "  found: ${it.ssid} rssi=${it.rssi} bssid=${it.bssid}") }
        _devices.value = devices
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private val MYHOME_PATTERN = Regex("^myhome-([a-z0-9]+)-[0-9a-fA-F]{4}$", RegexOption.IGNORE_CASE)
        private const val TAG = "MyHomeScanner"
    }
}
