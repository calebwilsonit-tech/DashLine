package app.seb3thehacker.gearslip.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.call.CallUiState
import app.seb3thehacker.gearslip.call.CarCalls
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.notify.CarNotification
import app.seb3thehacker.gearslip.notify.CarNotifications
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Every full screen Dashline can show. */
enum class DashScreen { Home, Map, Music, Phone, Messages, Garage, Settings, Latency, Classic }

/**
 * Dashline's whole car screen. Replaces Gearslip's CarUi as the root of what's projected.
 *
 * Speed rules for everything under here (the truck adds its own touch and video delay, so the
 * phone must add as little as possible):
 *  - buttons act on touch-DOWN ([Tap]) and screens switch instantly: no transition animations;
 *  - each card reads only the state it shows, so a song change never recomposes the map pane;
 *  - fast-changing values (progress bar, clock) are read in the draw phase, not composition.
 */
@Composable
fun DashUi() {
    val context = LocalContext.current.applicationContext
    remember(context) {
        DashPrefs.init(context)
        CarKeys.init(context)
        true
    }
    DisposableEffect(context) {
        PhoneMedia.start(context)
        CarCalls.start(context)
        onDispose {
            PhoneMedia.stop()
            CarCalls.stop()
        }
    }

    val themeId by DashPrefs.theme.collectAsState()
    val theme = DashThemes.byId(themeId)
    var screen by remember { mutableStateOf(DashScreen.Home) }

    // The car's own hardware keys.
    LaunchedEffect(Unit) {
        CarKeys.screenKeys.collect { key ->
            screen = when (key) {
                CarKeys.ScreenKey.BACK, CarKeys.ScreenKey.HOME -> DashScreen.Home
                CarKeys.ScreenKey.MAP -> DashScreen.Map
                CarKeys.ScreenKey.MEDIA -> DashScreen.Music
                CarKeys.ScreenKey.PHONE -> DashScreen.Phone
            }
        }
    }
    // An incoming call takes over whatever is up, like a phone would.
    val call by CarCalls.state.collectAsState()
    LaunchedEffect(call is CallUiState.Ringing) {
        if (call is CallUiState.Ringing && screen != DashScreen.Classic) screen = DashScreen.Home
    }

    // Gearslip's original car UI, kept reachable while Dashline grows. It brings its own services
    // up and down; ours come back when it closes.
    if (screen == DashScreen.Classic) {
        Box(Modifier.fillMaxSize()) {
            CarUi()
            CompositionLocalProvider(LocalDashTheme provides theme) {
                Tap(
                    onTap = {
                        screen = DashScreen.Home
                        CarCalls.start(context)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).height(36.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Label("◀ Dashline", 14.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
            }
        }
        return
    }

    val insets by CarEnvironment.insets.collectAsState()
    val pad = with(LocalDensity.current) {
        PaddingValues(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())
    }

    CompositionLocalProvider(LocalDashTheme provides theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(theme.bgTop, theme.bgBottom)))
                .padding(pad),
        ) {
            if (screen == DashScreen.Latency) {
                LatencyTest(onBack = { screen = DashScreen.Settings })
                return@Box
            }
            Row(Modifier.fillMaxSize()) {
                Rail(screen) { screen = it }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 10.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    when (screen) {
                        DashScreen.Home -> Dashboard(open = { screen = it })
                        DashScreen.Map -> MapScreen()
                        DashScreen.Music -> MusicScreen()
                        DashScreen.Phone -> PhoneScreen()
                        DashScreen.Messages -> MessagesScreen()
                        DashScreen.Garage -> GarageScreen()
                        DashScreen.Settings -> SettingsScreen(open = { screen = it })
                        DashScreen.Latency, DashScreen.Classic -> Unit
                    }
                }
            }
        }
    }
}

// --- rail -------------------------------------------------------------------------------------

private val railItems = listOf(
    DashScreen.Home to Glyph.Home,
    DashScreen.Map to Glyph.Map,
    DashScreen.Music to Glyph.Music,
    DashScreen.Phone to Glyph.Phone,
    DashScreen.Messages to Glyph.Message,
    DashScreen.Garage to Glyph.Garage,
)

@Composable
private fun Rail(current: DashScreen, onSelect: (DashScreen) -> Unit) {
    val t = LocalDashTheme.current
    Column(
        Modifier
            .width(76.dp)
            .fillMaxHeight()
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clock(size = 15.sp)
        for ((target, glyph) in railItems) {
            RailButton(glyph, selected = current == target) { onSelect(target) }
        }
        Spacer(Modifier.weight(1f))
        RailButton(Glyph.Settings, selected = current == DashScreen.Settings) { onSelect(DashScreen.Settings) }
    }
}

