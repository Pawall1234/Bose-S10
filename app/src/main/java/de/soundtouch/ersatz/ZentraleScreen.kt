@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.soundtouch.ersatz

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Lautsprecher, wie die Zentrale ihn bei Alexa anmeldet. */
data class ZentraleSpeaker(val name: String, val alexaName: String, val ip: String)

data class ZentraleStatus(
    val version: String,
    val ip: String,
    val lastAlexa: String,
    val scanning: Boolean,
    val speakers: List<ZentraleSpeaker>,
)

/** Kleine JSON-Schnittstelle der Zentrale (Port 8181, ab Firmware zentrale-8). */
object ZentraleApi {
    private const val PORT = 8181

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private suspend fun call(host: String, pathAndQuery: String): ZentraleStatus = withContext(Dispatchers.IO) {
        val conn = URL("http://$host:$PORT$pathAndQuery").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("Die Zentrale hat noch eine alte Firmware. Bitte zentrale-8 oder neuer aufspielen.")
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = JSONObject(text)
            if (code !in 200..299) throw IOException(json.optString("error", "Fehler $code"))
            parse(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): ZentraleStatus {
        val arr = json.optJSONArray("speakers")
        val list = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            ZentraleSpeaker(o.optString("name"), o.optString("alexaName"), o.optString("ip"))
        }
        return ZentraleStatus(
            version = json.optString("version"),
            ip = json.optString("ip"),
            lastAlexa = json.optString("lastAlexa"),
            scanning = json.optBoolean("scanning"),
            speakers = list,
        )
    }

    suspend fun status(host: String) = call(host, "/api/status")
    suspend fun scan(host: String) = call(host, "/api/scan")
    suspend fun add(host: String, name: String, ip: String) = call(host, "/api/add?name=${enc(name)}&ip=${enc(ip)}")
    suspend fun rename(host: String, name: String, alexa: String) = call(host, "/api/rename?name=${enc(name)}&alexa=${enc(alexa)}")

    /** Entfernen startet die Zentrale neu; die Antwort kann deshalb ausbleiben. */
    suspend fun remove(host: String, name: String) {
        runCatching { call(host, "/api/remove?name=${enc(name)}") }
    }
}

@Composable
fun ZentraleScreen(appSpeakers: List<Speaker>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(Zentrale.load(context)) }
    var found by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<ZentraleStatus?>(null) }
    var error by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ZentraleSpeaker?>(null) }
    var removeTarget by remember { mutableStateOf<ZentraleSpeaker?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun locate() {
        scope.launch {
            found = null
            var h = host
            if (h.isBlank() || !Zentrale.isReachable(h)) h = Zentrale.discover(context) ?: ""
            if (h.isNotBlank() && Zentrale.isReachable(h)) {
                host = h
                Zentrale.save(context, h)
                found = true
            } else {
                found = false
            }
        }
    }
    LaunchedEffect(Unit) { locate() }

    // Solange die Seite offen ist, alle 4 Sekunden den Stand holen.
    LaunchedEffect(found, host) {
        if (found != true) return@LaunchedEffect
        while (true) {
            runCatching { ZentraleApi.status(host) }
                .onSuccess { status = it; error = "" }
                .onFailure { error = it.message ?: "Zentrale antwortet nicht." }
            delay(4000)
        }
    }

    fun act(block: suspend () -> ZentraleStatus?) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { s -> if (s != null) status = s; error = "" }
                .onFailure { error = it.message ?: "Fehler" }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Alexa-Zentrale", "Dein D1 mini im Heimnetz", onBack = onBack)

        when (found) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Suche die Zentrale …")
            }
            false -> Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zentrale nicht gefunden", style = MaterialTheme.typography.titleLarge)
                    Text("Ist der D1 mini eingesteckt und im WLAN? In der FritzBox heißt er soundtouch-zentrale.")
                    OutlinedTextField(
                        manualHost, { manualHost = it.trim() },
                        label = { Text("IP-Adresse der Zentrale (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        if (manualHost.isNotBlank()) host = manualHost
                        locate()
                    }) { Text("Erneut suchen") }
                }
            }
            true -> {
                val s = status
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (error.isEmpty()) "Verbunden" else "Verbindung gestört", style = MaterialTheme.typography.titleLarge)
                        Text("Adresse $host" + (s?.version?.takeIf { it.isNotBlank() }?.let { " · Firmware $it" } ?: ""))
                        Text("Letzter Alexa-Befehl: ${s?.lastAlexa ?: "…"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s?.scanning == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Sucht gerade nach Lautsprechern …")
                            }
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)

                Text("Bei Alexa angemeldet", style = MaterialTheme.typography.titleMedium)
                if (s != null && s.speakers.isEmpty()) {
                    Text("Noch keine Lautsprecher. Tippe auf „Jetzt suchen“ oder übernimm sie unten aus der App.")
                }
                s?.speakers?.forEach { sp ->
                    Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("„${sp.alexaName}“", style = MaterialTheme.typography.titleMedium)
                                Text("${sp.name} · ${sp.ip}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { renameTarget = sp }) { Icon(Icons.Filled.Edit, contentDescription = "Alexa-Namen ändern") }
                            IconButton(onClick = { removeTarget = sp }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Entfernen") }
                        }
                    }
                }

                FilledTonalButton(
                    enabled = !busy && s?.scanning != true,
                    onClick = { act { ZentraleApi.scan(host) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Jetzt suchen")
                }

                // Lautsprecher, die die App kennt, die Zentrale aber noch nicht
                val missing = appSpeakers.filter { a -> s?.speakers?.none { it.name.equals(a.name, true) || it.ip == a.host } == true }
                missing.forEach { a ->
                    Button(
                        enabled = !busy,
                        onClick = { act { ZentraleApi.add(host, a.name, a.host) } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${a.name} zu Alexa hinzufügen")
                    }
                }

                Text(
                    "Nach jeder Änderung: „Alexa, suche Geräte“ sagen. Alte Einträge löschst du in der Alexa-App.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    renameTarget?.let { sp ->
        var newName by remember(sp) { mutableStateOf(sp.alexaName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Name für Alexa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("So sprichst du ${sp.name} an, z. B. „Alexa, schalte $newName ein“. Nimm keinen Namen, den schon ein Raum oder eine Gruppe in Alexa hat.")
                    OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val target = sp
                    renameTarget = null
                    act { ZentraleApi.rename(host, target.name, newName.trim()) }
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Abbrechen") } },
        )
    }

    removeTarget?.let { sp ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("${sp.name} entfernen?") },
            text = { Text("Die Zentrale startet dafür kurz neu. Den Eintrag „${sp.alexaName}“ löschst du danach auch in der Alexa-App.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = sp
                    removeTarget = null
                    act {
                        ZentraleApi.remove(host, target.name)
                        delay(6000)
                        ZentraleApi.status(host)
                    }
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Abbrechen") } },
        )
    }
}
