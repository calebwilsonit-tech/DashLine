package app.seb3thehacker.gearslip.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.Immutable
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.DirectionsVoice
import app.vela.core.config.CalibrationStore
import app.vela.core.config.JsTransforms
import app.vela.core.data.google.GoogleMapsDataSource
import app.vela.core.data.google.GoogleSession
import app.vela.core.di.CoreModule
import app.vela.core.diag.DiagLog
import app.vela.core.feedback.Haptics
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.nav.NavSession
import app.vela.core.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashline's navigation, on Vela's engine (github.com/PimpinPumpkin/Vela, GPL-3.0, prebuilt in
 * DriveApp/vela/). Vela's :core is UI-free: search, places, routes with live traffic, and the
 * turn-by-turn session. Dashline wires the same objects Vela's Hilt graph would (CoreModule),
 * feeds it the phone's GPS, and draws everything itself (DashMap.kt).
 *
 * Spoken turns: Vela's own voice is muted and every line it would say comes out through
 * Gearslip's [DirectionsVoice], which plays on the truck's directions channel (navigation audio
 * from TextToSpeech can't be captured otherwise).
 */
object DashNav {

    @Immutable
    data class Fix(val at: LatLng, val bearing: Float?, val speedMps: Float?)

    enum class Phase { IDLE, SEARCHING, RESULTS, ROUTING, PREVIEW, NAVIGATING, ERROR }

    @Immutable
    data class State(
        val phase: Phase = Phase.IDLE,
        val query: String = "",
        val results: List<Place> = emptyList(),
        val destination: Place? = null,
        val routes: List<Route> = emptyList(),
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private lateinit var source: GoogleMapsDataSource
    private lateinit var calibration: CalibrationStore
    lateinit var session: NavSession
        private set
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        val c = context.applicationContext
        app = c
        val http = CoreModule.okHttpClient()
        calibration = CalibrationStore(c, http)
        val diag = DiagLog(c)
        source = GoogleMapsDataSource(
            http,
            GoogleSession(http, calibration),
            calibration,
            JsTransforms(calibration),
            diag,
            CoreModule.routeEngine(c),
        )
        source.glRegion = java.util.Locale.getDefault().country.takeIf { it.isNotBlank() }
        val voice = VoiceGuide(c).apply {
            muted = true
            // Every line Vela would speak, spoken on the truck's directions channel instead.
            onMuted = { line -> DirectionsVoice.speak(line) }
        }
        session = NavSession(source, voice, Haptics(c), diag)
        ready = true
        // The signed request templates Vela fetches from its own repository at launch.
        scope.launch { runCatching { calibration.refresh() }.onFailure { GearslipLog.w("nav: calibration refresh failed: ${it.message}") } }
        startLocation(c)
    }

    // --- location ---------------------------------------------------------------------------

    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    private fun startLocation(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            GearslipLog.w("nav: no location permission")
            return
        }
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        // An explicit object with all four callbacks: the lambda form crashes on old Androids.
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = onFix(loc)
            @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        listener = l
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()?.let(::onFix)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, l, Looper.getMainLooper()) }
    }

    private fun onFix(loc: Location) {
        val at = LatLng(loc.latitude, loc.longitude)
        _fix.value = Fix(at, if (loc.hasBearing()) loc.bearing else null, if (loc.hasSpeed()) loc.speed else null)
        if (::session.isInitialized && session.state.value.navigating) {
            session.onLocation(
                at,
                imperial = true,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            )
        }
    }

    // --- search, route, go -------------------------------------------------------------------

    fun search(query: String) {
        if (!ready || query.isBlank()) return
        _state.value = State(Phase.SEARCHING, query = query)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { source.search(query, near = _fix.value?.at, rankFrom = _fix.value?.at).places }
            }.onSuccess { places ->
                _state.value = if (places.isEmpty()) State(Phase.ERROR, query, error = "Nothing found for \"$query\"")
                else State(Phase.RESULTS, query, results = places.take(8))
            }.onFailure {
                GearslipLog.w("nav: search failed: ${it.javaClass.simpleName}")
                _state.value = State(Phase.ERROR, query, error = "Search didn't work. Check the phone's internet.")
            }
        }
    }

    /** Routes from here to [place] and shows the preview (time with traffic, distance). */
    fun preview(place: Place) {
        val from = _fix.value?.at
        if (from == null) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = "Waiting for the phone's GPS")
            return
        }
        _state.value = _state.value.copy(phase = Phase.ROUTING, destination = place)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { source.directions(from, place.location) } }
                .onSuccess { routes ->
                    _state.value = if (routes.isEmpty()) _state.value.copy(phase = Phase.ERROR, error = "No route found")
                    else _state.value.copy(phase = Phase.PREVIEW, routes = routes)
                }
                .onFailure {
                    GearslipLog.w("nav: directions failed: ${it.javaClass.simpleName}")
                    _state.value = _state.value.copy(phase = Phase.ERROR, error = "Couldn't get directions")
                }
        }
    }

    fun go(routeIndex: Int = 0) {
        val s = _state.value
        val route = s.routes.getOrNull(routeIndex) ?: return
        val dest = s.destination ?: return
        session.start(
            route,
            dest.location,
            destinationLabel = dest.name,
            destinationAddress = dest.address.orEmpty(),
            from = _fix.value?.at,
            fromCourseDeg = _fix.value?.bearing?.toDouble(),
        )
        _state.value = s.copy(phase = Phase.NAVIGATING)
        GearslipLog.i("nav: navigation started")
    }

    /** Searches and starts driving to the top result: "navigate to Costco" from the assistant. */
    fun navigateTo(query: String) {
        if (!ready) return
        search(query)
        scope.launch {
            val done = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                state.first { it.phase != Phase.SEARCHING }
            } ?: return@launch
            val top = done.results.firstOrNull() ?: return@launch
            preview(top)
            val routed = kotlinx.coroutines.withTimeoutOrNull(20_000) { state.first { it.phase != Phase.ROUTING } } ?: return@launch
            if (routed.phase == Phase.PREVIEW) go()
        }
    }

    fun stop() {
        if (::session.isInitialized) session.stop()
        _state.value = State()
        GearslipLog.i("nav: navigation ended")
    }

    fun clear() {
        if (_state.value.phase != Phase.NAVIGATING) _state.value = State()
    }
}
