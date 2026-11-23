package app.seb3thehacker.gearslip.dash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the H.264 stream uses keyframes.
 *
 * Found 2026-11-23 from a video of the owner's 2017 GMC IntelliLink (firmware GMG3G_LINUX_16.3B020):
 * the radio only put a new picture on screen every 5.0 s, exactly Gearslip's keyframe interval.
 * The changes in between (P-frames) were acked but never shown, so a tap could take up to 5 s
 * to appear and quick taps were skipped entirely.
 *
 *  - [Mode.ALL_KEYFRAMES]: every frame is an IDR. That's ~11-30 KB per frame, a few Mbps over USB,
 *    and it doesn't depend on the radio handling P-frames at all.
 *  - [Mode.KEYFRAME_ON_TOUCH]: a normal stream, plus a keyframe right after each touch, so
 *    responses show at once while idle screens stay cheap.
 *  - [Mode.STOCK]: Gearslip's original behaviour, for comparison.
 *
 * Read when the encoder starts, so a change applies from the next connection.
 */
object VideoTuning {
    enum class Mode(val label: String) {
        ALL_KEYFRAMES("Every frame full"),
        KEYFRAME_ON_TOUCH("Full frame on touch"),
        STOCK("Original (5 s keyframes)"),
    }

    private val _mode = MutableStateFlow(Mode.ALL_KEYFRAMES)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Keyframe interval for MediaFormat.KEY_I_FRAME_INTERVAL; 0 asks for all keyframes. */
    val iFrameIntervalSeconds: Int get() = if (_mode.value == Mode.ALL_KEYFRAMES) 0 else 5

    internal fun load(name: String?) {
        _mode.value = Mode.entries.firstOrNull { it.name == name } ?: Mode.ALL_KEYFRAMES
    }

    internal fun set(mode: Mode) {
        _mode.value = mode
    }
}
