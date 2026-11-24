package app.seb3thehacker.gearslip.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.call.CallUiState
import app.seb3thehacker.gearslip.call.CarCalls
import kotlinx.coroutines.delay

/**
 * Where call audio goes: the truck (Bluetooth), the phone's earpiece, or its speaker.
 * Uses Android's communication-device routing; Telecom decides in the end, so the button
 * shows what Android reports back rather than what was asked for.
 */
object CallAudio {
    enum class Route(val label: String) { TRUCK("Truck"), PHONE("Phone"), SPEAKER("Speaker") }

    fun current(context: Context): Route {
        val am = context.getSystemService(AudioManager::class.java) ?: return Route.TRUCK
        return when (am.communicationDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Route.PHONE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Route.SPEAKER
            else -> Route.TRUCK
        }
    }

    fun set(context: Context, route: Route): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val want = when (route) {
            Route.TRUCK -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            Route.PHONE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            Route.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
        val device = am.availableCommunicationDevices.firstOrNull { it.type in want } ?: return false
        return runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
            .also { GearslipLog.i("calls: audio to ${route.name} -> $it") }
    }

    fun next(r: Route) = Route.entries[(r.ordinal + 1) % Route.entries.size]
}

/** "Can't talk, driving" to whoever's calling, then decline. */
private fun textBack(context: Context, number: String) {
    if (number.isBlank()) return CarCalls.decline()
    if (context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
        runCatching {
            context.getSystemService(SmsManager::class.java)
                .sendTextMessage(number, null, "Driving right now, I'll call you back.", null, null)
        }.onFailure { GearslipLog.w("calls: text back failed: ${it.message}") }
    }
    CarCalls.decline()
}

/** An incoming call, over everything. */
@Composable
internal fun IncomingCall(c: CallUiState.Ringing) {
    val t = LocalDashTheme.current
    val context = LocalContext.current.applicationContext
    Box(Modifier.fillMaxSize().background(t.bgBottom.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(c.caller.label, 84.dp)
            Label(c.caller.label, 28.sp, weight = FontWeight.Bold)
            if (c.caller.name != null) Label(c.caller.number, 16.sp, t.textDim)
            Label("Incoming call", 15.sp, t.accent)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (c.canDecline) {
                    CallButton("Decline", Color(0xFFC62828), Modifier.width(150.dp)) { CarCalls.decline() }
                    CallButton("Text back", t.panelHi, Modifier.width(150.dp), textColor = t.text) { textBack(context, c.caller.number) }
                }
                CallButton("Answer", Color(0xFF1E9E4A), Modifier.width(150.dp)) { CarCalls.answer(context) }
            }
        }
    }
}

/** A call in progress: a panel that leaves the map visible, with every control the truck needs. */
@Composable
internal fun ActiveCall(c: CallUiState.Active, modifier: Modifier) {
    val t = LocalDashTheme.current
    val context = LocalContext.current.applicationContext
    val muted by CarCalls.muted.collectAsState()
    var route by remember { mutableStateOf(CallAudio.current(context)) }
    val now = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = SystemClock.uptimeMillis()
            delay(1_000)
        }
    }
    Card(modifier.width(330.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.caller?.label ?: "?", 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(c.caller?.label ?: "On a call", 18.sp, weight = FontWeight.SemiBold)
                    val secs = ((now.longValue - c.since) / 1000).coerceAtLeast(0)
                    Label("%d:%02d".format(secs / 60, secs % 60) + if (muted) "  ·  muted" else "", 14.sp, if (muted) Color(0xFFFF6B6B) else t.accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CallButton(if (muted) "Unmute" else "Mute", if (muted) Color(0xFFFF6B6B) else t.panelHi, Modifier.weight(1f), textColor = if (muted) Color.White else t.text) {
                    CarCalls.toggleMute()
                }
                CallButton("🔈 ${route.label}", t.panelHi, Modifier.weight(1f), textColor = t.text) {
                    val want = CallAudio.next(route)
                    CallAudio.set(context, want)
                    route = CallAudio.current(context)
                }
            }
            CallButton("End call", Color(0xFFC62828), Modifier.fillMaxWidth()) { CarCalls.end(context) }
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val t = LocalDashTheme.current
    Box(Modifier.size(size).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
        Label(name.trim().take(1).uppercase().ifEmpty { "?" }, (size.value * 0.42f).sp, t.bgBottom, FontWeight.Bold)
    }
}

@Composable
private fun CallButton(label: String, color: Color, modifier: Modifier, textColor: Color = Color.White, onTap: () -> Unit) {
    Tap(onTap = onTap, modifier = modifier.height(56.dp), color = color) {
        Label(label, 16.sp, textColor, FontWeight.SemiBold)
    }
}
