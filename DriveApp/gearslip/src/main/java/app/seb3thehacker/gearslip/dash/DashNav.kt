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
        /** Stops on the way, in order (waypoints before Go; added to the drive after). */
        val stops: List<Place> = emptyList(),
        /** The destination's full listing: hours, open now, phone, gas price. */
        val details: Place? = null,
        /** These results came from a search along the route, priced in added minutes. */
        val alongRoute: Boolean = false,
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
    /** Route options (Vela's RoutingPrefs), remembered. */
    val avoidTolls = MutableStateFlow(false)
    val avoidHighways = MutableStateFlow(false)
    val avoidFerries = MutableStateFlow(false)
    /** Units: true = miles/mph/ft, false = km/km/h/m. */
    val imperial = MutableStateFlow(true)
    /** Spoken directions speed (TTS speech rate: 0.85..1.3). */
    val voiceSpeed = MutableStateFlow(1.0f)
    /** 3D extruded buildings and tilted 3D camera on the map. */
    val buildings3d = MutableStateFlow(true)
    val tilt3d = MutableStateFlow(true)
    /** How much the voice says (Vela's SpokenDetail): everything, brief, or highway exits only. */
    val voiceDetail = MutableStateFlow(app.vela.core.nav.SpokenDetail.Mode.FULL)
    /** Google's live traffic drawn on the map while browsing. */
    val trafficLayer = MutableStateFlow(true)

    @Immutable
    data class RouteCam(val loc: LatLng, val redLight: Boolean = false)

    /** Speed and red-light cameras along the route, and the one being warned about. */
    val cameras = MutableStateFlow<List<RouteCam>>(emptyList())
    val cameraAhead = MutableStateFlow<RouteCam?>(null)
    /** No-toll alternative route fetched for comparison when the primary route has tolls. */
    val noTollRoute = MutableStateFlow<Route?>(null)

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    /** Whether the phone has a connection; offline, the map swaps to downloaded tiles. */
    val online = MutableStateFlow(true)

    /** Ask the car screen to show the Map (a shared link, "navigate to"). */
    val showMap = MutableStateFlow(0)

    /** Google off: search and routes from open data only (Vela's NoGoogle). */
    val noGoogle = MutableStateFlow(false)
    fun setNoGoogle(on: Boolean) { noGoogle.value = on; app.vela.core.data.NoGoogle.enabled = on; prefs?.edit()?.putBoolean("noGoogle", on)?.apply() }

    /** Posted limit where the truck is (from an installed offline region), mph; null when unknown. */
    val speedLimitMph = MutableStateFlow<Int?>(null)
    /** More than ~7 mph over the limit. */
    val speeding = MutableStateFlow(false)
    /** Map look: "auto" follows the theme; otherwise an OpenFreeMap style. */
    val mapStyle = MutableStateFlow("auto")
    /** A drive that was running when the app last stopped, offered back on the next start. */
    val pendingResume = MutableStateFlow<Spot?>(null)
    private var pendingStops: List<Spot> = emptyList()

    fun setMapStyle(style: String) { mapStyle.value = style; prefs?.edit()?.putString("mapStyle", style)?.apply() }
    fun setPaused(on: Boolean) { if (::session.isInitialized) session.setPaused(on) }
    private lateinit var routeEngine: app.vela.core.data.RouteEngine

    fun setAvoidTolls(on: Boolean) { avoidTolls.value = on; app.vela.core.data.RoutingPrefs.avoidTolls = on; prefs?.edit()?.putBoolean("avoidTolls", on)?.apply(); reroutePreview() }
    fun setAvoidHighways(on: Boolean) { avoidHighways.value = on; app.vela.core.data.RoutingPrefs.avoidHighways = on; prefs?.edit()?.putBoolean("avoidHighways", on)?.apply(); reroutePreview() }
    fun setAvoidFerries(on: Boolean) { avoidFerries.value = on; app.vela.core.data.RoutingPrefs.avoidFerries = on; prefs?.edit()?.putBoolean("avoidFerries", on)?.apply(); reroutePreview() }
    fun setImperial(on: Boolean) { imperial.value = on; prefs?.edit()?.putBoolean("imperial", on)?.apply() }
    fun setVoiceSpeed(rate: Float) { val r = rate.coerceIn(0.75f, 1.4f); voiceSpeed.value = r; DirectionsVoice.setSpeechRate(r); prefs?.edit()?.putFloat("voiceSpeed", r)?.apply() }
    fun setBuildings3d(on: Boolean) { buildings3d.value = on; prefs?.edit()?.putBoolean("buildings3d", on)?.apply() }
    fun setTilt3d(on: Boolean) { tilt3d.value = on; prefs?.edit()?.putBoolean("tilt3d", on)?.apply() }
    fun setVoiceDetail(m: app.vela.core.nav.SpokenDetail.Mode) { voiceDetail.value = m; app.vela.core.nav.SpokenDetail.mode = m; prefs?.edit()?.putString("voiceDetail", m.name)?.apply() }
    fun setTrafficLayer(on: Boolean) { trafficLayer.value = on; prefs?.edit()?.putBoolean("traffic", on)?.apply() }

    private lateinit var http: OkHttpClient
    private lateinit var source: GoogleMapsDataSource
    private lateinit var calibration: CalibrationStore
    lateinit var session: NavSession
        private set
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        val c = context.applicationContext
        appContext = c
        DashPlaces.init(c)
        DashOffline.init(c) // before the route engine: it reads OfflineRoot.dir
        prefs = c.getSharedPreferences("dashline_nav", Context.MODE_PRIVATE).also { p ->
            mapStyle.value = p.getString("mapStyle", "auto") ?: "auto"
            noGoogle.value = p.getBoolean("noGoogle", false)
            app.vela.core.data.NoGoogle.enabled = noGoogle.value
            p.getString("activeDrive", null)?.let { raw ->
                runCatching {
                    val o = org.json.JSONObject(raw)
                    pendingResume.value = Spot.from(o.getJSONObject("dest"))
                    val a = o.optJSONArray("stops") ?: org.json.JSONArray()
                    pendingStops = (0 until a.length()).map { Spot.from(a.getJSONObject(it)) }
                }
            }
            avoidTolls.value = p.getBoolean("avoidTolls", false)
            avoidHighways.value = p.getBoolean("avoidHighways", false)
            avoidFerries.value = p.getBoolean("avoidFerries", false)
            imperial.value = p.getBoolean("imperial", true)
            voiceSpeed.value = p.getFloat("voiceSpeed", 1.0f)
            buildings3d.value = p.getBoolean("buildings3d", true)
            tilt3d.value = p.getBoolean("tilt3d", true)
            trafficLayer.value = p.getBoolean("traffic", true)
            voiceDetail.value = runCatching { app.vela.core.nav.SpokenDetail.Mode.valueOf(p.getString("voiceDetail", "FULL")!!) }
                .getOrDefault(app.vela.core.nav.SpokenDetail.Mode.FULL)
        }
        app.vela.core.data.RoutingPrefs.avoidTolls = avoidTolls.value
        app.vela.core.data.RoutingPrefs.avoidHighways = avoidHighways.value
        app.vela.core.data.RoutingPrefs.avoidFerries = avoidFerries.value
        app.vela.core.nav.SpokenDetail.mode = voiceDetail.value
        DirectionsVoice.setSpeechRate(voiceSpeed.value)
        http = CoreModule.okHttpClient()
        app.vela.data.StreamedSpeedLimit.http = http
        calibration = CalibrationStore(c, http)
        val diag = DiagLog(c)
        source = GoogleMapsDataSource(
            http,
            GoogleSession(http, calibration),
            calibration,
            JsTransforms(calibration),
            diag,
            CoreModule.routeEngine(c).also { routeEngine = it },
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
        runCatching {
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            online.value = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { online.value = true }
                override fun onLost(network: android.net.Network) { online.value = false }
            })
        }
        // The session's arrival ends Dashline's drive state too.
        scope.launch {
            session.state.collect { s ->
                if (s.arrived && _state.value.phase == Phase.NAVIGATING) {
                    GearslipLog.i("nav: arrived")
                    prefs?.edit()?.remove("activeDrive")?.apply()
                }
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

    private var limitAt = 0L
    private var limitLogged = false

    private fun updateLimit(at: LatLng, speedMps: Float?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - limitAt < 2_000 || !::routeEngine.isInitialized) return
        limitAt = now
        scope.launch {
            val kmh = withContext(Dispatchers.IO) {
                // A downloaded region first; otherwise Vela's speed-limit overlay, streamed a tile at a time.
                runCatching { routeEngine.currentRoadLimit(at.lat, at.lng) }.getOrNull()
                    ?: overlayUris(at).takeIf { it.isNotEmpty() }?.let { uris ->
                        runCatching { app.vela.data.StreamedSpeedLimit.limitAt(uris, at.lat, at.lng) }.getOrNull()
                    }
            }
            val imp = imperial.value
            val limit = kmh?.let {
                if (imp) (it / 1.609344 / 5).let { v -> (Math.round(v) * 5).toInt() }
                else (it / 5.0).let { v -> (Math.round(v) * 5).toInt() }
            }
            if (!limitLogged) { limitLogged = true; GearslipLog.i("nav: first speed limit lookup -> ${limit ?: "none"} (overlays ${overlays?.size ?: 0})") }
            if (limit != speedLimitMph.value) {
                GearslipLog.i("nav: speed limit ${limit ?: "unknown"}")
                speedLimitMph.value = limit
            }
            val speedNow = speedMps?.let { if (imp) it * 2.23694f else it * 3.6f }
            val margin = if (imp) 7 else 11
            val over = limit != null && speedNow != null && speedNow > limit + margin
            if (over && !speeding.value && voiceOn.value) DirectionsVoice.speak("Speed limit $limit")
            speeding.value = over
        }
    }

    /** Vela's speed-limit overlays (one PMTiles archive per state/country), listed once per run. */
    private var overlays: List<Pair<DoubleArray, String>>? = null

    private fun overlayUris(at: LatLng): List<String> {
        val list = overlays ?: runCatching {
            val body = http.newCall(okhttp3.Request.Builder().url(app.vela.core.config.VelaLinks.maxspeedManifest()).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return emptyList()
            val arr = org.json.JSONObject(body).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox")
                doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)) to "pmtiles://" + o.getString("url")
            }
        }.getOrNull()?.also { overlays = it } ?: return emptyList()
        return list.filter { (b, _) -> at.lat in b[0]..b[2] && at.lng in b[1]..b[3] && b[3] - b[1] < 60 }
            .sortedBy { (b, _) -> (b[2] - b[0]) * (b[3] - b[1]) }.map { it.second }.take(2)
    }

    /** Vela's open-data places overlays (one PMTiles archive per region), listed once per run. */
    private var placesOverlays: List<Pair<DoubleArray, String>>? = null

    suspend fun placesOverlayUri(at: LatLng): String? = withContext(Dispatchers.IO) {
        if (!online.value) return@withContext null
        val list = placesOverlays ?: runCatching {
            val body = http.newCall(okhttp3.Request.Builder().url(app.vela.core.config.VelaLinks.placesManifest()).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val arr = org.json.JSONObject(body).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox")
                doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)) to "pmtiles://" + o.getString("url")
            }
        }.getOrNull()?.also { placesOverlays = it } ?: return@withContext null
        list.filter { (b, _) -> at.lat in b[0]..b[2] && at.lng in b[1]..b[3] && b[3] - b[1] < 60 }
            .minByOrNull { (b, _) -> (b[2] - b[0]) * (b[3] - b[1]) }?.second
    }

    private fun onFix(loc: Location) {
        val at = LatLng(loc.latitude, loc.longitude)
        updateLimit(at, if (loc.hasSpeed()) loc.speed else null)
        _fix.value = Fix(at, if (loc.hasBearing()) loc.bearing else null, if (loc.hasSpeed()) loc.speed else null)
        if (::session.isInitialized && session.state.value.navigating) {
            checkCameras(at, if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            session.onLocation(
                at,
                imperial = imperial.value,
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
        val contacts = appContext?.let { DashContacts.addresses(it, t) }.orEmpty().map { contactPlace(it) }
        val local = DashPlaces.matching(t).map { it.place() } + contacts
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
        val contacts = appContext?.let { DashContacts.addresses(it, q) }.orEmpty()
        if (contacts.isNotEmpty()) {
            _state.value = State(Phase.RESULTS, q, results = contacts.map { contactPlace(it) })
            return
        }
        _state.value = State(Phase.SEARCHING, query = q)
        lastNear = _fix.value?.at
        morePage = 1
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
        if (place.id.startsWith("contact:")) {
            // A contact's address: found on the map first (the same address search), then routed.
            _state.value = _state.value.copy(phase = Phase.SEARCHING, query = place.address.orEmpty())
            scope.launch {
                val hit = withContext(Dispatchers.IO) { runCatching { lookup(place.address.orEmpty()).first.firstOrNull() }.getOrNull() }
                if (hit == null) _state.value = State(Phase.ERROR, error = "Couldn't find ${place.name}'s address on the map")
                else preview(hit.copy(name = place.name, address = hit.address ?: place.address))
            }
            return
        }
        val from = _fix.value?.at
        if (from == null) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = "Waiting for the phone's GPS")
            return
        }
        DashPlaces.addRecent(place)
        val keepStops = if (_state.value.destination?.location == place.location || place.id == "dash:start") _state.value.stops else emptyList()
        _state.value = _state.value.copy(phase = Phase.ROUTING, destination = place, routes = emptyList(), chosen = 0, stops = keepStops, details = null, alongRoute = false)
        noTollRoute.value = null
        camera.value = Camera.OVERVIEW
        loadDetails(place)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    source.directions(
                        from, place.location,
                        waypoints = keepStops.map { it.location },
                        avoidTolls = avoidTolls.value,
                        avoidHighways = avoidHighways.value,
                        avoidFerries = avoidFerries.value,
                    )
                }
            }
                .onSuccess { routes ->
                    _state.value = if (routes.isEmpty()) _state.value.copy(phase = Phase.ERROR, error = "No route found")
                    else _state.value.copy(phase = Phase.PREVIEW, routes = routes.take(4), chosen = 0)
                    // No-toll comparison: if tolls aren't avoided and the best route may have tolls, fetch a toll-free option.
                    if (!avoidTolls.value && routes.firstOrNull()?.tolls == app.vela.core.model.TollNote.MAY_HAVE_TOLLS &&
                        routes.none { it.tolls == app.vela.core.model.TollNote.NO_TOLLS }
                    ) {
                        scope.launch {
                            val free = runCatching {
                                withContext(Dispatchers.IO) {
                                    source.directions(
                                        from, place.location,
                                        waypoints = keepStops.map { it.location },
                                        avoidTolls = true,
                                        avoidHighways = avoidHighways.value,
                                        avoidFerries = avoidFerries.value,
                                    ).firstOrNull()
                                }
                            }.getOrNull()
                            if (free != null && _state.value.phase == Phase.PREVIEW && _state.value.destination?.location == place.location) {
                                noTollRoute.value = free
                            }
                        }
                    }
                }
                .onFailure {
                    GearslipLog.w("nav: directions failed: ${it.javaClass.simpleName}")
                    _state.value = _state.value.copy(phase = Phase.ERROR, error = "Couldn't get directions")
                }
        }
    }

    /** Switches the route preview to the fetched toll-free comparison route. */
    fun useNoTollRoute() {
        val free = noTollRoute.value ?: return
        val s = _state.value
        if (s.phase != Phase.PREVIEW) return
        val updated = (s.routes + free).distinctBy { it.polyline.size to it.distanceMeters.toInt() }.take(4)
        val idx = updated.indexOf(free).coerceAtLeast(0)
        _state.value = s.copy(routes = updated, chosen = idx)
        noTollRoute.value = null
    }

    /** Hours, open now, phone and gas price for [place], filled in when Google answers. */
    private fun loadDetails(place: Place) {
        // A search result already carries status, hours, phone and gas price (as in Vela).
        if (_state.value.destination?.id == place.id) _state.value = _state.value.copy(details = place)
        if (place.phone != null && place.statusText != null) return
        // Missing pieces: Vela's focused "name address" search, nearest match.
        scope.launch {
            val q = listOfNotNull(place.name, place.address).joinToString(" ")
            val hit = runCatching { withContext(Dispatchers.IO) { source.searchOnce(q, place.location) } }.getOrNull()
                ?.filter { metersBetween(it.location, place.location) < 150 }
                ?.minByOrNull { metersBetween(it.location, place.location) } ?: return@launch
            val merged = place.copy(
                phone = place.phone ?: hit.phone,
                statusText = place.statusText ?: hit.statusText,
                openNow = place.openNow ?: hit.openNow,
                hours = place.hours.ifEmpty { hit.hours },
                fuelPrice = place.fuelPrice ?: hit.fuelPrice,
                rating = place.rating ?: hit.rating,
                reviewCount = place.reviewCount ?: hit.reviewCount,
                website = place.website ?: hit.website,
            )
            if (_state.value.destination?.location == place.location) _state.value = _state.value.copy(details = merged)
        }
    }

    /** Opens a result's card (hours, phone) without routing yet. */
    fun details(place: Place) = loadDetails(place)

    private fun reroutePreview() {
        val s = _state.value
        if (s.phase == Phase.PREVIEW) s.destination?.let(::preview)
    }

    /** Adds [place] as a stop: a waypoint before Go, or into the running drive. */
    fun addStop(place: Place) {
        val s = _state.value
        if (session.state.value.navigating) {
            val here = _fix.value?.at ?: return
            session.addStop(NavSession.NavStop(place.location, place.name), here, atEnd = false)
            _state.value = s.copy(phase = Phase.NAVIGATING, results = emptyList(), alongRoute = false)
            GearslipLog.i("nav: stop added to the drive")
            return
        }
        val dest = s.destination ?: return
        _state.value = s.copy(stops = s.stops + place)
        preview(dest)
    }

    fun removeStop(place: Place) {
        val s = _state.value
        val dest = s.destination ?: return
        _state.value = s.copy(stops = s.stops - place)
        preview(dest)
    }

    /**
     * "Gas" while driving: searched around points on the road ahead, kept to the places near
     * the route, and priced with the minutes each adds to the trip (Vela's corridor search and
     * detour pricing), least detour first.
     */
    fun searchAlongRoute(query: String) {
        val route = session.state.value.route ?: _state.value.route ?: return search(query)
        val prior = _state.value
        _state.value = prior.copy(phase = Phase.SEARCHING, query = query)
        scope.launch {
            val priced = runCatching {
                withContext(Dispatchers.IO) {
                    val ahead = app.vela.core.data.RouteCorridor.ahead(route.polyline, session.state.value.nav.traveledM)
                    val centers = app.vela.core.data.RouteCorridor.searchCenters(ahead).take(4)
                    val found = centers.flatMap { c -> runCatching { source.search(query, near = c, spanMeters = 8_000.0).places }.getOrDefault(emptyList()) }
                    val near = app.vela.core.data.RouteCorridor.alongRoute(dedupe(found), ahead, 3_000.0).take(10)
                    source.detourAlongRoute(ahead, near) ?: near
                }
            }.getOrDefault(emptyList())
            _state.value = if (priced.isEmpty()) prior.copy(phase = if (session.state.value.navigating) Phase.NAVIGATING else prior.phase, error = "No \"$query\" along the route")
            else prior.copy(phase = Phase.RESULTS, query = query, results = priced, alongRoute = true)
        }
    }

    /** Back from along-route results to the drive or the preview. */
    fun closeResults() {
        val s = _state.value
        _state.value = when {
            session.state.value.navigating -> s.copy(phase = Phase.NAVIGATING, results = emptyList(), alongRoute = false)
            s.alongRoute && s.route != null -> s.copy(phase = Phase.PREVIEW, results = emptyList(), alongRoute = false)
            else -> State()
        }
    }

    // --- speed & red-light cameras -----------------------------------------------------------

    private val announced = mutableSetOf<String>()

    private fun loadCameras(route: Route) {
        cameras.value = emptyList()
        announced.clear()
        scope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    val speed = app.vela.core.data.OverpassSpeedCameras.fetchAlongCorridor(http, route.polyline)
                        .orEmpty().map { RouteCam(it.loc, redLight = false) }
                    val redLight = fetchRedLightCameras(route.polyline)
                    speed + redLight
                }
            }.getOrNull().orEmpty()
            cameras.value = found
            if (found.isNotEmpty()) GearslipLog.i("nav: ${found.size} cameras along the route (${found.count { it.redLight }} red-light)")
        }
    }

    /** Red-light enforcement cameras along [polyline] from Overpass (OSM `camera:type=red_light` / `enforcement=traffic_signals`). */
    private fun fetchRedLightCameras(polyline: List<LatLng>): List<RouteCam> {
        if (polyline.size < 2) return emptyList()
        val maxPts = 120
        val pts = if (polyline.size <= maxPts) polyline else {
            val stride = (polyline.size - 1).toDouble() / (maxPts - 1)
            (0 until maxPts).map { polyline[Math.round(it * stride).toInt().coerceAtMost(polyline.size - 1)] }
        }
        val coords = pts.joinToString(",") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng) }
        val q = "[out:json][timeout:20];(node[\"camera:type\"=\"red_light\"](around:150,$coords);node[\"enforcement\"=\"traffic_signals\"](around:150,$coords););out body 500;"
        return runCatching {
            val body = app.vela.core.data.OverpassEndpoints.run(http, q) { it.string() } ?: return emptyList()
            val arr = org.json.JSONObject(body).optJSONArray("elements") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else RouteCam(LatLng(lat, lon), redLight = true)
            }
        }.getOrDefault(emptyList())
    }

    private fun checkCameras(at: LatLng, speedMps: Double) {
        val list = cameras.value
        if (list.isEmpty()) { cameraAhead.value = null; return }
        val lead = app.vela.core.nav.CameraAlerts.leadDistanceM(speedMps.coerceAtLeast(8.0))
        val close = list.firstOrNull { metersBetween(it.loc, at) < lead }
        cameraAhead.value = close
        if (close != null) {
            val key = "${close.loc.lat},${close.loc.lng}"
            if (announced.add(key) && voiceOn.value) {
                DirectionsVoice.speak(if (close.redLight) "Red light camera ahead" else "Speed camera ahead")
            }
        }
    }

    /** Picks the interrupted drive back up: same destination and stops, routed from here. */
    fun resumeDrive() {
        val dest = pendingResume.value ?: return
        pendingResume.value = null
        _state.value = State(stops = pendingStops.map { it.place() }, destination = dest.place())
        preview(dest.place())
        scope.launch { awaitPreviewThenGo() }
    }

    fun dismissResume() {
        pendingResume.value = null
        prefs?.edit()?.remove("activeDrive")?.apply()
    }

    /** Through the stops and on to the destination, then back to where the trip starts. */
    fun roundTrip() {
        val s = _state.value
        val dest = s.destination ?: return
        val start = _fix.value?.at ?: return
        val home = Place(id = "dash:start", name = "Back to start", location = start)
        _state.value = s.copy(stops = s.stops + dest, destination = home)
        preview(home)
    }

    fun reverseStops() {
        val s = _state.value
        val dest = s.destination ?: return
        if (s.stops.size < 2) return
        _state.value = s.copy(stops = s.stops.reversed())
        preview(dest)
    }

    private fun contactPlace(a: DashContacts.Address) =
        Place(id = "contact:${a.name}", name = "${a.name} (contact)", location = LatLng(0.0, 0.0), address = a.address)

    /** A saved trip: its stops and destination, routed from here. */
    fun startTrip(t: DashPlaces.Trip) {
        _state.value = State(stops = t.stops.map { it.place() }, destination = t.dest.place())
        preview(t.dest.place())
    }

    fun saveTrip() {
        val s = _state.value
        val dest = s.destination ?: return
        DashPlaces.saveTrip(dest, s.stops)
    }

    /**
     * A map link shared from another app (a Google Maps link, a geo: URI, coordinates): parsed with
     * Vela's MapLinkParser (short links expanded first), then shown on the car's Map screen.
     */
    fun openLink(raw: String) {
        if (!ready) return
        scope.launch {
            val url = Regex("https?://\\S+").find(raw)?.value ?: raw.trim()
            val expanded = if (app.vela.core.data.ShortLinks.isShortener(url)) {
                withContext(Dispatchers.IO) { runCatching { app.vela.core.data.ShortLinks.resolve(http, url, calibration.current().userAgent) }.getOrNull() } ?: url
            } else url
            val link = app.vela.core.data.MapLinkParser.parse(expanded) ?: app.vela.core.data.MapLinkParser.parseBareCoordinate(raw.trim())
            GearslipLog.i("nav: shared link -> ${if (link == null) "not a map link" else "parsed"}")
            showMap.value++
            val lat = link?.lat
            val lng = link?.lng
            when {
                lat != null && lng != null ->
                    preview(Place(id = "link:$lat,$lng", name = link.query ?: "Shared location", location = LatLng(lat, lng)))
                !link?.query.isNullOrBlank() -> search(link!!.query!!)
                else -> search(raw.trim().take(120))
            }
        }
    }

    /**
     * "Closes 8:30 PM" read off the place's status, against the arrival time: a warning when the
     * place closes before, or within 15 minutes after, the truck gets there. Null when fine or unknown.
     */
    fun closingWarning(status: String?, arriveAtMs: Long): String? {
        val m = Regex("""[Cc]loses\s+(\d{1,2})(?::(\d{2}))?\s*([AaPp])\.?[Mm]""").find(status ?: return null) ?: return null
        var h = m.groupValues[1].toInt() % 12
        if (m.groupValues[3].equals("p", true)) h += 12
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis() - 3_600_000) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val left = (cal.timeInMillis - arriveAtMs) / 60_000
        return when {
            left < 0 -> "Closes before you get there"
            left < 15 -> "Closes $left min after you arrive"
            else -> null
        }
    }

    /** Where the last full search was centered, for More results. */
    private var lastNear: LatLng? = null
    private var morePage = 1

    /** The same search again, around where the map is looking (after a pan). */
    fun searchHere(center: LatLng) {
        val q = _state.value.query.ifBlank { return }
        _state.value = State(Phase.SEARCHING, query = q)
        lastNear = center
        morePage = 1
        scope.launch {
            val places = runCatching { withContext(Dispatchers.IO) { source.search(q, near = center, spanMeters = SEARCH_SPAN_M, rankFrom = center).places } }.getOrDefault(emptyList())
            _state.value = if (places.isEmpty()) State(Phase.ERROR, q, error = "Nothing found here for \"$q\"") else State(Phase.RESULTS, q, results = places)
        }
    }

    /** The next page of the current results. */
    fun moreResults() {
        val s = _state.value
        if (s.phase != Phase.RESULTS || s.alongRoute) return
        val near = lastNear ?: _fix.value?.at
        scope.launch {
            val more = runCatching {
                withContext(Dispatchers.IO) { source.searchMore(s.query, near, SEARCH_SPAN_M, _fix.value?.at, fromPage = morePage, pages = 1) }
            }.getOrDefault(emptyList())
            morePage++
            if (_state.value.query == s.query) _state.value = _state.value.copy(results = dedupe(_state.value.results + more))
        }
    }

    /** Other locations of the same business (a chain's branches), around here. */
    fun otherLocations(place: Place) {
        val q = place.name
        _state.value = State(Phase.SEARCHING, query = q)
        scope.launch {
            val branches = runCatching {
                withContext(Dispatchers.IO) { source.searchBranches(q, place, listOf(place), near = _fix.value?.at, spanMeters = 40_000.0, rankFrom = _fix.value?.at) }
            }.getOrDefault(emptyList())
            _state.value = State(Phase.RESULTS, q, results = dedupe(listOf(place) + branches))
        }
    }

    /** Moves a stop one place earlier in the order. */
    fun moveStopEarlier(place: Place) {
        val s = _state.value
        val i = s.stops.indexOf(place)
        val dest = s.destination ?: return
        if (i <= 0) return
        val list = s.stops.toMutableList().apply { add(i - 1, removeAt(i)) }
        _state.value = s.copy(stops = list)
        preview(dest)
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
            stops = s.stops.map { NavSession.NavStop(it.location, it.name) },
            destinationAddress = dest.address.orEmpty(),
            from = _fix.value?.at,
            fromCourseDeg = _fix.value?.bearing?.toDouble(),
        )
        loadCameras(route)
        prefs?.edit()?.putString(
            "activeDrive",
            org.json.JSONObject()
                .put("dest", Spot.of(dest, kind = "recent").json())
                .put("stops", org.json.JSONArray().apply { s.stops.forEach { put(Spot.of(it, kind = "recent").json()) } })
                .toString(),
        )?.apply()
        pendingResume.value = null
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
        prefs?.edit()?.remove("activeDrive")?.apply()
        cameras.value = emptyList()
        cameraAhead.value = null
        noTollRoute.value = null
        _state.value = State()
        camera.value = Camera.FOLLOW
        GearslipLog.i("nav: navigation ended")
    }

    fun clear() {
        if (_state.value.phase != Phase.NAVIGATING) {
            noTollRoute.value = null
            _state.value = State()
            camera.value = Camera.FOLLOW
        }
    }

    private const val ADDRESS_SPAN_M = 40_000.0 // Vela's ADDRESS_SEARCH_SPAN_M
    private const val SEARCH_SPAN_M = 30_000.0
}
