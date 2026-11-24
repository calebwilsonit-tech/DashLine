package app.seb3thehacker.gearslip.dash

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One Dashline look. Everything the car UI paints comes from here, so a new theme is just a new
 * entry in [DashThemes.all]. Colours are flat (no Material dynamic colour) so the picture is
 * cheap to draw and identical every time.
 */
@Immutable
data class DashTheme(
    val id: String,
    val name: String,
    /** Screen background, top-left to bottom-right gradient. */
    val bgTop: Color,
    val bgBottom: Color,
    /** Cards and the side rail. */
    val panel: Color,
    /** A pressed or selected card. */
    val panelHi: Color,
    val accent: Color,
    val accent2: Color,
    val text: Color,
    val textDim: Color,
    val radius: Dp = 18.dp,
    /** Hairline drawn around cards; Color.Transparent for none. */
    val outline: Color = Color.Transparent,
)

object DashThemes {
    val Carbon = DashTheme(
        id = "carbon", name = "Carbon",
        bgTop = Color(0xFF15171A), bgBottom = Color(0xFF0A0B0D),
        panel = Color(0xFF1F2227), panelHi = Color(0xFF2C3038),
        accent = Color(0xFFFF7A1A), accent2 = Color(0xFFFFB067),
        text = Color(0xFFF2F2F2), textDim = Color(0xFF9AA0A8),
    )

    val Denali = DashTheme(
        id = "denali", name = "Denali Chrome",
        bgTop = Color(0xFF1C1A18), bgBottom = Color(0xFF0B0A09),
        panel = Color(0xFF26231F), panelHi = Color(0xFF38332C),
        accent = Color(0xFFC9CED6), accent2 = Color(0xFFB08455),
        text = Color(0xFFF4F1EC), textDim = Color(0xFFA59C90),
        radius = 10.dp, outline = Color(0x33C9CED6),
    )

    val Neon = DashTheme(
        id = "neon", name = "Neon Night",
        bgTop = Color(0xFF0B1030), bgBottom = Color(0xFF05060F),
        panel = Color(0xFF141A3D), panelHi = Color(0xFF222B5C),
        accent = Color(0xFF22E4FF), accent2 = Color(0xFFFF3DCB),
        text = Color(0xFFEFF3FF), textDim = Color(0xFF8C96C8),
        radius = 22.dp, outline = Color(0x4022E4FF),
    )

    val Red = DashTheme(
        id = "red", name = "Pro Red",
        bgTop = Color(0xFF161212), bgBottom = Color(0xFF0A0707),
        panel = Color(0xFF221919), panelHi = Color(0xFF332424),
        accent = Color(0xFFE53935), accent2 = Color(0xFFFF8A80),
        text = Color(0xFFF5F0F0), textDim = Color(0xFFA89A9A),
        radius = 12.dp,
    )

    val Forest = DashTheme(
        id = "forest", name = "Forest",
        bgTop = Color(0xFF101A14), bgBottom = Color(0xFF070D09),
        panel = Color(0xFF17241C), panelHi = Color(0xFF223529),
        accent = Color(0xFF7CD957), accent2 = Color(0xFFC6F2A9),
        text = Color(0xFFEFF6EE), textDim = Color(0xFF94A897),
        radius = 16.dp,
    )

    val Ocean = DashTheme(
        id = "ocean", name = "Ocean",
        bgTop = Color(0xFF0B1A24), bgBottom = Color(0xFF050D13),
        panel = Color(0xFF112634), panelHi = Color(0xFF1A3648),
        accent = Color(0xFF26C6DA), accent2 = Color(0xFF80DEEA),
        text = Color(0xFFEAF6FA), textDim = Color(0xFF8FA9B5),
        radius = 20.dp, outline = Color(0x3326C6DA),
    )

    val Sunset = DashTheme(
        id = "sunset", name = "Sunset",
        bgTop = Color(0xFF1E1026), bgBottom = Color(0xFF0C0610),
        panel = Color(0xFF2A1735), panelHi = Color(0xFF3B2149),
        accent = Color(0xFFFF8F3F), accent2 = Color(0xFFFF5E8A),
        text = Color(0xFFFAF0F5), textDim = Color(0xFFB399AD),
        radius = 22.dp,
    )

