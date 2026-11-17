package de.soundtouch.ersatz

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data class Control(val speaker: Speaker) : Screen
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { SpeakerStore(context) }
    var speakers by remember { mutableStateOf(store.load()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                speakers = speakers,
                onOpen = { screen = Screen.Control(it) },
                onSetup = { screen = Screen.Setup },
                onFound = { found -> found.forEach { speakers = store.upsert(it) } },
                onRemove = { speakers = store.remove(it) },
            )
            Screen.Setup -> SetupScreen(
                onDone = { speaker ->
                    if (speaker != null) speakers = store.upsert(speaker)
                    screen = Screen.Home
                },
            )
            is Screen.Control -> ControlScreen(s.speaker, onBack = { screen = Screen.Home })
        }
    }
}

// ---------------------------------------------------------------- Startseite

@Composable
private fun HomeScreen(
    speakers: List<Speaker>,
    onOpen: (Speaker) -> Unit,
    onSetup: () -> Unit,
    onFound: (List<Speaker>) -> Unit,
    onRemove: (Speaker) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Meine Lautsprecher", style = MaterialTheme.typography.headlineMedium)

        if (speakers.isEmpty()) {
            Text("Noch keine Lautsprecher. Richte zuerst das WLAN ein oder suche im Heimnetz.")
        }
        speakers.forEach { sp ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(sp) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sp.name, style = MaterialTheme.typography.titleLarge)
                        Text(sp.host, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRemove(sp) }) { Text("Entfernen") }
                }
            }
        }

        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Lautsprecher mit WLAN verbinden")
        }

        FilledTonalButton(
            enabled = !searching,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                scope.launch {
                    searching = true
                    message = "Suche im Heimnetz …"
                    val found = runCatching { SpeakerDiscovery(context).discover() }.getOrDefault(emptyList())
                    onFound(found)
                    message = if (found.isEmpty()) "Keine Lautsprecher gefunden. Sind sie im WLAN?" else "${found.size} gefunden."
                    searching = false
                }
            },
        ) { Text(if (searching) "Suche läuft …" else "Im Heimnetz suchen") }

        if (message.isNotEmpty()) Text(message)

        Spacer(Modifier.height(8.dp))
        Text("Oder IP-Adresse von Hand eintragen", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(manualName, { manualName = it }, label = { Text("Name, z. B. Küche") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            manualIp, { manualIp = it.trim() },
            label = { Text("IP-Adresse aus dem Router") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = manualIp.isNotBlank(),
            onClick = {
                onFound(listOf(Speaker(manualName.ifBlank { manualIp }, manualIp)))
                manualIp = ""; manualName = ""
            },
        ) { Text("Hinzufügen") }

        Spacer(Modifier.height(8.dp))
        Text(
            "Die App arbeitet nur in deinem Heimnetz. Sie hat kein Konto, keine Cloud und sendet keine Daten ins Internet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------- WLAN-Einrichtung

private enum class SetupStep { Intro, Connecting, Credentials, Sending, Searching, Done, Failed }

@Composable
private fun SetupScreen(onDone: (Speaker?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connector = remember { HotspotConnector(context) }
    DisposableEffect(Unit) { onDispose { connector.release() } }

    var step by remember { mutableStateOf(SetupStep.Intro) }
    var speakerName by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var networks by remember { mutableStateOf<List<String>>(emptyList()) }
    var api by remember { mutableStateOf<SoundTouchApi?>(null) }
    var deviceType by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Speaker?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun startConnect() {
        scope.launch {
            step = SetupStep.Connecting
            error = ""
            val network = connector.connect()
            if (network == null) {
                error = "Keine Verbindung zum Lautsprecher-WLAN. Leuchtet die WLAN-Anzeige orange? Dann noch einmal versuchen."
                step = SetupStep.Failed
                return@launch
            }
            val a = SoundTouchApi(connector.speakerAddress(network), network)
            val info = runCatching { a.info() }.getOrNull()
            if (info == null) {
                error = "Mit dem WLAN verbunden, aber der Lautsprecher antwortet nicht. Bitte erneut versuchen."
                step = SetupStep.Failed
                return@launch
            }
            api = a
            deviceType = info.type
            networks = runCatching { a.wirelessSiteSurvey() }.getOrDefault(emptyList())
            step = SetupStep.Credentials
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startConnect() }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WLAN einrichten", style = MaterialTheme.typography.headlineMedium)

        when (step) {
            SetupStep.Intro -> {
                Text("1. Lautsprecher einschalten.")
                Text("2. Die Tasten „2“ und „Lautstärke −“ gleichzeitig gedrückt halten, bis die WLAN-Anzeige orange leuchtet.")
                Text("3. Einen Namen vergeben und auf „Verbinden“ tippen. Android fragt dann, ob es sich mit „Bose SoundTouch …“ verbinden darf. Bitte bestätigen.")
                OutlinedTextField(
                    speakerName, { speakerName = it },
                    label = { Text("Name, z. B. Küche oder Bad") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { permissionLauncher.launch(permissions) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Verbinden") }
                TextButton(onClick = { onDone(null) }) { Text("Abbrechen") }
            }

            SetupStep.Connecting -> Busy("Verbinde mit dem Lautsprecher …")

            SetupStep.Credentials -> {
                Text("Verbunden mit ${deviceType.ifBlank { "SoundTouch" }}.", fontWeight = FontWeight.Bold)
                Text("Wähle jetzt dein Heim-WLAN und gib das Passwort ein. Es wird nur an den Lautsprecher geschickt und nicht gespeichert.")
                if (networks.isNotEmpty()) {
                    Text("Gefundene Netze:", style = MaterialTheme.typography.titleSmall)
                    networks.forEach { n ->
                        OutlinedButton(onClick = { ssid = n }, modifier = Modifier.fillMaxWidth()) { Text(n) }
                    }
                }
                OutlinedTextField(ssid, { ssid = it }, label = { Text("WLAN-Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("WLAN-Passwort") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.getText()?.text?.trim()?.let { if (it.isNotEmpty()) password = it }
                    }) { Text("Passwort einfügen") }
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Verbergen" else "Anzeigen")
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Text(
                    "Hinweis: Der SoundTouch 10 nutzt vermutlich nur 2,4 GHz. Das WLAN muss im Router auf 2,4 GHz verfügbar sein.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = ssid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = {
                        scope.launch {
                            step = SetupStep.Sending
                            error = ""
                            val a = api ?: return@launch
                            val sent = runCatching { a.addWirelessProfile(ssid.trim(), password) }
                            if (sent.isFailure) {
                                // Manchmal antwortet der Lautsprecher nicht mehr, weil er schon ins Heim-WLAN wechselt.
                                // Ist er noch im Einrichtungs-WLAN erreichbar, hat er die Daten wirklich nicht angenommen.
                                val stillInSetup = runCatching { a.info() }.isSuccess
                                if (stillInSetup) {
                                    error = "Der Lautsprecher hat die WLAN-Daten nicht angenommen (${sent.exceptionOrNull()?.message}). " +
                                        "Bitte WLAN-Name und Passwort prüfen und noch einmal senden."
                                    step = SetupStep.Credentials
                                    return@launch
                                }
                            }
                            password = ""
                            connector.release()
                            step = SetupStep.Searching
                            delay(25_000)
                            val wanted = speakerName.trim()
                            var found: Speaker? = null
                            repeat(4) {
                                if (found == null) {
                                    val list = runCatching { SpeakerDiscovery(context).discover() }.getOrDefault(emptyList())
                                    found = list.firstOrNull { wanted.isNotBlank() && it.name.equals(wanted, ignoreCase = true) }
                                        ?: list.singleOrNull()
                                }
                            }
                            result = found?.let { Speaker(wanted.ifBlank { it.name }, it.host) }
                            // Namen erst im Heimnetz setzen, damit die Einrichtung nicht gestört wird.
                            result?.let { r -> if (wanted.isNotBlank()) runCatching { SoundTouchApi(r.host).setName(wanted) } }
                            step = SetupStep.Done
                        }
                    },
                ) { Text("WLAN-Daten senden") }
            }

            SetupStep.Sending -> Busy("Sende WLAN-Daten …")
            SetupStep.Searching -> Busy("Der Lautsprecher wechselt ins Heim-WLAN. Das dauert etwa eine halbe Minute …")

            SetupStep.Done -> {
                val r = result
                if (r != null) {
                    Text("Fertig! „${r.name}“ ist im WLAN unter ${r.host}.", fontWeight = FontWeight.Bold)
                } else {
                    Text("Die WLAN-Daten wurden gesendet. Ich habe den Lautsprecher im Heimnetz aber noch nicht gefunden.")
                    Text("Warte eine Minute und tippe auf der Startseite auf „Im Heimnetz suchen“. Leuchtet die WLAN-Anzeige weiß, ist er verbunden.")
                }
                Button(onClick = { onDone(r) }, modifier = Modifier.fillMaxWidth()) { Text("Zur Startseite") }
            }

            SetupStep.Failed -> {
                Text(error)
                Button(onClick = { step = SetupStep.Intro }, modifier = Modifier.fillMaxWidth()) { Text("Noch einmal") }
                TextButton(onClick = { onDone(null) }) { Text("Abbrechen") }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text)
    }
}

// ---------------------------------------------------------------- Steuerung

@Composable
private fun ControlScreen(speaker: Speaker, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(speaker) { SoundTouchApi(speaker.host) }
    var now by remember { mutableStateOf<NowPlaying?>(null) }
    var volume by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }

    LaunchedEffect(speaker) {
        while (true) {
            val np = runCatching { api.nowPlaying() }.getOrNull()
            online = np != null
            if (np != null) now = np
            if (!dragging) runCatching { api.volume() }.getOrNull()?.let { volume = it.toFloat() }
            delay(3000)
        }
    }

    fun send(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text(speaker.name, style = MaterialTheme.typography.headlineMedium)

        val np = now
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                when {
                    !online -> Text("Lautsprecher nicht erreichbar. Ist er im WLAN?")
                    np == null -> Text("Lade …")
                    np.standby -> Text("Aus (Standby)")
                    else -> {
                        Text(np.track.ifBlank { np.station.ifBlank { quelleName(np.source) } }, style = MaterialTheme.typography.titleLarge)
                        if (np.artist.isNotBlank()) Text(np.artist)
                        Text("Quelle: ${quelleName(np.source)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { send { api.key("POWER") } }) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Ein oder aus")
            }
            FilledTonalIconButton(onClick = { send { api.key("PREV_TRACK") } }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
            }
            FilledTonalIconButton(onClick = { send { api.key("PLAY_PAUSE") } }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Abspielen oder Pause")
            }
            FilledTonalIconButton(onClick = { send { api.key("NEXT_TRACK") } }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
            }
        }

        Text("Lautstärke: ${volume.toInt()}")
        Slider(
            value = volume,
            onValueChange = { dragging = true; volume = it },
            onValueChangeFinished = {
                dragging = false
                send { api.setVolume(volume.toInt()) }
            },
            valueRange = 0f..100f,
        )

        Text("Quelle", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { send { api.selectSource("AUX") } }) { Text("AUX") }
            OutlinedButton(onClick = { send { api.selectSource("BLUETOOTH") } }) { Text("Bluetooth") }
        }
        Text(
            "Spotify: in der Spotify-App auf „Geräte“ tippen und „${speaker.name}“ wählen (Spotify Connect).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun quelleName(source: String) = when (source) {
    "AUX" -> "AUX"
    "BLUETOOTH" -> "Bluetooth"
    "SPOTIFY" -> "Spotify"
    "AIRPLAY" -> "AirPlay"
    "STANDBY" -> "Standby"
    "INVALID_SOURCE", "" -> "Keine"
    else -> source
}