@Composable
private fun RailButton(glyph: Glyph, selected: Boolean, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Tap(
        onTap = onTap,
        modifier = Modifier.size(56.dp, 48.dp),
        color = if (selected) t.panelHi else Color.Transparent,
        outline = selected,
    ) {
        GlyphIcon(glyph, if (selected) t.accent else t.textDim, 26.dp)
    }
}

@Composable
private fun Clock(size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember { android.text.format.DateFormat.getTimeFormat(context) }
    // Recomposes once a minute, on the minute.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000 + 50)
        }
    }
    Label(format.format(Date(now)), size, weight = FontWeight.SemiBold, modifier = modifier)
}

// --- dashboard: up to three panes --------------------------------------------------------------

/**
 * The split view: the map (or whatever is primary) large on the left, music and the latest
 * alert stacked on the right. Each pane is its own composable reading its own state.
 */
@Composable
private fun Dashboard(open: (DashScreen) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MapPane(Modifier.weight(1.45f).fillMaxHeight(), onTap = { open(DashScreen.Map) })
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MusicCard(Modifier.weight(1.25f).fillMaxWidth(), onOpen = { open(DashScreen.Music) })
            AlertCard(Modifier.weight(1f).fillMaxWidth(), onOpen = { open(DashScreen.Messages) })
        }
    }
}

@Composable
private fun MapPane(modifier: Modifier, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Tap(onTap = onTap, modifier = modifier, contentAlignment = Alignment.TopStart) {
        // A quiet grid stands in for the map until the real one lands.
        Canvas(Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            val line = t.textDim.copy(alpha = 0.08f)
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step }
        }
        Column(Modifier.padding(18.dp)) {
            Clock(size = 54.sp)
            Label(DateFormat.getDateInstance(DateFormat.FULL).format(Date()), 15.sp, t.textDim)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Map, t.accent, 22.dp)
                Spacer(Modifier.width(8.dp))
                Label("Map — coming in the next build", 15.sp, t.textDim)
            }
        }
    }
}

