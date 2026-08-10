package com.nndai.myhome.data.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log

class DeviceApConnector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var currentCallback: ConnectivityManager.NetworkCallback? = null

    fun connectToDeviceAp(
        ssid: String,
        password: String? = null,
        onConnected: (gatewayIp: String) -> Unit,
        onFailed: (String) -> Unit
    ) {
        disconnect()

        Log.d(TAG, "connectToDeviceAp: requesting WifiNetworkSpecifier for SSID='$ssid' pass=${password != null}")

        val builder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
        if (!password.isNullOrBlank()) {
            builder.setWpa2Passphrase(password)
        }
        val specifier = builder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable: network=$network")
                val linkProps = connectivityManager.getLinkProperties(network)
                dumpLinkProperties(network, linkProps)
                val gateway = linkProps?.routes
                    ?.asSequence()
                    ?.filter { it.hasGateway() }
                    ?.firstOrNull()
                    ?.gateway?.hostAddress
                Log.d(TAG, "onAvailable: extracted gateway='$gateway'")
                if (gateway != null) {
                    val ok = runCatching {
                        ConnectivityManager.setProcessDefaultNetwork(network)
                        Log.d(TAG, "onAvailable: setProcessDefaultNetwork OK for $network")
                        true
                    }.getOrElse { e ->
                        Log.e(TAG, "setProcessDefaultNetwork FAILED: ${e.message}", e)
                        false
                    }
                    if (!ok) {
                        Log.w(TAG, "onAvailable: process default network not set, WS may fail")
                    }
                    onConnected(gateway)
                } else {
                    Log.e(TAG, "onAvailable: no gateway in LinkProperties")
                    onFailed("Không lấy được địa chỉ thiết bị")
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "onCapabilitiesChanged: network=$network, caps=$networkCapabilities")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "onLinkPropertiesChanged: network=$network")
                dumpLinkProperties(network, linkProperties)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "onLost: network=$network")
            }

            override fun onUnavailable() {
                Log.e(TAG, "onUnavailable: cannot connect to AP (timeout/rejected)")
                onFailed("Không thể kết nối tới thiết bị. Hãy thử lại.")
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                Log.d(TAG, "onBlockedStatusChanged: network=$network blocked=$blocked")
            }
        }
        currentCallback = callback
        connectivityManager.requestNetwork(request, callback)
        Log.d(TAG, "connectToDeviceAp: requestNetwork submitted")
    }

    private fun dumpLinkProperties(network: Network, lp: LinkProperties?) {
        if (lp == null) {
            Log.w(TAG, "LinkProperties for $network is NULL")
            return
        }
        val sb = StringBuilder("LinkProperties($network): ")
        sb.append("iface=").append(lp.interfaceName).append(" | ")
        sb.append("ipAddrs=").append(lp.linkAddresses.joinToString(",")).append(" | ")
        sb.append("routes=").append(lp.routes.joinToString(";") { r ->
            (if (r.hasGateway()) "dst=${r.destination} gw=${r.gateway}" else "dst=${r.destination} (no gw)")
        })
        Log.d(TAG, sb.toString())
    }

    fun disconnect() {
        currentCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            Log.d(TAG, "disconnect: unregistered callback")
            currentCallback = null
        }
    }

    companion object {
        private const val TAG = "MyHomeApConnector"
    }
}
