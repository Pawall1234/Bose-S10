package de.soundtouch.ersatz

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Ein gespeicherter Lautsprecher im Heimnetz. */
data class Speaker(val name: String, val host: String, val deviceId: String = "")

/** Speichert die Lautsprecher nur lokal auf dem Handy. */
class SpeakerStore(context: Context) {
    private val prefs = context.getSharedPreferences("speakers", Context.MODE_PRIVATE)

    fun load(): List<Speaker> = prefs.getString("list", "")!!
        .lines().filter { it.contains('|') }
        .map { line ->
            val parts = line.split('|')
            Speaker(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        }

    fun save(list: List<Speaker>) {
        prefs.edit().putString("list", list.joinToString("\n") { "${it.name.replace("|", " ")}|${it.host}|${it.deviceId}" }).apply()
    }

    /**
     * Übernimmt Suchergebnisse: Bekannte Lautsprecher (gleiche Geräte-ID, gleicher Name oder gleiche
     * Adresse) bekommen ihre neue Adresse, der gespeicherte Raumname bleibt. Neue werden ergänzt.
     */
    fun merge(found: List<Speaker>): List<Speaker> {
        val list = load().toMutableList()
        for (f in found) {
            val i = list.indexOfFirst {
                (f.deviceId.isNotEmpty() && it.deviceId == f.deviceId) ||
                    it.name.equals(f.name, ignoreCase = true) || it.host == f.host
            }
            if (i >= 0) {
                list[i] = list[i].copy(host = f.host, deviceId = f.deviceId.ifEmpty { list[i].deviceId })
            } else {
                list.add(f)
            }
        }
        save(list)
        return list
    }

    fun upsert(speaker: Speaker): List<Speaker> {
        val list = load().filterNot { it.host == speaker.host || it.name == speaker.name } + speaker
        save(list)
        return list
    }

    fun remove(speaker: Speaker): List<Speaker> {
        val list = load().filterNot { it == speaker }
        save(list)
        return list
    }
}

/**
 * Verbindet das Handy mit dem Einrichtungs-WLAN des Lautsprechers ("Bose SoundTouch …").
 * Android zeigt dazu einen Auswahldialog. Das Heim-WLAN bleibt für andere Apps erhalten.
 */
class HotspotConnector(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun connect(timeoutMs: Long = 120_000): Network? {
        release()
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("Bose", PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont: CancellableContinuation<Network?> ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
            }
        }
    }

    /** Adresse des Lautsprechers im Einrichtungs-WLAN (laut Community-Doku 192.0.2.1). */
    fun speakerAddress(network: Network): String {
        val lp = cm.getLinkProperties(network)
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null
        return dhcp ?: lp?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress ?: "192.0.2.1"
    }

    /** Leitet die ganze App (auch die WebView) über das Einrichtungs-WLAN. */
    fun bindProcess(network: Network) {
        cm.bindProcessToNetwork(network)
    }

    fun release() {
        cm.bindProcessToNetwork(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}

/** Sucht SoundTouch-Lautsprecher im Heimnetz über mDNS (Dienst _soundtouch._tcp). */
class SpeakerDiscovery(private val context: Context) {

    suspend fun discover(durationMs: Long = 8000): List<Speaker> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("soundtouch").apply { setReferenceCounted(false); acquire() }
        val found = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(found) { found.add(serviceInfo) }
            }
        }
        try {
            nsd.discoverServices("_soundtouch._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(durationMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val result = mutableListOf<Speaker>()
        for (info in synchronized(found) { found.toList() }) {
            val resolved = resolve(nsd, info) ?: continue
            @Suppress("DEPRECATION")
            val host = resolved.host?.hostAddress ?: continue
            if (result.none { it.host == host }) {
                // Name und Geräte-ID direkt beim Lautsprecher abfragen, damit er sich später wiedererkennen lässt.
                val info = runCatching { SoundTouchApi(host).info() }.getOrNull()
                result.add(Speaker(info?.name?.ifBlank { null } ?: resolved.serviceName, host, info?.deviceId ?: ""))
            }
        }
        lock.release()
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(4000) {
            suspendCancellableCoroutine { cont: CancellableContinuation<NsdServiceInfo?> ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            }
        }
}
