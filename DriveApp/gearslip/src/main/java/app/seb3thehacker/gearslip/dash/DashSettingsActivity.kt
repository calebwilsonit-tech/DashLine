package app.seb3thehacker.gearslip.dash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Dashline's settings on the phone itself, for what can't be typed on the car screen: the Gemini
 * API key the assistant uses, and music auto-play.
 */
class DashSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashPrefs.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var key by remember { mutableStateOf(DashPrefs.geminiKey(this)) }
                    var model by remember { mutableStateOf(DashPrefs.geminiModel(this)) }
                    var show by remember { mutableStateOf(false) }
                    var saved by remember { mutableStateOf(false) }
                    val autoPlay by DashPrefs.autoPlay.collectAsState()
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Dashline settings", style = MaterialTheme.typography.headlineSmall)

                        val assistant by DashPrefs.assistant.collectAsState()
                        Text("Voice assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "What the radio's mic button and the steering-wheel voice button start.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        for (a in DashPrefs.Assistant.entries) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = a == assistant, onClick = { DashPrefs.setAssistant(a) })
                                Text(a.label)
                            }
                        }
                        Text("Dashline assistant (Gemini API)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Dashline's assistant uses your own free Gemini API key from Google AI Studio. " +
                                "It's stored only on this phone. Without one, the assistant still handles " +
                                "\"call…\", \"text…\" and \"play…\".",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                        }) { Text("Get a free key in Google AI Studio") }
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it; saved = false },
                            label = { Text("Gemini API key") },
                            singleLine = true,
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show key", Modifier.weight(1f))
                            Switch(checked = show, onCheckedChange = { show = it })
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; saved = false },
                            label = { Text("Model (leave as is unless you know you want another)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            DashPrefs.saveGemini(this@DashSettingsActivity, key, model.ifBlank { DashPrefs.DEFAULT_MODEL })
                            saved = true
                        }) { Text(if (saved) "Saved ✓" else "Save") }

                        Spacer(Modifier.padding(4.dp))
                        Text("Maps", style = MaterialTheme.typography.titleMedium)
                        remember { DashNav.init(this@DashSettingsActivity); true }
                        val noGoogle by DashNav.noGoogle.collectAsState()
                        val imperial by DashNav.imperial.collectAsState()
                        val voiceSpeed by DashNav.voiceSpeed.collectAsState()
                        val buildings3d by DashNav.buildings3d.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Units: ${if (imperial) "Miles & mph" else "Kilometers & km/h"}")
                                Text("Applies to distances, speedometer, speed limits, and spoken turns.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = imperial, onCheckedChange = DashNav::setImperial)
                        }
                        Text("Spoken directions speed", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.85f to "Slow", 1.0f to "Normal", 1.15f to "Brisk", 1.3f to "Fast").forEach { (rate, label) ->
                                val selected = kotlin.math.abs(voiceSpeed - rate) < 0.05f
                                if (selected) {
                                    Button(onClick = { DashNav.setVoiceSpeed(rate) }) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { DashNav.setVoiceSpeed(rate) }) { Text(label) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("3D buildings")
                                Text("Show extruded 3D buildings when zoomed in.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = buildings3d, onCheckedChange = DashNav::setBuildings3d)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Google-free maps")
                                Text("Search and routes from open data only: no live traffic, weaker business search.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = noGoogle, onCheckedChange = DashNav::setNoGoogle)
                        }
                        OfflineMaps()

                        Spacer(Modifier.padding(4.dp))
                        Text("Accent color", style = MaterialTheme.typography.titleMedium)
                        Text("Your own color on any theme. Pick the first to use the theme's.", style = MaterialTheme.typography.bodySmall)
                        val accent by DashPrefs.accent.collectAsState()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0L) + DashThemes.ACCENTS).forEach { c ->
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(34.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(if (c == 0L) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color(c))
                                        .border(if (c == accent) 3.dp else 0.dp, androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.CircleShape)
                                        .clickable { DashPrefs.setAccent(c) },
                                )
                            }
                        }

                        Spacer(Modifier.padding(4.dp))
                        Text("Music", style = MaterialTheme.typography.titleMedium)
                        val pauseOnDisconnect by DashPrefs.pauseOnDisconnect.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Start music when the truck connects", Modifier.weight(1f))
                            Switch(checked = autoPlay, onCheckedChange = DashPrefs::setAutoPlay)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Stop music when unplugged from the car", Modifier.weight(1f))
                            Switch(checked = pauseOnDisconnect, onCheckedChange = DashPrefs::setPauseOnDisconnect)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Offline map regions (Vela's routing files): routes without signal, and the posted speed limit
 * on the truck screen. Suggested: the region you're in. Big files, so best on Wi-Fi.
 */
@androidx.compose.runtime.Composable
private fun OfflineMaps() {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { DashOffline.loadCatalog(context) }
    val st by DashOffline.state.collectAsState()
    var filter by remember { mutableStateOf("") }
    val here = remember {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        runCatching {
            @Suppress("MissingPermission")
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }
    Text("Offline maps", style = MaterialTheme.typography.titleMedium)
    Text(
        "Download your state for routes and speed limits where there's no signal (with signal they work anyway). " +
            "Add its map tiles to keep the map itself drawing in dead zones. Big files, so use Wi-Fi.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (st.error.isNotEmpty()) Text(st.error, color = MaterialTheme.colorScheme.error)
    // Boxes only approximate a region: skip ones wrapping the date line, smallest (most local) first.
    val suggested = here?.let { h ->
        st.catalog.filter { it.covers(h.latitude, h.longitude) && it.e - it.w < 60 }
            .sortedBy { (it.n - it.s) * (it.e - it.w) }.take(2)
    }.orEmpty()
    val installed = st.catalog.filter { it.id in st.installed }
    val matches = if (filter.length >= 2) st.catalog.filter { it.name.contains(filter, ignoreCase = true) }.take(12) else emptyList()
    val rows = (installed + suggested + matches).distinctBy { it.id }
    if (st.catalog.isEmpty() && st.error.isEmpty()) Text("Loading the region list…")
    OutlinedTextField(
        value = filter,
        onValueChange = { filter = it },
        label = { Text("Find a state or country") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    rows.forEach { r ->
        val pct = st.downloading[r.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name)
                Text(
                    when {
                        pct != null -> "Downloading… $pct%"
                        r.id in st.installed -> "Installed · ${r.sizeMb} MB"
                        r in suggested -> "Where you are · ${r.sizeMb} MB"
                        else -> "${r.sizeMb} MB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                pct != null -> Unit
                r.id in st.installed -> OutlinedButton(onClick = { DashOffline.delete(r) }) { Text("Delete") }
                else -> Button(onClick = { DashOffline.download(r) }) { Text("Download") }
            }
        }
        // The map itself without signal: Vela's basemap tiles for the same region.
        st.tiles[r.id]?.let { tr ->
            val tp = st.tilesDownloading[tr.id]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    when {
                        tp != null -> "Map tiles: downloading\u2026 $tp%"
                        tr.id in st.tilesInstalled -> "Map tiles installed \u00b7 ${tr.sizeMb} MB"
                        else -> "Map tiles for no signal \u00b7 ${tr.sizeMb} MB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    tp != null -> Unit
                    tr.id in st.tilesInstalled -> OutlinedButton(onClick = { DashOffline.deleteTiles(tr) }) { Text("Delete") }
                    else -> OutlinedButton(onClick = { DashOffline.downloadTiles(tr) }) { Text("Get") }
                }
            }
        }
        // Offline place & address search without signal: Vela's SQLite place pack for the same region.
        st.places[r.id]?.let { pr ->
            val pp = st.placesDownloading[pr.id]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    when {
                        pp != null -> "Place pack: downloading\u2026 $pp%"
                        pr.id in st.placesInstalled -> "Place pack installed \u00b7 ${pr.sizeMb} MB"
                        else -> "Place pack for offline search \u00b7 ${pr.sizeMb} MB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    pp != null -> Unit
                    pr.id in st.placesInstalled -> OutlinedButton(onClick = { DashOffline.deletePlaces(pr) }) { Text("Delete") }
                    else -> OutlinedButton(onClick = { DashOffline.downloadPlaces(pr) }) { Text("Get") }
                }
            }
        }
    }
}
