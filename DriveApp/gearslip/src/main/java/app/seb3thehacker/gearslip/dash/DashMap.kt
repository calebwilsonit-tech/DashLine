package app.seb3thehacker.gearslip.dash

import android.content.Context
import android.view.ViewGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarAssistant
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The map: one MapLibre view (OpenFreeMap vector tiles, no key), kept alive for the whole drive
 * and moved between the dashboard pane and the full Map screen, so switching screens never
 * reloads tiles. Route, position and turn-by-turn come from [DashNav] (Vela's engine).
 */
internal object DashMapHolder {
    private var view: MapView? = null
    var map: MapLibreMap? = null
        private set
    var style: Style? = null
        private set
    private var dark: Boolean? = null

    fun view(context: Context, dark: Boolean): MapView {
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            if (this.dark != dark) setStyle(dark)
            return v
        }
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        return MapView(context.applicationContext, options).apply {
            onCreate(null)
            onStart()
            onResume()
            getMapAsync { m ->
                map = m
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.cameraPosition = CameraPosition.Builder().target(LatLng(39.5, -98.35)).zoom(3.0).build()
                setStyle(dark)
            }
            view = this
        }
    }

    private fun setStyle(dark: Boolean) {
        val m = map ?: return
        this.dark = dark
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { s ->
            style = s
            s.addSource(GeoJsonSource(ROUTE_SRC))
            s.addLayer(
                LineLayer(ROUTE_CASING, ROUTE_SRC).withProperties(
                    PropertyFactory.lineColor("#0B2E59"),
                    PropertyFactory.lineWidth(11f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addLayer(
                LineLayer(ROUTE_LINE, ROUTE_SRC).withProperties(
                    PropertyFactory.lineColor("#3D8BFF"),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addSource(GeoJsonSource(ME_SRC))
            s.addLayer(
                CircleLayer(ME_HALO, ME_SRC).withProperties(
                    PropertyFactory.circleRadius(16f),
                    PropertyFactory.circleColor("#3D8BFF"),
                    PropertyFactory.circleOpacity(0.2f),
                ),
            )
            s.addLayer(
                CircleLayer(ME_DOT, ME_SRC).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor("#3D8BFF"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            lastRoute = null
            updateRoute(routePoints)
            updateMe(meAt)
        }
    }

    private var routePoints: List<app.vela.core.model.LatLng> = emptyList()
    private var lastRoute: List<app.vela.core.model.LatLng>? = null
    private var meAt: app.vela.core.model.LatLng? = null

    fun updateRoute(points: List<app.vela.core.model.LatLng>) {
        routePoints = points
        if (lastRoute === points) return
        val src = style?.getSourceAs<GeoJsonSource>(ROUTE_SRC) ?: return
        lastRoute = points
        if (points.size < 2) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))
        else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lng, it.lat) })))
    }

    fun updateMe(at: app.vela.core.model.LatLng?) {
        meAt = at
        val src = style?.getSourceAs<GeoJsonSource>(ME_SRC) ?: return
        if (at != null) src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(at.lng, at.lat)))
    }

    private const val ROUTE_SRC = "dash-route"
    private const val ROUTE_LINE = "dash-route-line"
    private const val ROUTE_CASING = "dash-route-casing"
    private const val ME_SRC = "dash-me"
    private const val ME_DOT = "dash-me-dot"
    private const val ME_HALO = "dash-me-halo"
    private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
}

