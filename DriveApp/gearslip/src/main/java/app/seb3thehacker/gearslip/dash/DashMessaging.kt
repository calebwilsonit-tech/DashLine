package app.seb3thehacker.gearslip.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.compose.runtime.Immutable
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarAssistant
import app.seb3thehacker.gearslip.notify.CarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which notifications count as messages, and which conversations are muted for this drive.
 *
 * A notification is a message if it comes from a known chat app, or if it carries a reply action
 * or a conversation (MessagingStyle). Anything else (shopping, news, system) stays off the car.
 */
object DashMessages {
    private val CHAT_APPS = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging",
        "com.facebook.orca", // Messenger
        "com.facebook.mlite",
        "com.discord",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms", // Signal
        "com.instagram.android",
        "com.snapchat.android",
        "com.google.android.apps.dynamite", // Google Chat
        "com.Slack",
        "com.microsoft.teams",
        "com.groupme.android",
        "com.google.android.dialer", // missed calls and voicemail
    )

    /** Social apps that post conversation-styled notifications that aren't messages (Facebook memories). */
    private val NOT_CHAT = setOf("com.facebook.katana", "com.facebook.lite", "com.twitter.android", "com.reddit.frontpage")

    fun isMessage(n: CarNotification): Boolean =
        n.packageName !in NOT_CHAT && (n.packageName in CHAT_APPS || n.reply != null)

    /** Muted until the car disconnects, like Android Auto's per-conversation mute. */
    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    /** One conversation: the app plus its title (the sender or group name). */
    fun conversation(n: CarNotification) = "${n.packageName}|${n.title}"

    fun mute(n: CarNotification) { _muted.value = _muted.value + conversation(n) }
    fun unmute(n: CarNotification) { _muted.value = _muted.value - conversation(n) }
    fun isMuted(n: CarNotification, muted: Set<String>) = conversation(n) in muted
    fun clearMutes() { _muted.value = emptySet() }
}

/** A phone contact for the Contacts screen. */
@Immutable
data class DashContact(val name: String, val number: String, val starred: Boolean)

object DashContacts {
    fun load(context: Context): List<DashContact> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<DashContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    out += DashContact(name, number, c.getInt(2) == 1)
                }
            }
        }.onFailure { GearslipLog.w("dash contacts: ${it.message}") }
        // One row per person: the first number listed for them.
        return out.distinctBy { it.name }
    }
}

/**
 * A new text by voice: "What's your message to Sam?" → dictation → read back → sends after a
 * short countdown unless cancelled. Sent as a plain SMS, which also shows up in Google Messages.
 */
object DashText {
    enum class Phase { IDLE, ASKING, LISTENING, CONFIRMING, SENT, ERROR }

    @Immutable
    data class State(
        val phase: Phase = Phase.IDLE,
        val to: DashContact? = null,
        val heard: String = "",
        val error: String = "",
        /** uptimeMillis the countdown ends; 0 when not counting. */
        val sendAt: Long = 0,
    )

    private const val COUNTDOWN_MS = 4_000L
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var run = 0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) { appContext = context.applicationContext }

    fun start(to: DashContact) {
        val id = ++run
        main.removeCallbacksAndMessages(null)
        _state.value = State(Phase.ASKING, to)
        CarAssistant.say("What's your message to ${to.name.substringBefore(' ')}?") {
            if (id == run) listen(id)
        }
    }

    /** "Text Sam I'm on my way": the message is known, so go straight to the read-back. */
    fun startWith(to: DashContact, message: String) {
        val id = ++run
        main.removeCallbacksAndMessages(null)
        _state.value = State(Phase.CONFIRMING, to, heard = message)
        confirm(id, message)
    }

    private fun listen(id: Int) {
        _state.value = _state.value.copy(phase = Phase.LISTENING, heard = "")
        CarAssistant.dictate(
            onPartial = { if (id == run) _state.value = _state.value.copy(heard = it) },
            onResult = { text -> if (id == run) confirm(id, text) },
            onError = { msg -> if (id == run) fail(id, msg) },
        )
    }

    private fun confirm(id: Int, text: String) {
        if (text.isBlank()) return fail(id, "Didn't catch that")
        _state.value = _state.value.copy(phase = Phase.CONFIRMING, heard = text, sendAt = 0)
        CarAssistant.say("Sending: $text") {
            if (id != run) return@say
            val at = SystemClock.uptimeMillis() + COUNTDOWN_MS
            _state.value = _state.value.copy(sendAt = at)
            main.postAtTime({ if (id == run) send() }, at)
        }
    }

    fun sendNow() { if (_state.value.phase == Phase.CONFIRMING) send() }

    fun retry() { _state.value.to?.let(::start) }

    fun cancel() {
        run++
        main.removeCallbacksAndMessages(null)
        CarAssistant.stopListening()
        CarAssistant.stopSpeaking()
        _state.value = State()
    }

    private fun send() {
        val s = _state.value
        val to = s.to ?: return cancel()
        val context = appContext ?: return cancel()
        val id = ++run
        CarAssistant.stopListening()
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return fail(id, "Dashline needs permission to send texts")
        }
        val ok = runCatching {
            val sms = context.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(s.heard.trim())
            sms.sendMultipartTextMessage(to.number, null, parts, null, null)
        }.onFailure { GearslipLog.w("dash text: send failed: ${it.message}") }.isSuccess
        if (!ok) return fail(id, "The text didn't send")
        GearslipLog.i("dash text: sent")
        CarAssistant.say("Message sent.") {}
        _state.value = s.copy(phase = Phase.SENT, sendAt = 0)
        main.postDelayed({ if (id == run) _state.value = State() }, 2_000)
    }

    private fun fail(id: Int, message: String) {
        _state.value = _state.value.copy(phase = Phase.ERROR, error = message, sendAt = 0)
        main.postDelayed({ if (id == run) _state.value = State() }, 5_000)
    }
}
