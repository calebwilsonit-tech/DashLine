package app.seb3thehacker.gearslip.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.seb3thehacker.gearslip.car.VoiceReply
import app.seb3thehacker.gearslip.car.CarAssistant.Phase as A
import app.seb3thehacker.gearslip.car.placeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contacts: favourites up top, then everyone A–Z, with a letter strip to jump. Each person has
 * Call and Text (by voice). Taps in the scrolling list fire on finger-up so a scroll can't call
 * someone by accident.
 */
@Composable
internal fun ContactsScreen() {
    val t = LocalDashTheme.current
    val context = LocalContext.current.applicationContext
    val contacts by produceState<List<DashContact>?>(null) {
        value = withContext(Dispatchers.IO) { DashContacts.load(context) }
    }
    val list = contacts
    if (list == null) {
        Label("Loading contacts…", 16.sp, t.textDim)
        return
    }
    if (list.isEmpty()) {
        Label("No contacts found (or Dashline can't read them yet).", 16.sp, t.textDim, maxLines = 2)
        return
    }
    val favourites = remember(list) { list.filter { it.starred } }
    // Rows: favourites first, then everyone; letters index into "everyone".
    val rows = remember(list) { favourites + list }
    val letters = remember(list) {
        list.withIndex()
            .groupBy { it.value.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#' }
            .mapValues { favourites.size + it.value.first().index }
            .toSortedMap()
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows.size) { i ->
                if (i == 0 && favourites.isNotEmpty()) Label("Favourites", 14.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                if (i == favourites.size) Label("All contacts", 14.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                ContactRow(rows[i]) { placeCall(context, rows[i].number) }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Letter strip: tap a letter to jump there.
        Column(
            Modifier.width(34.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for ((letter, index) in letters) {
                Tap(
                    onTap = { scope.launch { state.scrollToItem(index) } },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = Color.Transparent,
                    outline = false,
                ) { Label(letter.toString(), 12.sp, t.textDim, FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ContactRow(c: DashContact, onCall: () -> Unit) {
    val t = LocalDashTheme.current
    Card(Modifier.fillMaxWidth().height(60.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(t.panelHi),
                contentAlignment = Alignment.Center,
            ) { Label(c.name.take(1).uppercase(), 16.sp, t.accent, FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(c.name, 17.sp, weight = FontWeight.SemiBold)
                Label(c.number, 13.sp, t.textDim)
            }
            Tap(
                onTap = { DashText.start(c) },
                onTapUp = true,
                modifier = Modifier.size(76.dp, 44.dp),
                color = t.panelHi,
            ) { Label("Text", 15.sp) }
            Spacer(Modifier.width(8.dp))
            Tap(
                onTap = onCall,
                onTapUp = true,
                modifier = Modifier.size(76.dp, 44.dp),
                color = Color(0xFF1E9E4A),
            ) { Label("Call", 15.sp, Color.White, FontWeight.SemiBold) }
        }
    }
}

/**
 * The strip along the bottom while a message is read out, a reply is dictated, or a new text is
 * being composed: what was heard, and Cancel / Send now.
 */
@Composable
internal fun VoiceOverlay(modifier: Modifier) {
    val t = LocalDashTheme.current
    val reply by VoiceReply.state.collectAsState()
    val text by DashText.state.collectAsState()
    val assistant by app.seb3thehacker.gearslip.car.CarAssistant.state.collectAsState()

    val replying = reply.phase != VoiceReply.Phase.IDLE
    val texting = text.phase != DashText.Phase.IDLE
    val asking = assistant.phase != app.seb3thehacker.gearslip.car.CarAssistant.Phase.IDLE
    if (!replying && !texting && !asking) return

    val title: String
    val body: String
    val canSend: Boolean
    val onCancel: () -> Unit
    val onSend: () -> Unit
    if (asking && !texting && !replying) {
        title = when (assistant.phase) {
            A.LISTENING -> if (assistant.ready) "Listening…" else "One moment…"
            A.THINKING -> "Thinking…"
            A.SPEAKING -> assistant.heard.ifBlank { "Dashline" }
            A.ERROR -> "Assistant"
            A.IDLE -> ""
        }
        body = when (assistant.phase) {
            A.SPEAKING, A.ERROR -> assistant.reply
            else -> assistant.heard
        }
        canSend = false
        onCancel = app.seb3thehacker.gearslip.car.CarAssistant::cancel
        onSend = {}
    } else if (texting) {
        val who = text.to?.name.orEmpty()
        title = when (text.phase) {
            DashText.Phase.ASKING -> "Text to $who"
            DashText.Phase.LISTENING -> "Listening… (to $who)"
            DashText.Phase.CONFIRMING -> "Sending to $who"
            DashText.Phase.SENT -> "Sent to $who ✓"
            DashText.Phase.ERROR -> text.error
            DashText.Phase.IDLE -> ""
        }
        body = text.heard
        canSend = text.phase == DashText.Phase.CONFIRMING
        onCancel = DashText::cancel
        onSend = DashText::sendNow
    } else {
        val who = reply.target?.title.orEmpty()
        title = when (reply.phase) {
            VoiceReply.Phase.READING, VoiceReply.Phase.READ -> "Reading $who"
            VoiceReply.Phase.OFFERING -> "Reply to $who? Say yes or no"
            VoiceReply.Phase.ASKING -> "Reply to $who"
            VoiceReply.Phase.LISTENING -> "Listening… (reply to $who)"
            VoiceReply.Phase.CONFIRMING -> "Sending to $who"
            VoiceReply.Phase.SENT -> "Sent ✓"
            VoiceReply.Phase.ERROR -> reply.error
            VoiceReply.Phase.IDLE -> ""
        }
        body = reply.heard
        canSend = reply.phase == VoiceReply.Phase.CONFIRMING
        onCancel = VoiceReply::cancel
        onSend = VoiceReply::sendNow
    }

    Box(
        modifier
            .padding(start = 86.dp, end = 10.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(t.radius))
            .background(t.panelHi),
    ) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, 16.sp, t.accent, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Label(body.ifEmpty { " " }, 18.sp, maxLines = 3)
            }
            Spacer(Modifier.width(10.dp))
            if (canSend) {
                Tap(onTap = onSend, modifier = Modifier.size(100.dp, 52.dp), color = t.accent) {
                    Label("Send", 16.sp, t.bgBottom, FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
            }
            Tap(onTap = onCancel, modifier = Modifier.size(100.dp, 52.dp), color = t.panel) {
                Label("Cancel", 16.sp)
            }
        }
    }
}
