@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.soundtouch.ersatz

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/** Ein Radiosender aus dem freien Verzeichnis radio-browser.info. */
data class Station(val name: String, val streamUrl: String, val imageUrl: String, val info: String)

/**
 * Sucht Radiosender im freien Verzeichnis radio-browser.info.
 * Gesendet wird nur der Suchbegriff, keine persönlichen Daten.
 */
object RadioBrowser {
    suspend fun search(query: String): List<Station> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL(
            "https://de1.api.radio-browser.info/json/stations/search?name=$q&limit=30" +
                "&hidebroken=true&order=clickcount&reverse=true",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "SoundTouch-App/1.0")
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val stream = o.optString("url_resolved").ifBlank { o.optString("url") }
                if (stream.isBlank()) return@mapNotNull null
                val details = listOf(
                    o.optString("country"),
                    o.optString("codec"),
                    o.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbit/s" } ?: "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                Station(o.optString("name").trim(), stream, o.optString("favicon"), details)
            }.distinctBy { it.streamUrl }
        } finally {
            conn.disconnect()
        }
    }
}

/** Die Alexa-Zentrale (D1 mini/ESP32) vermittelt die Radiosender für die Senderplätze. */
object Zentrale {
    private const val PORT = 8181
    private const val PATH = "/core02/svc-bmx-adapter-orion/prod/orion/station"

    fun load(context: Context): String =
        context.getSharedPreferences("zentrale", Context.MODE_PRIVATE).getString("host", "") ?: ""

    fun save(context: Context, host: String) {
        context.getSharedPreferences("zentrale", Context.MODE_PRIVATE).edit().putString("host", host.trim()).apply()
    }

    /** Adresse, die der Lautsprecher beim Abspielen des Senders abfragt. */
    fun stationLocation(host: String, station: Station): String {
        val json = JSONObject()
            .put("streamUrl", station.streamUrl)
            .put("name", station.name)
            .put("imageUrl", station.imageUrl)
            .toString()
        val data = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "http://$host:$PORT$PATH?data=$data"
    }

    /** Prüft, ob unter der Adresse eine Zentrale antwortet. */
    suspend fun isReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("http://$host:$PORT/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /** Sucht die Zentrale im Heimnetz (mDNS-Dienst _stzentrale._tcp). */
    @Suppress("DEPRECATION")
    suspend fun discover(context: Context): String? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("zentrale").apply { setReferenceCounted(false); acquire() }
        var found: NsdServiceInfo? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (found == null) found = serviceInfo
            }
        }
        try {
            nsd.discoverServices("_stzentrale._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            repeat(16) { if (found == null) delay(500) }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
        }
        val info = found ?: return null
        val resolved = withTimeoutOrNull(4000) {
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
        return resolved?.host?.hostAddress
    }
}

/** Die sechs runden Senderplatz-Tasten in der Steuerung. */
@Composable
fun PresetSection(api: SoundTouchApi, onManage: () -> Unit) {
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
    LaunchedEffect(api) { presets = runCatching { api.presets() }.getOrDefault(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Senderplätze", style = MaterialTheme.typography.titleMedium)
        listOf(1..3, 4..6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { id ->
                    val name = presets.firstOrNull { it.id == id }?.name.orEmpty()
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { scope.launch { runCatching { api.playPreset(id) } } },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (name.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (name.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.size(60.dp),
                        ) { Text("$id", style = MaterialTheme.typography.titleLarge) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            name.ifBlank { "leer" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.Radio, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Radiosender suchen und speichern")
        }
    }
}

/** Sendersuche: Sender finden, anhören und auf einem Senderplatz speichern. */
@Composable
fun StationsScreen(speaker: Speaker, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(speaker) { SoundTouchApi(speaker.host) }
    var zentrale by remember { mutableStateOf(Zentrale.load(context)) }
    var zentraleOk by remember { mutableStateOf<Boolean?>(null) }
    var manualHost by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Station>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Station?>(null) }

    fun checkZentrale() {
        scope.launch {
            zentraleOk = null
            var host = zentrale
            if (host.isBlank() || !Zentrale.isReachable(host)) {
                host = Zentrale.discover(context) ?: ""
            }
            if (host.isNotBlank() && Zentrale.isReachable(host)) {
                zentrale = host
                Zentrale.save(context, host)
                zentraleOk = true
            } else {
                zentraleOk = false
            }
        }
    }
    LaunchedEffect(Unit) { checkZentrale() }

    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            searching = true
            message = ""
            results = runCatching { RadioBrowser.search(query) }.getOrElse {
                message = "Suche fehlgeschlagen. Ist das Handy mit dem Internet verbunden?"
                emptyList()
            }
            if (results.isEmpty() && message.isEmpty()) message = "Keine Sender gefunden."
            searching = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Radiosender", speaker.name, onBack = onBack)

        when (zentraleOk) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Suche die Zentrale …")
            }
            false -> Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zentrale nicht gefunden", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Ohne Bose-Cloud braucht der Lautsprecher einen Vermittler im Heimnetz, um Radiosender abzuspielen. " +
                            "Das übernimmt die Zentrale auf deinem D1 mini. Ist sie eingesteckt und im WLAN?",
                    )
                    OutlinedTextField(
                        manualHost, { manualHost = it.trim() },
                        label = { Text("IP-Adresse der Zentrale (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        if (manualHost.isNotBlank()) zentrale = manualHost
                        checkZentrale()
                    }) { Text("Erneut suchen") }
                }
            }
            true -> Text(
                "Zentrale verbunden ($zentrale).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            query, { query = it },
            label = { Text("Sender suchen, z. B. WDR 2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            trailingIcon = {
                FilledIconButton(onClick = { runSearch() }, shape = CircleShape) {
                    Icon(Icons.Filled.Search, contentDescription = "Suchen")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Die Suche nutzt das freie Senderverzeichnis radio-browser.info. Gesendet wird nur dein Suchbegriff.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searching) CircularProgressIndicator()
        if (message.isNotEmpty()) Text(message)

        val sel = selected
        if (sel != null) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(sel.name, style = MaterialTheme.typography.titleLarge)
                    Text("Auf welchem Senderplatz speichern?")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..6).forEach { id ->
                            FilledIconButton(
                                enabled = zentraleOk == true,
                                shape = CircleShape,
                                modifier = Modifier.size(48.dp),
                                onClick = {
                                    scope.launch {
                                        val loc = Zentrale.stationLocation(zentrale, sel)
                                        message = if (runCatching { api.storeRadioPreset(id, sel.name, loc, sel.imageUrl) }.isSuccess) {
                                            "„${sel.name}“ ist auf Platz $id gespeichert."
                                        } else {
                                            "Speichern hat nicht geklappt."
                                        }
                                        selected = null
                                    }
                                },
                            ) { Text("$id", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    FilledTonalButton(
                        enabled = zentraleOk == true,
                        onClick = {
                            scope.launch {
                                val loc = Zentrale.stationLocation(zentrale, sel)
                                message = if (runCatching { api.playRadio(sel.name, loc, sel.imageUrl) }.isSuccess) {
                                    "Spiele „${sel.name}“ …"
                                } else {
                                    "Abspielen hat nicht geklappt."
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Jetzt anhören")
                    }
                }
            }
        }

        results.forEach { st ->
            Card(
                onClick = { selected = st },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Radio, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (st.info.isNotBlank()) {
                            Text(st.info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
