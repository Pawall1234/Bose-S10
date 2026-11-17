package de.soundtouch.ersatz

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Infos, die der Lautsprecher über sich selbst meldet (Antwort von /info). */
data class DeviceInfo(val name: String, val type: String, val deviceId: String)

/** Was gerade läuft (Antwort von /now_playing). */
data class NowPlaying(
    val source: String,
    val track: String,
    val artist: String,
    val station: String,
    val playStatus: String,
) {
    val standby: Boolean get() = source == "STANDBY"
}

/**
 * Lokale Steuerung eines Bose SoundTouch über die offengelegte Web-API (HTTP, Port 8090).
 * Mit [network] werden alle Anfragen über ein bestimmtes Netz geschickt, z. B. über den
 * Einrichtungs-Hotspot des Lautsprechers, der kein Internet hat.
 */
class SoundTouchApi(private val host: String, private val network: Network? = null) {

    private fun open(path: String): HttpURLConnection {
        val url = URL("http://$host:8090$path")
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 10000
        return conn
    }

    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = open(path)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} bei $path")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = open(path)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} bei $path")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun info(): DeviceInfo {
        val xml = get("/info")
        return DeviceInfo(
            name = Xml.text(xml, "name"),
            type = Xml.text(xml, "type"),
            deviceId = Xml.attr(xml, "info", "deviceID"),
        )
    }

    suspend fun nowPlaying(): NowPlaying {
        val xml = get("/now_playing")
        return NowPlaying(
            source = Xml.attr(xml, "nowPlaying", "source"),
            track = Xml.text(xml, "track"),
            artist = Xml.text(xml, "artist"),
            station = Xml.text(xml, "stationName"),
            playStatus = Xml.text(xml, "playStatus"),
        )
    }

    suspend fun volume(): Int = Xml.text(get("/volume"), "actualvolume").toIntOrNull() ?: 0

    suspend fun setVolume(value: Int) {
        post("/volume", "<volume>${value.coerceIn(0, 100)}</volume>")
    }

    /** Simuliert einen Tastendruck, z. B. POWER, PLAY_PAUSE, NEXT_TRACK, PREV_TRACK. */
    suspend fun key(name: String) {
        post("/key", "<key state=\"press\" sender=\"Gabbo\">$name</key>")
        post("/key", "<key state=\"release\" sender=\"Gabbo\">$name</key>")
    }

    /** Quelle wählen: "AUX" oder "BLUETOOTH". */
    suspend fun selectSource(source: String) {
        val account = if (source == "AUX") " sourceAccount=\"AUX\"" else ""
        post("/select", "<ContentItem source=\"$source\"$account></ContentItem>")
    }

    suspend fun setName(name: String) {
        post("/name", "<name>${Xml.escape(name)}</name>")
    }

    /** Lässt den Lautsprecher nach WLAN-Netzen in der Umgebung suchen. Liefert die Netznamen. */
    suspend fun wirelessSiteSurvey(): List<String> {
        val xml = post("/performWirelessSiteSurvey", "<PerformWirelessSiteSurvey timeout=\"5\"/>")
        return Xml.allAttrs(xml, "ssid").filter { it.isNotBlank() }.distinct()
    }

    /** Übergibt die Zugangsdaten des Heim-WLANs. Danach wechselt der Lautsprecher ins Heimnetz. */
    suspend fun addWirelessProfile(ssid: String, password: String, securityType: String = "wpa_or_wpa2") {
        post(
            "/addWirelessProfile",
            "<AddWirelessProfile><profile ssid=\"${Xml.escape(ssid)}\" " +
                "password=\"${Xml.escape(password)}\" securityType=\"$securityType\" /></AddWirelessProfile>",
        )
    }
}

/** Sehr kleine XML-Hilfen, reichen für die kurzen Antworten der SoundTouch-API. */
object Xml {
    fun text(xml: String, tag: String): String =
        Regex("<$tag(\\s[^>]*)?>([^<]*)</$tag>").find(xml)?.groupValues?.get(2)?.let(::unescape)?.trim() ?: ""

    fun attr(xml: String, tag: String, attr: String): String {
        val open = Regex("<$tag(\\s[^>]*)?>").find(xml)?.value ?: return ""
        return Regex("\\s$attr=\"([^\"]*)\"").find(open)?.groupValues?.get(1)?.let(::unescape) ?: ""
    }

    fun allAttrs(xml: String, attr: String): List<String> =
        Regex("\\s$attr=\"([^\"]*)\"").findAll(xml).map { unescape(it.groupValues[1]) }.toList()

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