/** The live map, camera following the car. [compact] is the dashboard pane. */
@Composable
internal fun DashMapView(modifier: Modifier, compact: Boolean) {
    val context = LocalContext.current
    val t = LocalDashTheme.current
    val dark = t.bgTop.red + t.bgTop.green + t.bgTop.blue < 1.2f
    androidx.compose.runtime.remember { DashNav.init(context); true }
    val fix by DashNav.fix.collectAsState()
    val nav by DashNav.session.state.collectAsState()
    val ui by DashNav.state.collectAsState()

    AndroidView(
        modifier = modifier,
        factory = { c -> DashMapHolder.view(c, dark) },
    )

    // Position, route and camera. Only the map's own GL thread redraws; Compose does nothing per frame.
    LaunchedEffect(fix, nav.route, ui.routes, ui.phase, compact) {
        val m = DashMapHolder.map ?: return@LaunchedEffect
        DashMapHolder.updateMe(fix?.at)
        val line = nav.route?.polyline ?: ui.routes.firstOrNull()?.polyline.orEmpty()
        DashMapHolder.updateRoute(line)
        val f = fix
        when {
            nav.navigating && f != null -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(f.at.lat, f.at.lng))
                        .zoom(if (compact) 15.5 else 16.5)
                        .tilt(if (compact) 0.0 else 45.0)
                        .bearing((f.bearing ?: 0f).toDouble())
                        .padding(0.0, if (compact) 0.0 else 160.0, 0.0, 0.0)
                        .build(),
                ),
                900,
            )
            ui.phase == DashNav.Phase.PREVIEW && line.size >= 2 -> {
                val b = LatLngBounds.Builder().apply { line.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 60), 700)
            }
            f != null -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(f.at.lat, f.at.lng)).zoom(if (compact) 13.5 else 15.0).tilt(0.0).bearing(0.0).build(),
                ),
                700,
            )
        }
    }
}

/** The full Map screen: map, turn card, search and route preview. */
@Composable
internal fun DashMapScreen() {
    val t = LocalDashTheme.current
    val context = LocalContext.current
    androidx.compose.runtime.remember { DashNav.init(context); true }
    val ui by DashNav.state.collectAsState()
    val nav by DashNav.session.state.collectAsState()

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(t.radius))) {
        DashMapView(Modifier.fillMaxSize(), compact = false)
        if (nav.navigating) {
            TurnCard(Modifier.align(Alignment.TopStart).padding(10.dp))
            TripBar(Modifier.align(Alignment.BottomCenter).padding(10.dp))
        } else {
            when (ui.phase) {
                DashNav.Phase.IDLE, DashNav.Phase.ERROR -> WhereTo(Modifier.align(Alignment.TopStart).padding(10.dp), ui.error)
                DashNav.Phase.SEARCHING -> Status(Modifier.align(Alignment.TopStart).padding(10.dp), "Searching \"${ui.query}\"…")
                DashNav.Phase.RESULTS -> Results(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxHeight(), ui.results)
                DashNav.Phase.ROUTING -> Status(Modifier.align(Alignment.TopStart).padding(10.dp), "Getting directions…")
                DashNav.Phase.PREVIEW -> Preview(Modifier.align(Alignment.BottomCenter).padding(10.dp))
                DashNav.Phase.NAVIGATING -> Unit
            }
        }
    }
}

/** "Where to?": speak a place, an address or a business. */
@Composable
private fun WhereTo(modifier: Modifier, error: String) {
    val t = LocalDashTheme.current
    Column(modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tap(onTap = ::askWhereTo, modifier = Modifier.fillMaxWidth().height(56.dp), color = t.panel, contentAlignment = Alignment.CenterStart) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Mic, t.accent, 24.dp)
                Spacer(Modifier.width(10.dp))
                Label("Where to?", 18.sp, weight = FontWeight.SemiBold)
            }
        }
        if (error.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Label(error, 14.sp, Color(0xFFFF6B6B), maxLines = 2, modifier = Modifier.padding(10.dp)) }
    }
}

private fun askWhereTo() {
    CarAssistant.say("Where to?") {
        CarAssistant.dictate(
            onPartial = {},
            onResult = { heard -> if (heard.isNotBlank()) DashNav.search(heard) },
            onError = { GearslipLog.w("nav: where-to dictation: $it") },
        )
    }
}

@Composable
private fun Status(modifier: Modifier, text: String) {
    Card(modifier.width(300.dp)) { Label(text, 16.sp, modifier = Modifier.padding(14.dp)) }
}

