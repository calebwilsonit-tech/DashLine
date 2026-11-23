package app.seb3thehacker.gearslip.dash

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Immutable
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's own assistant (Google Assistant / Gemini: the one a long press on the power button
 * opens), started from the radio's mic button or the steering-wheel voice key.
 *
 * Android won't put another app's window on Dashline's car display, so the assistant's own panel
 * stays on the phone. Dashline mirrors what it says instead: the helper accessibility service
 * ([GarageService], whose config also covers the assistant packages) reads the text of the
 * assistant's window, and the car shows it in a bar along the bottom.
 *
 * Its spoken answers play wherever the assistant sends them (a separate audio stream that apps
 * can't capture), so they may come from the phone rather than the truck: to be checked in the truck.
 */
object PhoneAssistant {
    /** Packages whose windows are the assistant's panel. */
    val PACKAGES = setOf(
        "com.google.android.googlequicksearchbox", // Google app: Assistant / Gemini overlay
        "com.google.android.apps.bard", // Gemini app
    )

    @Immutable
    data class State(
        val active: Boolean = false,
        /** Latest text read off the assistant's panel: what was heard and what it answered. */
        val lines: List<String> = emptyList(),
        val error: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val pollTask = Runnable { poll() }
    private var startedAt = 0L
    private var seenWindow = false
    private const val POLL_MS = 300L
    private const val MAX_MS = 90_000L
    /** The assistant hasn't shown a window this long after starting: give up the bar. */
    private const val NO_WINDOW_MS = 6_000L

    /** True when the voice key should go to the phone's assistant (the default). */
    fun wantsVoiceKey(): Boolean = DashPrefs.assistant.value == DashPrefs.Assistant.PHONE

    /** Starts the phone's assistant, waking the phone if needed. */
    fun launch(context: Context) {
        val svc = Garage.service
        if (svc == null) {
            show(State(error = "Turn on \"Dashline garage button\" in the phone's Accessibility settings"))
            return
        }
        GearslipLog.i("assistant: starting the phone's assistant")
        startedAt = SystemClock.elapsedRealtime()
        seenWindow = false
        _state.value = State(active = true, lines = listOf("Starting your assistant…"))
        // Screen on first (it may be dark in a mount); the assistant works over the lock screen.
        svc.startActivity(
            Intent(svc, GarageWakeActivity::class.java)
                .putExtra(GarageWakeActivity.EXTRA_FOR, GarageWakeActivity.FOR_ASSISTANT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    /** Called by [GarageWakeActivity] once the screen is on. */
    internal fun screenReady(context: Context) {
        val launcher: Context = Garage.service ?: context
        val ok = runCatching {
            launcher.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { GearslipLog.w("assistant: voice command failed: ${it.message}") }.isSuccess ||
            runCatching {
                launcher.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        if (!ok) {
            show(State(error = "Couldn't start the phone's assistant"))
            return
        }
        main.removeCallbacks(pollTask)
        main.postDelayed(pollTask, POLL_MS)
    }

    /** Closes the assistant on the phone and the bar on the car. */
    fun close() {
        if (_state.value.active && seenWindow) Garage.service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        finish()
    }

    /** Assistant window events, forwarded by the helper service. */
    internal fun onAssistantEvent(svc: AccessibilityService) {
        if (_state.value.active) read(svc)
    }

    private fun poll() {
        if (!_state.value.active) return
        val svc = Garage.service ?: return finish()
        val open = read(svc)
        val age = SystemClock.elapsedRealtime() - startedAt
        when {
            open -> seenWindow = true
            seenWindow -> return finish() // it was up and now it's gone: the assistant closed
            age > NO_WINDOW_MS -> return finish()
        }
        if (age > MAX_MS) return finish()
        main.postDelayed(pollTask, POLL_MS)
    }

    /** Reads the assistant's window into the state; false if no assistant window is up. */
    private fun read(svc: AccessibilityService): Boolean {
        val roots = runCatching { svc.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .filter { it.packageName?.toString() in PACKAGES }
        if (roots.isEmpty()) return false
        val texts = mutableListOf<String>()
        roots.forEach { collect(it, texts, 0) }
        val lines = texts.map { it.trim() }.filter { it.length > 1 && it !in CHROME }.distinct().takeLast(4)
        if (lines.isNotEmpty() && lines != _state.value.lines) _state.value = _state.value.copy(lines = lines)
        return true
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || !n.isVisibleToUser) return
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    /** Button labels and hints that aren't part of the conversation. */
    private val CHROME = setOf("Share", "Copy", "Live", "Add photo", "Open Gemini", "Ask Gemini", "Type, talk or share a photo")

    private fun show(s: State) {
        _state.value = s
        main.postDelayed({ if (!_state.value.active) _state.value = State() }, 5_000)
    }

    private fun finish() {
        main.removeCallbacks(pollTask)
        _state.value = State()
    }
}
