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
import app.vela.core.data.PhotonGeocoder
import app.vela.core.data.google.GoogleMapsDataSource
import app.vela.core.data.google.GoogleSession
import app.vela.core.di.CoreModule
import app.vela.core.diag.DiagLog
import app.vela.core.feedback.Haptics
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.nav.NavSession
import app.vela.core.util.AddressQuery
import app.vela.core.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Dashline's navigation, on Vela's engine (github.com/PimpinPumpkin/Vela, GPL-3.0, prebuilt in
 * DriveApp/vela/). Vela's :core is UI-free: search, places, routes with live traffic, and the
 * turn-by-turn session. Dashline wires the same objects Vela's Hilt graph would (CoreModule),
 * runs the search the way Vela's app does (addresses through Google autocomplete with a wide
 * span plus OpenStreetMap's Photon), feeds it the phone's GPS, and draws everything itself.
 *
 * Spoken turns: Vela's own voice is muted and every line it would say comes out through
 * Gearslip's [DirectionsVoice], on the truck's directions channel.
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
        val chosen: Int = 0,
        val error: String = "",
    ) {
        val route: Route? get() = routes.getOrNull(chosen)
    }

    /** Suggestions while typing: saved/recent places first, then Google and OpenStreetMap. */
    @Immutable
    data class Suggestions(val term: String = "", val places: List<Place> = emptyList(), val queries: List<String> = emptyList())

    /** How the camera behaves: following the car, showing the whole route, or free after a pan. */
    enum class Camera { FOLLOW, OVERVIEW, FREE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _suggestions = MutableStateFlow(Suggestions())
    val suggestions: StateFlow<Suggestions> = _suggestions.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    val camera = MutableStateFlow(Camera.FOLLOW)
    /** Map always north-up (otherwise it turns with the truck while driving). */
    val northUp = MutableStateFlow(false)
    /** Spoken turns on or off. */
    val voiceOn = MutableStateFlow(true)

    private lateinit var http: OkHttpClient
    private lateinit var source: GoogleMapsDataSource
    private lateinit var calibration: CalibrationStore
    lateinit var session: NavSession
        private set
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        val c = context.applicationContext
        DashPlaces.init(c)
        http = CoreModule.okHttpClient()
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
            onMuted = { line -> if (voiceOn.value) DirectionsVoice.speak(line) }
        }
        session = NavSession(source, voice, Haptics(c), diag)
        ready = true
        scope.launch { runCatching { calibration.refresh() }.onFailure { GearslipLog.w("nav: calibration refresh failed: ${it.message}") } }
        startLocation(c)
        // The session's arrival ends Dashline's drive state too.
        scope.launch {
            session.state.collect { s ->
                if (s.arrived && _state.value.phase == Phase.NAVIGATING) GearslipLog.i("nav: arrived")
            }
        }
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

    // --- search ------------------------------------------------------------------------------

    private var suggestJob: Job? = null

    /** Live suggestions while typing, the way Vela's search box does it. */
    fun suggest(term: String) {
        suggestJob?.cancel()
        val t = term.trim()
        val local = DashPlaces.matching(t).map { it.place() }
        _suggestions.value = Suggestions(t, local)
        if (!ready || t.length < 2) return
        suggestJob = scope.launch {
            delay(320) // fire once typing pauses
            val found = withContext(Dispatchers.IO) { lookup(t) }
            if (_suggestions.value.term == t) {
                _suggestions.value = Suggestions(t, dedupe(local + found.first).take(8), found.second.take(3))
            }
        }
    }

    /**
     * Google autocomplete (with a wide span for an address, so "833 Beech Bend Dr Nashville"
     * isn't pulled to a lookalike street nearby) raced against Photon (OpenStreetMap) for
     * house-number queries. Returns places and query completions.
     */
    private suspend fun lookup(t: String): Pair<List<Place>, List<String>> = kotlinx.coroutines.coroutineScope {
        val near = _fix.value?.at
        val address = AddressQuery.parse(t) != null || PhotonGeocoder.looksLikeAddress(t)
        val photon = if (address) async {
            runCatching { PhotonGeocoder.suggest(http, t, near, "en", limit = 4, hardBox = false) }.getOrDefault(emptyList())
        } else null
        val span = if (address) ADDRESS_SPAN_M else SEARCH_SPAN_M
        val auto = runCatching { source.suggest(t, near, span) }.getOrNull()
        val google = auto?.places.orEmpty()
        val osm = photon?.await().orEmpty()
        // For an address, a match that names the right city should win over a nearer lookalike,
        // and the exact house number over its neighbors.
        val merged = if (address) dedupe(osm + google) else dedupe(google + osm)
        val houseNo = Regex("""^\s*(\d+)""").find(t)?.groupValues?.get(1)
        val ranked = if (houseNo == null) merged else merged.sortedByDescending { p ->
            Regex("""\b""" + houseNo + """\b""").containsMatchIn(p.name + " " + p.address.orEmpty())
        }
        Pair(ranked, auto?.queries.orEmpty())
    }

    /** A full search (Enter on the keyboard, a spoken place, a category button). */
    fun search(query: String) {
        if (!ready || query.isBlank()) return
        val q = query.trim()
        DashPlaces.find(q)?.let { spot ->
            _state.value = State(Phase.RESULTS, q, results = listOf(spot.place()))
            return
        }
        _state.value = State(Phase.SEARCHING, query = q)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val address = AddressQuery.parse(q) != null || PhotonGeocoder.looksLikeAddress(q)
                    if (address) {
                        lookup(q).first.ifEmpty { source.search(q, near = _fix.value?.at, spanMeters = ADDRESS_SPAN_M).places }
                    } else {
                        source.search(q, near = _fix.value?.at, spanMeters = SEARCH_SPAN_M, rankFrom = _fix.value?.at).places
                    }
                }
            }.onSuccess { places ->
                _state.value = if (places.isEmpty()) State(Phase.ERROR, q, error = "Nothing found for \"$q\"")
                else State(Phase.RESULTS, q, results = places.take(10))
            }.onFailure {
                GearslipLog.w("nav: search failed: ${it.javaClass.simpleName}")
                _state.value = State(Phase.ERROR, q, error = "Search didn't work. Check the phone's internet.")
            }
        }
    }

    private fun dedupe(list: List<Place>): List<Place> {
        val out = mutableListOf<Place>()
        for (p in list) if (out.none { metersBetween(it.location, p.location) < 50 }) out += p
        return out
    }

    // --- route, go ---------------------------------------------------------------------------

    /** Routes from here to [place]: up to four, with traffic. */
    fun preview(place: Place) {
        val from = _fix.value?.at
        if (from == null) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = "Waiting for the phone's GPS")
            return
        }
        DashPlaces.addRecent(place)
        _state.value = _state.value.copy(phase = Phase.ROUTING, destination = place, routes = emptyList(), chosen = 0)
        camera.value = Camera.OVERVIEW
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { source.directions(from, place.location) } }
                .onSuccess { routes ->
                    _state.value = if (routes.isEmpty()) _state.value.copy(phase = Phase.ERROR, error = "No route found")
                    else _state.value.copy(phase = Phase.PREVIEW, routes = routes.take(4), chosen = 0)
                }
                .onFailure {
                    GearslipLog.w("nav: directions failed: ${it.javaClass.simpleName}")
                    _state.value = _state.value.copy(phase = Phase.ERROR, error = "Couldn't get directions")
                }
        }
    }

    fun choose(index: Int) {
        val s = _state.value
        if (index in s.routes.indices) _state.value = s.copy(chosen = index)
    }

    fun go() {
        val s = _state.value
        val route = s.route ?: return
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
        camera.value = Camera.FOLLOW
        GearslipLog.i("nav: navigation started")
    }

    /** "Navigate to Costco", "take me home", "directions to mom's house". */
    fun navigateTo(said: String) {
        if (!ready) return
        DashPlaces.find(said)?.let { spot ->
            preview(spot.place())
            scope.launch { awaitPreviewThenGo() }
            return
        }
        search(said)
        scope.launch {
            val done = withTimeoutOrNull(15_000) { state.first { it.phase != Phase.SEARCHING } } ?: return@launch
            val top = done.results.firstOrNull() ?: return@launch
            preview(top)
            awaitPreviewThenGo()
        }
    }

    private suspend fun awaitPreviewThenGo() {
        val routed = withTimeoutOrNull(25_000) { state.first { it.phase != Phase.ROUTING } } ?: return
        if (routed.phase == Phase.PREVIEW) go()
    }

    fun acceptFaster() = session.acceptFasterRoute()
    fun dismissFaster() = session.dismissFasterRoute()

    fun stop() {
        if (::session.isInitialized) session.stop()
        _state.value = State()
        camera.value = Camera.FOLLOW
        GearslipLog.i("nav: navigation ended")
    }

    fun clear() {
        if (_state.value.phase != Phase.NAVIGATING) {
            _state.value = State()
            camera.value = Camera.FOLLOW
        }
    }

    private const val ADDRESS_SPAN_M = 40_000.0 // Vela's ADDRESS_SEARCH_SPAN_M
    private const val SEARCH_SPAN_M = 30_000.0
}