@Composable
private fun MusicCard(modifier: Modifier, onOpen: () -> Unit) {
    val t = LocalDashTheme.current
    val now by PhoneMedia.now.collectAsState()
    val allowed by PhoneMedia.allowed.collectAsState()
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Tap(
                onTap = onOpen,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = Color.Transparent,
                outline = false,
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(now, Modifier.size(92.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        when {
                            !allowed -> {
                                Label("Music needs notification access", 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
                                Label("Turn it on for Dashline in the phone's settings", 13.sp, t.textDim, maxLines = 2)
                            }
                            now.hasTrack -> {
                                Label(now.title, 17.sp, weight = FontWeight.SemiBold, maxLines = 2)
                                Label(now.artist, 14.sp, t.textDim)
                            }
                            else -> {
                                Label("Nothing playing", 17.sp, weight = FontWeight.SemiBold)
                                Label("Start YouTube Music on the phone", 13.sp, t.textDim, maxLines = 2)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Progress(now, Modifier.fillMaxWidth().height(4.dp))
            Spacer(Modifier.height(8.dp))
            Transport(now, buttonSize = 46.dp)
        }
    }
}

@Composable
private fun Art(now: PhoneMedia.Now, modifier: Modifier) {
    val t = LocalDashTheme.current
    val art = now.art
    Box(
        modifier.clip(RoundedCornerShape(t.radius / 2)).background(t.panelHi),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GlyphIcon(Glyph.Music, t.textDim, 30.dp)
        }
    }
}

/** The progress bar. Its fill is computed in the draw phase, so ticking it never recomposes. */
@Composable
private fun Progress(now: PhoneMedia.Now, modifier: Modifier) {
    val t = LocalDashTheme.current
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(now.playing) {
        while (now.playing) {
            tick.longValue = System.nanoTime()
            delay(500)
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val read = tick.longValue // redraw on each tick
                drawRect(t.panelHi)
                val d = now.durationMs
                if (d > 0) {
                    val f = (now.positionNow().toFloat() / d).coerceIn(0f, 1f)
                    drawRect(t.accent, size = Size(size.width * f, size.height))
                }
            },
    )
}

@Composable
private fun Transport(now: PhoneMedia.Now, buttonSize: androidx.compose.ui.unit.Dp) {
    val t = LocalDashTheme.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tap(onTap = PhoneMedia::previous, modifier = Modifier.size(buttonSize), shape = CircleShape, color = t.panelHi, pressedColor = t.accent2) {
            GlyphIcon(Glyph.Prev, t.text, buttonSize * 0.5f)
        }
        Tap(onTap = PhoneMedia::playPause, modifier = Modifier.size(buttonSize * 1.2f), shape = CircleShape, color = t.accent, pressedColor = t.accent2) {
            GlyphIcon(if (now.playing) Glyph.Pause else Glyph.Play, t.bgBottom, buttonSize * 0.55f)
        }
        Tap(onTap = PhoneMedia::next, modifier = Modifier.size(buttonSize), shape = CircleShape, color = t.panelHi, pressedColor = t.accent2) {
            GlyphIcon(Glyph.Next, t.text, buttonSize * 0.5f)
        }
    }
}

/** A ringing or active call if there is one, otherwise the newest notification. */
@Composable
private fun AlertCard(modifier: Modifier, onOpen: () -> Unit) {
    val t = LocalDashTheme.current
    val context = LocalContext.current
    val call by CarCalls.state.collectAsState()
    val history by CarNotifications.history.collectAsState()
    when (val c = call) {
        is CallUiState.Ringing -> Card(modifier) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Label("Incoming call", 13.sp, t.accent)
                Label(c.caller.label, 20.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tap(onTap = { CarCalls.answer(context) }, modifier = Modifier.weight(1f).height(48.dp), color = Color(0xFF1E9E4A)) {
                        Label("Answer", 17.sp, Color.White, FontWeight.SemiBold)
                    }
                    if (c.canDecline) {
                        Tap(onTap = { CarCalls.decline() }, modifier = Modifier.weight(1f).height(48.dp), color = Color(0xFFC62828)) {
                            Label("Decline", 17.sp, Color.White, FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        is CallUiState.Active -> Card(modifier) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Label("On a call", 13.sp, t.accent)
                Label(c.caller?.label ?: "Call", 20.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Tap(onTap = { CarCalls.end(context) }, modifier = Modifier.fillMaxWidth().height(48.dp), color = Color(0xFFC62828)) {
                    Label("End call", 17.sp, Color.White, FontWeight.SemiBold)
                }
            }
        }
        CallUiState.None -> {
            val latest = remember(history) { history.filter(::worthShowing).maxByOrNull { it.postedAt } }
            Tap(onTap = onOpen, modifier = modifier, contentAlignment = Alignment.TopStart) {
                if (latest == null) {
                    Column(Modifier.padding(12.dp)) {
                        Label("No new messages", 16.sp, weight = FontWeight.SemiBold)
                        Label("Texts and alerts show up here", 13.sp, t.textDim, maxLines = 2)
                    }
                } else {
                    NotificationBody(latest, Modifier.padding(12.dp), lines = 3)
                }
            }
        }
    }
}

/** System chatter (USB, keyboard layout, Android itself) never belongs on the car screen. */
private val systemPackages = setOf("android", "com.android.systemui", "com.android.settings", "com.android.shell")

private fun worthShowing(n: CarNotification) = n.packageName !in systemPackages

@Composable
private fun NotificationBody(n: CarNotification, modifier: Modifier, lines: Int) {
    val t = LocalDashTheme.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Message, t.accent, 16.dp)
            Spacer(Modifier.width(6.dp))
            Label(n.appLabel, 12.sp, t.textDim)
            Spacer(Modifier.weight(1f))
            Label(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.postedAt)), 12.sp, t.textDim)
        }
        Spacer(Modifier.height(4.dp))
        Label(n.title, 16.sp, weight = FontWeight.SemiBold)
        Label(n.text, 14.sp, t.textDim, maxLines = lines)
    }
}

// --- full screens -----------------------------------------------------------------------------

