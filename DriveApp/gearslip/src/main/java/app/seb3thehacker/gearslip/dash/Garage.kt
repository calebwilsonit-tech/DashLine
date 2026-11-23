package app.seb3thehacker.gearslip.dash

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Immutable
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The garage button: opens the official myQ app and presses its door button.
 *
 * Chamberlain blocks every third-party route to the door (cloud API, Home Assistant, Google), so
 * the one thing still guaranteed to work is their own app. myQ's home screen tags its controls
 * (checked on myQ 5.325, 2026-11-23):
 *   device_name        "Garage Door"
 *   device_status      "Open for 36 minutes" / "Closed" / "Opening…"
 *   device_state_icon  the button that opens or closes the door
 * [GarageService] (an accessibility service limited to myQ) finds those and taps the button.
 *
 * The phone can be dark in a pocket: [GarageWakeActivity] turns the screen on and asks for the
 * lock screen to be dismissed, which succeeds without a PIN while the phone is unlocked by a
 * trusted device (Pixel Extend Unlock with the truck's Bluetooth).
 *
 * The flow is a small state machine on the main thread, polled every [POLL_MS], with a hard
 * timeout so a myQ redesign or a slow network fails loudly instead of hanging.
 */
object Garage {
    const val MYQ = "com.chamberlain.android.liftmaster.myq"

    private const val ID_BUTTON = "device_state_icon"
    private const val ID_STATUS = "device_status"
    private const val ID_NAME = "device_name"

    private const val POLL_MS = 200L
    private const val FIND_TIMEOUT_MS = 12_000L
    private const val AFTER_PRESS_MS = 2_500L

    enum class Phase { IDLE, WAKING, OPENING_MYQ, PRESSING, DONE, FAILED }

    @Immutable
    data class State(
        val phase: Phase = Phase.IDLE,
        val doorName: String = "Garage",
        /** myQ's own words, e.g. "Closed" or "Open for 3 minutes". Empty until first seen. */
        val status: String = "",
        /** elapsedRealtime when [status] was read. */
        val statusAt: Long = 0,
        /** Shown under the button: what's happening or what went wrong. */
        val message: String = "",
    ) {
        val busy get() = phase == Phase.WAKING || phase == Phase.OPENING_MYQ || phase == Phase.PRESSING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _serviceOn = MutableStateFlow(false)
    val serviceOn: StateFlow<Boolean> = _serviceOn.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    internal var service: GarageService? = null
        set(value) {
            field = value
            _serviceOn.value = value != null
        }

    private var dryRun = false
    private var lastBounds: Rect? = null
    private var startedAt = 0L
    /** Whether the phone's screen was off when the press began; if so it goes back off after. */
    private var wasDark = false

    /**
     * Opens or closes the door (whichever myQ's button does right now). With [dryRun] it goes
     * through every step but the tap, to check that the button can be found.
     */
    fun press(context: Context, dryRun: Boolean = false) {
        if (_state.value.busy) return
        val svc = service
        if (svc == null) {
            fail("Turn on \"Dashline garage button\" in the phone's Accessibility settings")
            return
        }
        if (svc.packageManager.getLaunchIntentForPackage(MYQ) == null) {
            fail("The myQ app isn't installed on the phone")
            return
        }
        this.dryRun = dryRun
        lastBounds = null
        startedAt = SystemClock.elapsedRealtime()
        wasDark = svc.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false
        GearslipLog.i("garage: ${if (dryRun) "dry run" else "press"} requested")
        update(Phase.WAKING, if (dryRun) "Testing - won't press…" else "Waking the phone…")
        // The accessibility service may start activities from the background.
        svc.startActivity(
            Intent(svc, GarageWakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    /** Called by [GarageWakeActivity] once the lock screen is out of the way (or isn't there). */
    internal fun unlocked(context: Context) {
        if (_state.value.phase != Phase.WAKING) return
        stopWaitingForUnlock()
        val launch = context.packageManager.getLaunchIntentForPackage(MYQ)
        if (launch == null) {
            fail("The myQ app isn't installed on the phone")
            return
        }
        update(Phase.OPENING_MYQ, "Opening myQ…")
        // From the accessibility service when there is one: it may start activities from the background.
        val launcher: Context = service ?: context
        launcher.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main.post(::poll)
    }

    /**
     * The phone is properly locked (Extend Unlock paused, or not near the truck). Instead of
     * failing, wait for the driver to unlock it (the lock screen is already lit) and carry on.
     */
    internal fun lockedOut(context: Context) {
        if (_state.value.phase != Phase.WAKING) return
        GearslipLog.i("garage: phone locked - waiting up to ${UNLOCK_WAIT_MS / 1000}s for an unlock")
        update(Phase.WAKING, "Unlock your phone to finish")
        val app = context.applicationContext
        stopWaitingForUnlock()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                GearslipLog.i("garage: phone unlocked - carrying on")
                unlocked(app)
            }
        }
        unlockReceiver = receiver
        unlockContext = app
        app.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
        main.postDelayed(unlockTimeout, UNLOCK_WAIT_MS)
    }

    private var unlockReceiver: android.content.BroadcastReceiver? = null
    private var unlockContext: Context? = null
    private val unlockTimeout = Runnable {
        stopWaitingForUnlock()
        if (_state.value.phase == Phase.WAKING) fail("Phone stayed locked, so the door wasn't touched")
    }
    private const val UNLOCK_WAIT_MS = 30_000L

    private fun stopWaitingForUnlock() {
        main.removeCallbacks(unlockTimeout)
        unlockReceiver?.let { r -> runCatching { unlockContext?.unregisterReceiver(r) } }
        unlockReceiver = null
    }

    private fun poll() {
        val phase = _state.value.phase
        if (phase != Phase.OPENING_MYQ) return
        if (SystemClock.elapsedRealtime() - startedAt > FIND_TIMEOUT_MS) {
            fail("Couldn't find the door button in myQ (is it signed in?)")
            goBack()
            return
        }
        val svc = service ?: return fail("Garage service stopped")
        val root = myQRoot(svc)
        val button = root?.let { find(it, ID_BUTTON) }
        if (button == null) {
            lastBounds = null
            main.postDelayed(::poll, POLL_MS)
            return
        }
        // Wait until myQ has finished sliding in: the button must be on screen and in the same
        // place on two polls in a row. (The first find often comes mid-animation, off the edge.)
        val where = bounds(button)
        val screen = svc.resources.displayMetrics
        val onScreen = button.isVisibleToUser && where.left >= 0 && where.right <= screen.widthPixels &&
            where.top >= 0 && where.bottom <= screen.heightPixels
        if (!onScreen || where != lastBounds) {
            lastBounds = where
            main.postDelayed(::poll, POLL_MS)
            return
        }
        readStatus(root)
        if (dryRun) {
            GearslipLog.i("garage: dry run found the button at ${bounds(button)}")
            finish("Found the door button ✓ (test only, nothing pressed)")
            goBack()
            return
        }
        update(Phase.PRESSING, "Pressing…")
        val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            tapCenter(svc, bounds(button))
        GearslipLog.i("garage: pressed=$clicked after ${SystemClock.elapsedRealtime() - startedAt}ms")
        if (!clicked) {
            fail("myQ didn't accept the press")
            goBack()
            return
        }
        // Give myQ a moment to show "Opening…"/"Closing…" so the car screen can echo it.
        main.postDelayed({
            service?.let { s -> myQRoot(s)?.let(::readStatus) }
            finish("Sent ✓")
            goBack()
        }, AFTER_PRESS_MS)
    }

    /** Any myQ event while idle refreshes the door status, so the car screen stays current. */
    internal fun onMyQEvent(svc: AccessibilityService) {
        if (_state.value.busy) return
        myQRoot(svc)?.let(::readStatus)
    }

    private fun myQRoot(svc: AccessibilityService): AccessibilityNodeInfo? {
        svc.rootInActiveWindow?.let { if (it.packageName == MYQ) return it }
        return svc.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == MYQ } }
    }

    private fun readStatus(root: AccessibilityNodeInfo) {
        val status = find(root, ID_STATUS)?.text?.toString()?.trim().orEmpty()
        val name = find(root, ID_NAME)?.text?.toString()?.trim().orEmpty()
        if (status.isEmpty() && name.isEmpty()) return
        if (status != _state.value.status) GearslipLog.i("garage: myQ says \"$name\" is \"$status\"")
        _state.value = _state.value.copy(
            status = status.ifEmpty { _state.value.status },
            doorName = name.ifEmpty { _state.value.doorName },
            statusAt = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * myQ's Compose UI reports its tags as bare ids ("device_state_icon"); classic views report
     * "package:id/name". Match either.
     */
    private fun find(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$MYQ:id/$id").firstOrNull()?.let { return it }
        root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        return walk(root) { it.viewIdResourceName == id || it.viewIdResourceName?.endsWith("/$id") == true }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int = 0, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun tapCenter(svc: AccessibilityService, r: Rect): Boolean {
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    /**
     * Leaves myQ for the home screen, and if the phone was dark before, puts it back to sleep.
     * To verify in the truck: whether this screen-off counts as a "manual lock" that pauses
     * Extend Unlock. If it does, drop the LOCK_SCREEN step and let the screen time out instead.
     */
    private fun goBack() {
        val svc = service ?: return
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // No LOCK_SCREEN here any more: on the truck, the second press of a drive found the phone
        // locked. An app-triggered lock counts as a manual lock and pauses Extend Unlock until the
        // next unlock. The screen now just times out on its own.
    }

    private fun update(phase: Phase, message: String) {
        _state.value = _state.value.copy(phase = phase, message = message)
    }

    private fun finish(message: String) {
        update(Phase.DONE, message)
        settleLater()
    }

    private fun fail(message: String) {
        GearslipLog.w("garage: $message")
        update(Phase.FAILED, message)
        settleLater()
    }

    /** Back to idle after a while, keeping the last status and message visible until then. */
    private fun settleLater() {
        main.postDelayed({
            val s = _state.value
            if (s.phase == Phase.DONE || s.phase == Phase.FAILED) _state.value = s.copy(phase = Phase.IDLE)
        }, 8_000)
    }
}

/** Limited to myQ by its config (res/xml/dash_garage_service.xml). */
class GarageService : AccessibilityService() {
    override fun onServiceConnected() {
        Garage.service = this
        GearslipLog.i("garage: service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg == Garage.MYQ) Garage.onMyQEvent(this)
        else if (pkg in PhoneAssistant.PACKAGES) PhoneAssistant.onAssistantEvent(this)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (Garage.service === this) Garage.service = null
        super.onDestroy()
    }
}

/**
 * Invisible: turns the screen on, asks for the lock screen to go away, then hands over to myQ.
 * A trusted-device unlock dismisses without a PIN; a real lock can't be, and that's reported.
 */
class GarageWakeActivity : Activity() {
    companion object {
        const val EXTRA_FOR = "for"
        const val FOR_ASSISTANT = "assistant"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (intent.getStringExtra(EXTRA_FOR) == FOR_ASSISTANT) {
            // The assistant runs over the lock screen, so don't wait on an unlock (on a phone that
            // isn't trusted-unlocked that waits for a fingerprint that never comes). This activity
            // has only turned the screen on.
            PhoneAssistant.screenReady(this)
            finish()
            return
        }
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            Garage.unlocked(this)
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Garage.unlocked(this@GarageWakeActivity)
                finish()
            }

            override fun onDismissCancelled() {
                Garage.lockedOut(this@GarageWakeActivity)
                finish()
            }

            override fun onDismissError() {
                Garage.lockedOut(this@GarageWakeActivity)
                finish()
            }
        })
    }
}