    /** For bright sun: light panels, dark text. */
    val Daylight = DashTheme(
        id = "daylight", name = "Daylight",
        bgTop = Color(0xFFE9ECF0), bgBottom = Color(0xFFD5DAE0),
        panel = Color(0xFFFFFFFF), panelHi = Color(0xFFE3E7EC),
        accent = Color(0xFF1565C0), accent2 = Color(0xFF42A5F5),
        text = Color(0xFF111418), textDim = Color(0xFF5B6470),
        radius = 16.dp, outline = Color(0x22000000),
    )

    val all = listOf(Carbon, Denali, Neon, Red, Forest, Ocean, Sunset, Daylight)

    /** Accent colors the driver can put on any theme (phone settings). */
    val ACCENTS = listOf(
        0xFFFF7A1A, 0xFFE53935, 0xFFFFC107, 0xFF7CD957, 0xFF26C6DA, 0xFF3D8BFF, 0xFFB388FF, 0xFFFF5E8A, 0xFFC9CED6,
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Carbon
}

val LocalDashTheme = staticCompositionLocalOf { DashThemes.Carbon }

/** Dashline's own settings, kept apart from Gearslip's. */
object DashPrefs {
    private const val FILE = "dashline"
    private const val KEY_THEME = "theme"
    private const val KEY_VIDEO = "video_mode"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_ACCENT = "accent"

    /** The driver's own accent color over the theme's; 0 = the theme's own. */
    private val _accent = MutableStateFlow(0L)
    val accent: StateFlow<Long> = _accent.asStateFlow()

    fun setAccent(argb: Long) {
        _accent.value = argb
        prefs()?.edit()?.putLong(KEY_ACCENT, argb)?.apply()
    }

    enum class Assistant(val label: String) {
        PHONE("Phone's Google Assistant / Gemini"),
        DASHLINE("Dashline assistant (Gemini API key)"),
    }

    private val _assistant = MutableStateFlow(Assistant.PHONE)
    /** Which assistant the mic button and the steering-wheel voice key start. */
    val assistant: StateFlow<Assistant> = _assistant.asStateFlow()

    fun setAssistant(a: Assistant) {
        _assistant.value = a
        prefs()?.edit()?.putString(KEY_ASSISTANT, a.name)?.apply()
    }
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private val _autoPlay = MutableStateFlow(true)
    /** Start the phone's music when the truck connects. */
    val autoPlay: StateFlow<Boolean> = _autoPlay.asStateFlow()

    private val _theme = MutableStateFlow(DashThemes.Carbon.id)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        _theme.value = prefs()?.getString(KEY_THEME, null) ?: DashThemes.Carbon.id
        VideoTuning.load(prefs()?.getString(KEY_VIDEO, null))
        _autoPlay.value = prefs()?.getBoolean(KEY_AUTOPLAY, true) ?: true
        _accent.value = prefs()?.getLong(KEY_ACCENT, 0L) ?: 0L
        _assistant.value = Assistant.entries.firstOrNull { it.name == prefs()?.getString(KEY_ASSISTANT, null) } ?: Assistant.PHONE
    }

    fun setTheme(id: String) {
        _theme.value = id
        prefs()?.edit()?.putString(KEY_THEME, id)?.apply()
    }

    fun setVideoMode(mode: VideoTuning.Mode) {
        VideoTuning.set(mode)
        prefs()?.edit()?.putString(KEY_VIDEO, mode.name)?.apply()
    }

    fun setAutoPlay(on: Boolean) {
        _autoPlay.value = on
        prefs()?.edit()?.putBoolean(KEY_AUTOPLAY, on)?.apply()
    }

    /** The driver's own Google AI Studio key. Kept in this app's private storage only. */
    fun geminiKey(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_GEMINI, "").orEmpty()

    fun geminiModel(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun saveGemini(context: Context, key: String, model: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GEMINI, key.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    private fun prefs() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
