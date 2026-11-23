package app.seb3thehacker.gearslip.dash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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

                        Text("Voice assistant (Gemini)", style = MaterialTheme.typography.titleMedium)
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
                        Text("Music", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Start music when the truck connects", Modifier.weight(1f))
                            Switch(checked = autoPlay, onCheckedChange = DashPrefs::setAutoPlay)
                        }
                    }
                }
            }
        }
    }
}
