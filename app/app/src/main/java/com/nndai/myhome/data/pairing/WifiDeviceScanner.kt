package com.nndai.myhome.data.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
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

/**
 * Real-Time Active Wi-Fi Device Scanner Engine.
 */
class WifiDeviceScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _devices = MutableStateFlow<List<PairingDevice>>(emptyList())
    val devices: StateFlow<List<PairingDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _isLocationOn = MutableStateFlow(true)
    val isLocationOn: StateFlow<Boolean> = _isLocationOn.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                _scanning.value = false
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "onReceive: SCAN_RESULTS_AVAILABLE_ACTION updated=$updated")
                refreshDevices()
            }
        }
    }
    private var receiverRegistered = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "onLocationChanged: Location probe completed, refreshing scan results")
            _scanning.value = false
            refreshDevices()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isLocationServicesEnabled(): Boolean {
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val enabled = gpsEnabled || networkEnabled
        _isLocationOn.value = enabled
        return enabled
    }

    fun startScan() {
        if (!receiverRegistered) {
            appContext.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        val locationOk = isLocationServicesEnabled()
        if (!locationOk) {
            Log.w(TAG, "startScan(): Location services (GPS) disabled on phone!")
        }

        _scanning.value = true
        // 1. Refresh using current scan results
        refreshDevices()

        // 2. Trigger wifiManager.startScan() directly without debounce
        val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
        Log.d(TAG, "startScan(): wifiManager.startScan() requested -> started=$started")

        // 3. Trigger Hardware Location Probe
        triggerHardwareLocationProbe()
    }

    /**
     * Hardware Location Probe: Requests single location update to force Android OS
     * hardware radio to perform an active Wi-Fi scan.
     */
    private fun triggerHardwareLocationProbe() {
        runCatching {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                Log.d(TAG, "triggerHardwareLocationProbe(): Requesting single location probe using provider=$provider")
                locationManager.requestSingleUpdate(provider, locationListener, appContext.mainLooper)
            } else {
                Log.w(TAG, "triggerHardwareLocationProbe(): No location provider enabled")
                _scanning.value = false
            }
        }.onFailure { e ->
            Log.e(TAG, "triggerHardwareLocationProbe() failed: ${e.message}")
            _scanning.value = false
        }
    }

    fun refreshDevices() {
        val scanResults: List<android.net.wifi.ScanResult> = runCatching {
            wifiManager.scanResults
        }.getOrElse { emptyList() }

        val devices = scanResults
            .mapNotNull { r ->
                val rawSsid = r.SSID?.replace("\"", "") ?: return@mapNotNull null
                if (rawSsid.isBlank() || rawSsid == "<unknown ssid>") return@mapNotNull null
                val match = MYHOME_PATTERN.matchEntire(rawSsid) ?: return@mapNotNull null
                PairingDevice(
                    model = match.groupValues[1].lowercase(),
                    ssid = rawSsid,
                    rssi = r.level,
                    bssid = r.BSSID ?: ""
                )
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.rssi }

        Log.d(TAG, "refreshDevices(): totalRaw=${scanResults.size} matchedMyHome=${devices.size}")
        devices.forEach { Log.d(TAG, "  found: ${it.ssid} rssi=${it.rssi} bssid=${it.bssid}") }
        _devices.value = devices
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    companion object {
        private val MYHOME_PATTERN = Regex("^myhome-([a-z0-9]+)-[0-9a-fA-F]{4}$", RegexOption.IGNORE_CASE)
        private const val TAG = "MyHomeScanner"
    }
}
