package edu.dlsu.esca_3_2526_c3.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requests to the ESP32 boards go over the Wi-Fi network (DMS-NET has no internet,
 * so Android would otherwise send them over mobile data). Everything else, such as
 * Firebase, keeps using the phone's normal network. requestNetwork() also keeps
 * Android from dropping a Wi-Fi network without internet.
 */
class WifiNet(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile var network: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { network = n }
        override fun onLost(n: Network) { if (network == n) network = null }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.requestNetwork(request, callback) } catch (_: Exception) { }
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        network = null
    }

    fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        val n = network
        val c = (if (n != null) n.openConnection(u) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        return c
    }

    fun getBytes(url: String, timeoutMs: Int = 1500): ByteArray {
        val c = open(url, timeoutMs)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun getText(url: String, timeoutMs: Int = 1500): String = String(getBytes(url, timeoutMs), Charsets.UTF_8)
}