@Composable
private fun MusicScreen() {
    val t = LocalDashTheme.current
    val now by PhoneMedia.now.collectAsState()
    Card(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(now, Modifier.fillMaxHeight(0.9f).aspectRatio(1f))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Label(if (now.hasTrack) now.title else "Nothing playing", 26.sp, weight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Label(now.artist, 18.sp, t.textDim)
                Spacer(Modifier.height(22.dp))
                Progress(now, Modifier.fillMaxWidth().height(6.dp))
                Spacer(Modifier.height(22.dp))
                Transport(now, buttonSize = 64.dp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tap(onTap = { PhoneMedia.seekBy(-15_000) }, modifier = Modifier.weight(1f).height(44.dp), color = t.panelHi) {
                        Label("− 15 s", 15.sp)
                    }
                    Tap(onTap = { PhoneMedia.seekBy(30_000) }, modifier = Modifier.weight(1f).height(44.dp), color = t.panelHi) {
                        Label("+ 30 s", 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesScreen() {
    val t = LocalDashTheme.current
    val history by CarNotifications.history.collectAsState()
    val recent = remember(history) { history.filter(::worthShowing).sortedByDescending { it.postedAt }.take(4) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Messages", 22.sp, weight = FontWeight.Bold)
        if (recent.isEmpty()) {
            Label("Nothing yet. Texts from Google Messages land here.", 16.sp, t.textDim, maxLines = 2)
        }
        for (n in recent) {
            Card(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                NotificationBody(n, Modifier.padding(12.dp).fillMaxWidth(), lines = 2)
            }
        }
    }
}

@Composable
private fun MapScreen() = ComingSoon(Glyph.Map, "Map", "Google map with turn-by-turn directions is the next big piece.")

@Composable
private fun PhoneScreen() = ComingSoon(Glyph.Phone, "Phone", "Recents, favourites and a keypad are coming. Calls already show on the dashboard.")

@Composable
private fun GarageScreen() = ComingSoon(Glyph.Garage, "Garage", "One tap will open the door through the myQ app on your phone. Setup comes in a later build.")

@Composable
private fun ComingSoon(glyph: Glyph, title: String, body: String) {
    val t = LocalDashTheme.current
    Card(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(glyph, t.accent, 64.dp)
            Spacer(Modifier.height(14.dp))
            Label(title, 26.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Label(body, 16.sp, t.textDim, maxLines = 3)
        }
    }
}

@Composable
private fun SettingsScreen(open: (DashScreen) -> Unit) {
    val t = LocalDashTheme.current
    val current by DashPrefs.theme.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Theme", 20.sp, weight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (theme in DashThemes.all) {
                ThemeSwatch(theme, selected = theme.id == current, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DashPrefs.setTheme(theme.id)
                }
            }
        }
        Label("Tools", 20.sp, weight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tap(onTap = { open(DashScreen.Latency) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Label("Touch latency test", 16.sp, weight = FontWeight.SemiBold)
            }
            Tap(onTap = { open(DashScreen.Classic) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Label("Gearslip classic UI", 16.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** A theme previewed in its own colours, so picking one shows what you get. */
@Composable
private fun ThemeSwatch(theme: DashTheme, selected: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Box(
        modifier
            .clip(RoundedCornerShape(theme.radius))
            .background(Brush.linearGradient(listOf(theme.bgTop, theme.bgBottom)))
            .drawBehind {
                if (selected) drawRect(t.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTap()
                    waitForUpOrCancellation()
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(theme.radius / 2)).background(theme.panel),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(theme.accent))
            }
            Spacer(Modifier.height(8.dp))
            SwatchText(theme.name, theme.text, if (selected) "Selected" else "Tap to use", theme.textDim)
        }
    }
}

@Composable
private fun SwatchText(title: String, color: Color, sub: String, subColor: Color) {
    Label(title, 16.sp, color, FontWeight.SemiBold)
    Label(sub, 12.sp, subColor)
}

// --- latency test -----------------------------------------------------------------------------

/**
 * Measures the truck's real touch-to-screen time. The whole screen flips black/white the moment
 * a finger lands (touch-DOWN, earliest event pass). Film it in slow motion: the frames between
 * the finger touching the glass and the colour changing are the end-to-end delay, radio included.
 *
 * The spinning bar keeps frames flowing at the full rate, so a stalled stream is obvious, and the
 * phone-side time for each flip is logged as "dash latency".
 */
@Composable
private fun LatencyTest(onBack: () -> Unit) {
    val t = LocalDashTheme.current
    val flips = remember { mutableIntStateOf(0) }
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { n -> angle.floatValue = (n / 1_000_000L % 1000L) * 0.36f }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(if (flips.intValue % 2 == 1) Color.White else Color.Black) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flips.intValue++
                    GearslipLog.i("dash latency: flip #${flips.intValue} at uptime ${down.uptimeMillis}")
                    waitForUpOrCancellation(PointerEventPass.Initial)
                }
            },
    ) {
        Canvas(Modifier.align(Alignment.Center).size(160.dp)) {
            rotate(angle.floatValue) {
                drawRect(Color(0xFFFF3B30), Offset(size.width / 2 - 6.dp.toPx(), 0f), Size(12.dp.toPx(), size.height / 2))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val lit = flips.intValue % 2 == 1
            Label(
                "Flips: ${flips.intValue}  ·  Tap anywhere — film in slow-mo",
                15.sp,
                if (lit) Color.Black else Color.White,
            )
        }
        Tap(
            onTap = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(110.dp, 48.dp),
            color = Color(0xFF444444),
        ) { Label("◀ Back", 16.sp, Color.White) }
    }
}
