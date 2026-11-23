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

    val all = listOf(Carbon, Denali, Neon)

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Carbon
}

val LocalDashTheme = staticCompositionLocalOf { DashThemes.Carbon }

/** Dashline's own settings, kept apart from Gearslip's. */
object DashPrefs {
    private const val FILE = "dashline"
    private const val KEY_THEME = "theme"

    private val _theme = MutableStateFlow(DashThemes.Carbon.id)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        _theme.value = prefs()?.getString(KEY_THEME, null) ?: DashThemes.Carbon.id
    }

    fun setTheme(id: String) {
        _theme.value = id
        prefs()?.edit()?.putString(KEY_THEME, id)?.apply()
    }

    private fun prefs() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
