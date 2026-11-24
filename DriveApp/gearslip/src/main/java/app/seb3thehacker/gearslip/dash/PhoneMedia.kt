package app.seb3thehacker.gearslip.dash

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remote control for whatever is playing on the phone (YouTube Music, mainly).
 *
 * YouTube Music only shows its library to Google-signed hosts, but any app with notification
 * access can see and drive its media *session*: title, artist, art, play/pause, skip, seek. That's
 * all Dashline needs, and it works for every player, not just YouTube Music.
 */
object PhoneMedia {

    @Immutable
    data class Now(
        val packageName: String = "",
        val title: String = "",
        val artist: String = "",
        val art: ImageBitmap? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val speed: Float = 1f,
        /** elapsedRealtime when [positionMs] was true. */
        val updatedAt: Long = 0,
    ) {
        val hasTrack get() = title.isNotEmpty()

        fun positionNow(): Long {
            if (!playing || updatedAt == 0L) return positionMs
            val moved = positionMs + ((SystemClock.elapsedRealtime() - updatedAt) * speed).toLong()
            return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
        }
    }

    private val _now = MutableStateFlow(Now())
    val now: StateFlow<Now> = _now.asStateFlow()

    /** False until notification access is granted; the UI shows how to fix it. */
    private val _allowed = MutableStateFlow(true)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var listener: ComponentName? = null

    // The last art bitmap we scaled, so a play/pause doesn't redo the work.
    private var lastArtSource: Bitmap? = null
    private var lastArt: ImageBitmap? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A paused player losing to a playing one: re-pick on every state change.
            refresh()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun start(context: Context) {
        if (manager != null) return
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(context, GearslipNotificationListener::class.java)
        this.listener = listener
        appContext = context.applicationContext
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            manager = m
            _allowed.value = true
            pick(m.getActiveSessions(listener))
        } catch (e: SecurityException) {
            GearslipLog.w("dash media: no notification access, can't see the player")
            _allowed.value = false
        }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun refresh() {
        val m = manager ?: return publish()
        val sessions = runCatching { m.getActiveSessions(listener) }.getOrNull()
        if (sessions != null) pick(sessions) else publish()
    }

    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.packageName == controller?.packageName }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _now.value = Now()
            return
        }
        val md = c.metadata
        val st = c.playbackState
        _now.value = Now(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            art = art(md),
            playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = st?.position ?: 0,
            speed = st?.playbackSpeed ?: 1f,
            updatedAt = st?.lastPositionUpdateTime ?: 0,
        )
    }

    /** Art scaled once to a small square: a 1000px bitmap redrawn every frame is wasted work. */
    private fun art(md: MediaMetadata?): ImageBitmap? {
        val src = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (src === lastArtSource) return lastArt
        val side = ART_PX
        val scaled = runCatching {
            if (src.width <= side && src.height <= side) src else src.scale(side, side)
        }.getOrNull() ?: return null
        lastArtSource = src
        lastArt = scaled.asImageBitmap().also { it.prepareToDraw() }
        return lastArt
    }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (_now.value.playing) t.pause() else t.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }

    fun play() { controller?.transportControls?.play() ?: mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }

    fun pause() { controller?.transportControls?.pause() }

    private const val YT_MUSIC = "com.google.android.apps.youtube.music"
    private var appContext: Context? = null

    /**
     * "Play Morgan Wallen": through YouTube Music's own session when it's running (no screen
     * change on the phone), otherwise its play-from-search intent, launched from the garage
     * accessibility service (allowed to start activities from the background).
     */
    fun playFromSearch(context: Context, query: String) {
        val yt = runCatching { manager?.getActiveSessions(listener)?.firstOrNull { it.packageName == YT_MUSIC } }.getOrNull()
        if (yt != null) {
            if (query.isBlank()) yt.transportControls.play()
            else yt.transportControls.playFromSearch(query, android.os.Bundle())
            return
        }
        val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(YT_MUSIC)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcher: Context = Garage.service ?: context
        runCatching { launcher.startActivity(intent) }
            .onFailure { GearslipLog.w("dash media: couldn't start YouTube Music: ${it.message}") }
    }

    /**
     * Music on when the truck connects: resume whatever was last playing. A paused session plays;
     * with none, a PLAY media key wakes the last player (Android's media resumption).
     */
    fun autoPlay(context: Context) {
        if (_now.value.playing) return
        GearslipLog.i("dash media: auto-play on connect")
        val c = controller
        if (c != null) c.transportControls.play()
        else {
            appContext = context.applicationContext
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    /** Music off when the phone is unplugged from the car. */
    fun pauseOnDisconnect(context: Context) {
        GearslipLog.i("dash media: pause on disconnect")
        val c = controller
        if (c != null) {
            if (_now.value.playing) c.transportControls.pause()
        } else {
            appContext = context.applicationContext
            val audio = appContext?.getSystemService(android.media.AudioManager::class.java)
            if (audio?.isMusicActive == true) mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun mediaKey(code: Int) {
        val audio = appContext?.getSystemService(android.media.AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun seekBy(deltaMs: Long) {
        val pos = _now.value.positionNow() + deltaMs
        controller?.transportControls?.seekTo(pos.coerceAtLeast(0))
    }

    private const val ART_PX = 256
}

/**
 * Music off while Dashline talks or listens: a message read aloud, a voice reply or text, either
 * assistant. Asking for audio focus alone didn't pause YouTube Music in the truck, so the player
 * is paused directly, and resumed afterwards only if this paused it.
 */
object VoiceDuck {
    private var pausedByUs = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val resume = Runnable {
        if (pausedByUs) {
            pausedByUs = false
            PhoneMedia.play()
        }
    }

    fun update(voiceActive: Boolean) {
        if (voiceActive) {
            main.removeCallbacks(resume)
            if (PhoneMedia.now.value.playing) {
                PhoneMedia.pause()
                pausedByUs = true
                app.seb3thehacker.gearslip.GearslipLog.i("voice duck: music paused")
            }
        } else if (pausedByUs) {
            // A short wait, so the gap between "read" and "reply?" doesn't blip the music back on.
            main.postDelayed(resume, 800)
        }
    }
}
