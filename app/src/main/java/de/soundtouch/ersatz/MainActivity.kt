@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.soundtouch.ersatz

import android.Manifest
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App() } }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp)) {
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
}

/** Passendes Symbol zum Raumnamen. */
private fun roomIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "küche" in n || "kueche" in n || "kitchen" in n -> Icons.Filled.Kitchen
        "bad" in n || "bath" in n -> Icons.Filled.Bathtub
        else -> Icons.Filled.Speaker
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

@Composable
private fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column {
            Text(title, style = if (onBack == null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, size: Int = 48, container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size((size * 0.5).dp))
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
    var showManual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(greeting(), if (speakers.isEmpty()) "Lass uns deine Lautsprecher einrichten." else "Deine Räume")

        if (speakers.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundIcon(Icons.Filled.Wifi, 56, MaterialTheme.colorScheme.surface)
                    Text("Noch keine Lautsprecher", style = MaterialTheme.typography.titleLarge)
                    Text("Verbinde deinen SoundTouch mit dem WLAN. Das dauert etwa zwei Minuten.")
                    Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.Wifi, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lautsprecher einrichten")
                    }
                }
            }
        }

        speakers.forEach { sp -> RoomCard(sp, onOpen = { onOpen(sp) }, onRemove = { onRemove(sp) }) }

        if (speakers.isNotEmpty()) {
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Weiteren Lautsprecher einrichten")
            }
        }

        FilledTonalButton(
            enabled = !searching,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                scope.launch {
                    searching = true
                    message = ""
                    val found = runCatching { SpeakerDiscovery(context).discover() }.getOrDefault(emptyList())
                    onFound(found)
                    message = if (found.isEmpty()) "Keine Lautsprecher gefunden. Sind sie im WLAN?" else "${found.size} Lautsprecher gefunden."
                    searching = false
                }
            },
        ) {
            if (searching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (searching) "Suche im Heimnetz …" else "Im Heimnetz suchen")
        }
        if (message.isNotEmpty()) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }

        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "IP-Eingabe ausblenden" else "IP-Adresse von Hand eintragen")
        }
        AnimatedVisibility(showManual) {
            Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(manualName, { manualName = it }, label = { Text("Raum, z. B. Küche") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        manualIp, { manualIp = it.trim() },
                        label = { Text("IP-Adresse aus dem Router") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = manualIp.isNotBlank(),
                        onClick = {
                            onFound(listOf(Speaker(manualName.ifBlank { manualIp }, manualIp)))
                            manualIp = ""; manualName = ""; showManual = false
                        },
                    ) { Text("Hinzufügen") }
                }
            }
        }

        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Nur im Heimnetz. Kein Konto, keine Cloud, keine Daten ins Internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        }
        Text(
            "Version $version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Karte für einen Raum mit Live-Status, Ein/Aus und Lautstärke direkt auf der Startseite. */
@Composable
private fun RoomCard(speaker: Speaker, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(speaker) { SoundTouchApi(speaker.host) }
    var now by remember { mutableStateOf<NowPlaying?>(null) }
    var online by remember { mutableStateOf<Boolean?>(null) }
    var volume by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(speaker) {
        while (true) {
            val np = runCatching { api.nowPlaying() }.getOrNull()
            online = np != null
            if (np != null) now = np
            if (!dragging) runCatching { api.volume() }.getOrNull()?.let { volume = it.toFloat() }
            delay(5000)
        }
    }

    val isOn = online == true && now?.standby == false
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(roomIcon(speaker.name), 48, if (isOn) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(speaker.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val np = now
                    val status = when {
                        online == null -> "Verbinde …"
                        online == false -> "Nicht erreichbar"
                        np == null || np.standby -> "Aus"
                        else -> np.track.ifBlank { np.station.ifBlank { quelleName(np.source) } }
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(
                    enabled = online == true,
                    onClick = { scope.launch { runCatching { api.key("POWER") } } },
                    colors = if (isOn) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = if (isOn) "Ausschalten" else "Einschalten")
                }
            }
            if (online == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = volume,
                        onValueChange = { dragging = true; volume = it },
                        onValueChangeFinished = {
                            dragging = false
                            scope.launch { runCatching { api.setVolume(volume.toInt()) } }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("${volume.toInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
                }
            } else if (online == false) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Entfernen")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- WLAN-Einrichtung

private enum class SetupStep { Intro, Connecting, Credentials, WebSetup, Sending, Searching, Done, Failed }

private fun SetupStep.progressIndex() = when (this) {
    SetupStep.Intro -> 0
    SetupStep.Connecting -> 1
    SetupStep.Credentials, SetupStep.WebSetup, SetupStep.Sending, SetupStep.Failed -> 2
    SetupStep.Searching -> 3
    SetupStep.Done -> 4
}

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
    var setupNetwork by remember { mutableStateOf<Network?>(null) }
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
            setupNetwork = network
            deviceType = info.type
            networks = runCatching { a.wirelessSiteSurvey() }.getOrDefault(emptyList())
            step = SetupStep.Credentials
        }
    }

    suspend fun searchAfterSetup() {
        password = ""
        connector.release()
        setupNetwork = null
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
        val r = found?.let { Speaker(wanted.ifBlank { it.name }, it.host) }
        result = r
        // Namen erst im Heimnetz setzen, damit die Einrichtung nicht gestört wird.
        if (r != null && wanted.isNotBlank()) runCatching { SoundTouchApi(r.host).setName(wanted) }
        step = SetupStep.Done
    }

    fun openWebSetup() {
        val n = setupNetwork ?: return
        connector.bindProcess(n)
        step = SetupStep.WebSetup
    }

    fun sendCredentials() {
        scope.launch {
            step = SetupStep.Sending
            error = ""
            val a = api ?: return@launch
            // 1. Weg der eingebauten Einrichtungsseite (Port 80), 2. API /addWirelessProfile.
            val viaForm = runCatching { a.configureWifiViaWebForm(ssid.trim(), password) }
            val sent = if (viaForm.getOrDefault(false)) {
                Result.success(Unit)
            } else {
                runCatching { a.addWirelessProfile(ssid.trim(), password) }.let { r ->
                    if (r.isFailure && viaForm.isFailure) {
                        Result.failure(Exception("Formular: ${viaForm.exceptionOrNull()?.message}; API: ${r.exceptionOrNull()?.message}"))
                    } else r
                }
            }
            if (sent.isFailure) {
                // Manchmal antwortet der Lautsprecher nicht mehr, weil er schon ins Heim-WLAN wechselt.
                // Ist er noch im Einrichtungs-WLAN erreichbar, hat er die Daten wirklich nicht angenommen.
                val stillInSetup = runCatching { a.info() }.isSuccess
                if (stillInSetup) {
                    error = "Der Lautsprecher hat die WLAN-Daten nicht angenommen (${sent.exceptionOrNull()?.message}). " +
                        "Bitte WLAN-Name und Passwort prüfen oder die Einrichtungsseite nutzen."
                    step = SetupStep.Credentials
                    return@launch
                }
            }
            searchAfterSetup()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startConnect() }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("WLAN einrichten", "Schritt ${minOf(step.progressIndex() + 1, 4)} von 4", onBack = { onDone(null) })
        LinearProgressIndicator(
            progress = { step.progressIndex() / 4f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )

        when (step) {
            SetupStep.Intro -> {
                InstructionRow(1, "Lautsprecher einschalten.")
                InstructionRow(2, "Die Tasten „2“ und „Lautstärke −“ gleichzeitig gedrückt halten, bis die WLAN-Anzeige orange leuchtet.")
                InstructionRow(3, "Raum eingeben und auf „Verbinden“ tippen. Android fragt dann, ob es sich mit „Bose SoundTouch …“ verbinden darf. Bitte bestätigen.")
                Text("Welcher Raum?", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Küche", "Bad", "Wohnzimmer").forEach { r ->
                        FilterChip(selected = speakerName == r, onClick = { speakerName = r }, label = { Text(r) })
                    }
                }
                OutlinedTextField(
                    speakerName, { speakerName = it },
                    label = { Text("Oder eigenen Namen eingeben") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { permissionLauncher.launch(permissions) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Verbinden")
                }
            }

            SetupStep.Connecting -> BusyCard("Verbinde mit dem Lautsprecher …", "Bitte die Android-Abfrage bestätigen.")

            SetupStep.Credentials -> {
                StatusBanner(Icons.Filled.CheckCircle, "Verbunden mit ${deviceType.ifBlank { "SoundTouch" }}", ok = true)
                Text("Wähle dein Heim-WLAN und gib das Passwort ein. Es geht nur an den Lautsprecher und wird nicht gespeichert.")
                if (networks.isNotEmpty()) {
                    Text("Gefundene Netze", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        networks.take(8).forEach { n ->
                            Card(
                                onClick = { ssid = n },
                                shape = MaterialTheme.shapes.small,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (ssid == n) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Wifi, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (ssid == n) Icon(Icons.Filled.CheckCircle, contentDescription = "Ausgewählt", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(ssid, { ssid = it }, label = { Text("WLAN-Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("WLAN-Passwort") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Passwort verbergen" else "Passwort anzeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AssistChip(
                    onClick = { clipboard.getText()?.text?.trim()?.let { if (it.isNotEmpty()) password = it } },
                    label = { Text("Passwort einfügen") },
                    leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (error.isNotEmpty()) StatusBanner(Icons.Filled.ErrorOutline, error, ok = false)
                Text(
                    "Hinweis: Der SoundTouch 10 nutzt vermutlich nur 2,4 GHz. Das WLAN muss im Router auf 2,4 GHz verfügbar sein.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = ssid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = { sendCredentials() },
                ) { Text("WLAN-Daten senden") }
                OutlinedButton(onClick = { openWebSetup() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.Language, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Einrichtungsseite des Lautsprechers öffnen")
                }
            }

            SetupStep.WebSetup -> {
                Text("Das ist die eingebaute Einrichtungsseite des Lautsprechers. Trage dort dein Heim-WLAN ein und schicke es ab. Tippe danach unten auf „Fertig“.")
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl("http://" + (setupNetwork?.let { connector.speakerAddress(it) } ?: "192.0.2.1") + "/")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(520.dp),
                    )
                }
                Button(
                    onClick = { scope.launch { searchAfterSetup() } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Fertig, Lautsprecher im Heimnetz suchen") }
                TextButton(onClick = { step = SetupStep.Credentials }) { Text("Zurück") }
            }

            SetupStep.Sending -> BusyCard("Sende WLAN-Daten …", "Einen Moment bitte.")
            SetupStep.Searching -> BusyCard(
                "Der Lautsprecher wechselt ins Heim-WLAN",
                "Das dauert etwa eine halbe Minute. Danach leuchtet die WLAN-Anzeige weiß.",
            )

            SetupStep.Done -> {
                val r = result
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoundIcon(Icons.Filled.CheckCircle, 56, MaterialTheme.colorScheme.surface)
                        if (r != null) {
                            Text("Geschafft!", style = MaterialTheme.typography.headlineSmall)
                            Text("„${r.name}“ ist jetzt im WLAN.")
                        } else {
                            Text("WLAN-Daten gesendet", style = MaterialTheme.typography.headlineSmall)
                            Text("Ich habe den Lautsprecher im Heimnetz noch nicht gefunden. Warte eine Minute und tippe auf der Startseite auf „Im Heimnetz suchen“.")
                        }
                    }
                }
                Button(onClick = { onDone(r) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Zur Startseite") }
            }

            SetupStep.Failed -> {
                StatusBanner(Icons.Filled.ErrorOutline, error, ok = false)
                Button(onClick = { step = SetupStep.Intro }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Noch einmal") }
                if (setupNetwork != null) {
                    OutlinedButton(onClick = { openWebSetup() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Einrichtungsseite des Lautsprechers öffnen")
                    }
                }
                TextButton(onClick = { onDone(null) }) { Text("Abbrechen") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InstructionRow(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatusBanner(icon: ImageVector, text: String, ok: Boolean) {
    val bg = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Spacer(Modifier.width(12.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BusyCard(title: String, subtitle: String) {
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

    val np = now
    val isOn = online && np != null && !np.standby
    val playing = np?.playStatus == "PLAY_STATE"

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader(speaker.name, onBack = onBack)

        // Große „Läuft gerade“-Karte mit Farbverlauf
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                )
                .padding(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (isOn) Icons.Filled.MusicNote else roomIcon(speaker.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
                val title = when {
                    !online -> "Nicht erreichbar"
                    np == null -> "Lade …"
                    np.standby -> "Ausgeschaltet"
                    else -> np.track.ifBlank { np.station.ifBlank { quelleName(np.source) } }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    !online -> "Ist der Lautsprecher im WLAN?"
                    np == null || np.standby -> "Tippe auf Ein/Aus, um ihn einzuschalten."
                    np.artist.isNotBlank() -> "${np.artist} · ${quelleName(np.source)}"
                    else -> quelleName(np.source)
                }
                Text(sub, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
            }
        }

        // Wiedergabe-Steuerung
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { send { api.key("POWER") } }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Ein oder aus")
            }
            IconButton(enabled = isOn, onClick = { send { api.key("PREV_TRACK") } }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück", modifier = Modifier.size(32.dp))
            }
            FilledIconButton(enabled = isOn, onClick = { send { api.key("PLAY_PAUSE") } }, modifier = Modifier.size(76.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Abspielen",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(enabled = isOn, onClick = { send { api.key("NEXT_TRACK") } }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", modifier = Modifier.size(32.dp))
            }
        }

        // Lautstärke
        Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lautstärke", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${volume.toInt()}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        volume = (volume - 5).coerceAtLeast(0f); send { api.setVolume(volume.toInt()) }
                    }) { Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Leiser") }
                    Slider(
                        value = volume,
                        onValueChange = { dragging = true; volume = it },
                        onValueChangeFinished = {
                            dragging = false
                            send { api.setVolume(volume.toInt()) }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        volume = (volume + 5).coerceAtMost(100f); send { api.setVolume(volume.toInt()) }
                    }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Lauter") }
                }
            }
        }

        // Quellen
        Text("Quelle", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceTile(Icons.Filled.Cable, "AUX", selected = np?.source == "AUX", modifier = Modifier.weight(1f)) { send { api.selectSource("AUX") } }
            SourceTile(Icons.Filled.Bluetooth, "Bluetooth", selected = np?.source == "BLUETOOTH", modifier = Modifier.weight(1f)) { send { api.selectSource("BLUETOOTH") } }
        }
        HorizontalDivider()
        Text(
            "Spotify: In der Spotify-App auf „Geräte“ tippen und „${speaker.name}“ wählen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Adresse: ${speaker.host}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SourceTile(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun quelleName(source: String) = when (source) {
    "AUX" -> "AUX"
    "BLUETOOTH" -> "Bluetooth"
    "SPOTIFY" -> "Spotify"
    "AIRPLAY" -> "AirPlay"
    "STANDBY" -> "Standby"
    "INVALID_SOURCE", "" -> "Keine Quelle"
    else -> source
}