@Composable
private fun Results(modifier: Modifier, places: List<Place>) {
    val t = LocalDashTheme.current
    val fix by DashNav.fix.collectAsState()
    Card(modifier.width(330.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Results", 15.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(6.dp))
                Spacer(Modifier.weight(1f))
                Tap(onTap = DashNav::clear, modifier = Modifier.size(70.dp, 34.dp), color = t.panelHi) { Label("Back", 13.sp) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(places.size) { i ->
                    val p = places[i]
                    Tap(
                        onTap = { DashNav.preview(p) },
                        onTapUp = true,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        color = t.panelHi,
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            Row {
                                Label(p.name, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                fix?.let { f -> Label(miles(metersBetween(f.at, p.location)), 13.sp, t.textDim) }
                            }
                            Label(p.address ?: p.category.orEmpty(), 12.sp, t.textDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(modifier: Modifier) {
    val t = LocalDashTheme.current
    val ui by DashNav.state.collectAsState()
    val r = ui.routes.firstOrNull() ?: return
    Card(modifier.fillMaxWidth().height(84.dp)) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(ui.destination?.name.orEmpty(), 17.sp, weight = FontWeight.SemiBold)
                Label("${minutes(r.durationInTrafficSeconds ?: r.durationSeconds)} · ${miles(r.distanceMeters)}", 15.sp, t.accent)
            }
            Tap(onTap = DashNav::clear, modifier = Modifier.size(90.dp, 52.dp), color = t.panelHi) { Label("Cancel", 15.sp) }
            Spacer(Modifier.width(8.dp))
            Tap(onTap = { DashNav.go() }, modifier = Modifier.size(110.dp, 52.dp), color = Color(0xFF1E9E4A)) {
                Label("Go", 18.sp, Color.White, FontWeight.Bold)
            }
        }
    }
}

/** The next turn, big and simple. */
@Composable
private fun TurnCard(modifier: Modifier) {
    val t = LocalDashTheme.current
    val nav by DashNav.session.state.collectAsState()
    val step = nav.route?.maneuvers?.getOrNull(nav.nav.stepIndex + 1) ?: nav.route?.maneuvers?.getOrNull(nav.nav.stepIndex)
    Box(modifier.width(330.dp).clip(RoundedCornerShape(t.radius)).background(Color(0xFF0F5132))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(arrow(step?.type), 40.sp, Color.White, FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column {
                Label(miles(nav.nav.distanceToNextManeuver), 24.sp, Color.White, FontWeight.Bold)
                Label(step?.road ?: nav.maneuverText.ifBlank { step?.instruction.orEmpty() }, 15.sp, Color.White, maxLines = 2)
            }
        }
    }
}

@Composable
private fun TripBar(modifier: Modifier) {
    val t = LocalDashTheme.current
    val nav by DashNav.session.state.collectAsState()
    Card(modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(minutes(nav.remainingDuration), 20.sp, Color(0xFF34C759), FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Label("${miles(nav.remainingDistance)} · arrive ${arrival(nav.remainingDuration)}", 15.sp, t.textDim, modifier = Modifier.weight(1f))
            Tap(onTap = DashNav::stop, modifier = Modifier.size(90.dp, 46.dp), color = Color(0xFFC62828)) {
                Label("End", 16.sp, Color.White, FontWeight.SemiBold)
            }
        }
    }
}

private fun arrow(type: ManeuverType?) = when (type) {
    ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT -> "⬅"
    ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT -> "➡"
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> "↖"
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT -> "↗"
    ManeuverType.UTURN -> "↩"
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> "⟳"
    ManeuverType.ARRIVE -> "⚑"
    else -> "⬆"
}

internal fun metersBetween(a: app.vela.core.model.LatLng, b: app.vela.core.model.LatLng): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLng / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(h))
}

internal fun miles(meters: Double): String {
    val mi = meters / 1609.344
    return when {
        mi < 0.1 -> "${(meters * 3.28084 / 10).roundToInt() * 10} ft"
        mi < 10 -> String.format(Locale.US, "%.1f mi", mi)
        else -> "${mi.roundToInt()} mi"
    }
}

internal fun minutes(seconds: Double): String {
    val m = (seconds / 60).roundToInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

private fun arrival(seconds: Double): String =
    android.text.format.DateFormat.format("h:mm a", System.currentTimeMillis() + (seconds * 1000).toLong()).toString()
