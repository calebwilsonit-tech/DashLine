package app.seb3thehacker.gearslip.dash

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarAssistant
import app.seb3thehacker.gearslip.car.CarKeyboard
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import app.vela.core.model.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
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
    private var styleUrl: String? = null

    fun view(context: Context, url: String): MapView {
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            if (styleUrl != url) setStyle(url)
            return v
        }
        appCtx = context.applicationContext
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
                // A pan by hand stops the camera following until Re-center.
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) DashNav.camera.value = DashNav.Camera.FREE
                }
                setStyle(pendingUrl ?: LIGHT_STYLE)
            }
            view = this
            pendingUrl = url
        }
    }

    private var pendingUrl: String? = null

    /** The OpenFreeMap style for a choice ("auto" follows the theme's light or dark). */
    fun styleUrl(choice: String, dark: Boolean) = when (choice) {
        "liberty", "bright", "positron", "fiord", "dark" -> "https://tiles.openfreemap.org/styles/$choice"
        "satellite" -> SATELLITE
        else -> if (dark) DARK_STYLE else LIGHT_STYLE
    }

    private var appCtx: Context? = null

    /**
     * Offline with a downloaded map-tile file: the same OpenFreeMap style (saved while online),
     * with its tile source pointed at the file. Null when online or nothing covers the spot.
     */
    private fun offlineStyleJson(url: String): String? {
        if (DashNav.online.value || url == SATELLITE) return null
        val ctx = appCtx ?: return null
        val at = DashNav.fix.value?.at ?: return null
        val file = DashOffline.tilesFor(at.lat, at.lng) ?: return null
        val saved = java.io.File(ctx.filesDir, "styles/${url.substringAfterLast('/')}.json").takeIf { it.exists() } ?: return null
        return runCatching {
            val o = org.json.JSONObject(saved.readText())
            val sources = o.getJSONObject("sources")
            sources.getJSONObject("openmaptiles").apply { remove("tiles"); put("url", "pmtiles://file://${file.absolutePath}") }
            // The shaded-relief raster is online-only.
            sources.remove("ne2_shaded")
            val layers = o.getJSONArray("layers")
            val kept = org.json.JSONArray()
            for (i in 0 until layers.length()) layers.getJSONObject(i).let { if (it.optString("source") != "ne2_shaded") kept.put(it) }
            o.put("layers", kept)
            GearslipLog.i("map: offline, drawing from downloaded tiles")
            o.toString()
        }.getOrNull()
    }

    /** Keeps a copy of each OpenFreeMap style for offline use. */
    private fun saveStyle(url: String) {
        val ctx = appCtx ?: return
        if (!url.startsWith("https://tiles.openfreemap.org/styles/")) return
        val f = java.io.File(ctx.filesDir, "styles/${url.substringAfterLast('/')}.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) return
        Thread {
            runCatching {
                val body = java.net.URL(url).openStream().use { it.readBytes() }
                f.parentFile?.mkdirs(); f.writeBytes(body)
            }
        }.start()
    }

    fun setStyle(url: String) {
        val m = map ?: run { pendingUrl = url; return }
        val offline = offlineStyleJson(url)
        val key = if (offline != null) "$url#offline" else url
        if (styleUrl == key && style != null) return
        styleUrl = key
        if (offline == null) saveStyle(url)
        val builder = when {
            offline != null -> Style.Builder().fromJson(offline)
            url == SATELLITE -> Style.Builder().fromJson(SATELLITE_JSON)
            else -> Style.Builder().fromUri(url)
        }
        m.setStyle(builder) { s ->
            style = s
            s.addImage(ARROW_IMG, arrowBitmap())
            // Google's live traffic, as Vela draws it while browsing (hidden during a drive: the
            // route line carries the traffic then).
            s.addSource(org.maplibre.android.style.sources.RasterSource(TRAFFIC_TILES_SRC, org.maplibre.android.style.sources.TileSet("2.2.0", TRAFFIC_TILES).apply { maxZoom = 16f }, 256))
            s.addLayer(org.maplibre.android.style.layers.RasterLayer(TRAFFIC_TILES_LAYER, TRAFFIC_TILES_SRC).withProperties(PropertyFactory.rasterOpacity(0.9f)))
            trafficShown = null
            s.addSource(GeoJsonSource(ALT_SRC))
            s.addLayer(
                LineLayer(ALT_LINE, ALT_SRC).withProperties(
                    PropertyFactory.lineColor("#8A94A6"),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineOpacity(0.8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
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
            // Traffic on the route: slow orange, jammed red, standstill dark red.
            s.addSource(GeoJsonSource(TRAFFIC_SRC))
            s.addLayer(
                LineLayer(TRAFFIC_LINE, TRAFFIC_SRC).withProperties(
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("level"),
                            Expression.literal("#3D8BFF"),
                            Expression.stop(1, "#F5A623"),
                            Expression.stop(2, "#E53935"),
                            Expression.stop(3, "#8B1A1A"),
                        ),
                    ),
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
            // Which way the truck is heading, when the GPS knows.
            s.addLayer(
                org.maplibre.android.style.layers.SymbolLayer(ME_ARROW, ME_SRC).withProperties(
                    PropertyFactory.iconImage(ARROW_IMG),
                    PropertyFactory.iconRotate(Expression.get("b")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            ).also { s.getLayer(ME_ARROW)?.setProperties(PropertyFactory.visibility(Property.VISIBLE)) }
            // Road numbers along the route ("I 5"), like the shields Vela draws on the line.
            s.addSource(GeoJsonSource(SHIELD_SRC))
            s.addLayer(
                org.maplibre.android.style.layers.SymbolLayer(SHIELD_LAYER, SHIELD_SRC).withProperties(
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                    PropertyFactory.symbolSpacing(350f),
                    PropertyFactory.textField(Expression.get("ref")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#1C4FB8"),
                    PropertyFactory.textHaloWidth(3f),
                    PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT),
                ),
            )
            // The next turn drawn on the route: a white stroke through it ending in an arrowhead.
            s.addImage(TURN_IMG, turnHeadBitmap())
            s.addSource(GeoJsonSource(TURN_SRC))
            s.addLayer(
                LineLayer(TURN_LINE, TURN_SRC).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).also { it.setFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString"))) },
            )
            s.addLayer(
                org.maplibre.android.style.layers.SymbolLayer(TURN_HEAD, TURN_SRC).withProperties(
                    PropertyFactory.iconImage(TURN_IMG),
                    PropertyFactory.iconRotate(Expression.get("b")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ).also { it.setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))) },
            )
            lastTurnKey = null
            s.addSource(GeoJsonSource(CAM_SRC))
            s.addLayer(
                CircleLayer(CAM_DOT, CAM_SRC).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#FF9800"),
                    PropertyFactory.circleStrokeColor("#000000"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            lastCams = null
            s.addSource(GeoJsonSource(PIN_SRC))
            s.addLayer(
                CircleLayer(PIN_DOT, PIN_SRC).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor("#E53935"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            lastRoute = null
            lastAlts = null
            lastPins = null
            draw(routeNow, altsNow, meAt, pinsNow)
        }
    }

    private var routeNow: Route? = null
    private var altsNow: List<Route> = emptyList()
    private var pinsNow: List<app.vela.core.model.LatLng> = emptyList()
    private var lastRoute: Route? = null
    private var lastAlts: List<Route>? = null
    private var lastPins: List<app.vela.core.model.LatLng>? = null
    private var meAt: app.vela.core.model.LatLng? = null

    /** Updates the map's data; only what changed is re-sent to the GL thread. */
    private var bearingNow: Float? = null

    fun draw(route: Route?, alts: List<Route>, me: app.vela.core.model.LatLng?, pins: List<app.vela.core.model.LatLng>, bearing: Float? = bearingNow) {
        routeNow = route; altsNow = alts; meAt = me; pinsNow = pins; bearingNow = bearing
        val s = style ?: return
        me?.let {
            val f = Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat))
            bearing?.let { b -> f.addNumberProperty("b", b) }
            s.getSourceAs<GeoJsonSource>(ME_SRC)?.setGeoJson(f)
            s.getLayer(ME_ARROW)?.setProperties(PropertyFactory.visibility(if (bearing != null) Property.VISIBLE else Property.NONE))
        }
        if (route !== lastRoute) {
            lastRoute = route
            val line = route?.polyline.orEmpty()
            s.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(lineFeature(line))
            s.getSourceAs<GeoJsonSource>(TRAFFIC_SRC)?.setGeoJson(trafficFeatures(route))
            s.getSourceAs<GeoJsonSource>(SHIELD_SRC)?.setGeoJson(shieldFeatures(route))
        }
        if (alts !== lastAlts) {
            lastAlts = alts
            s.getSourceAs<GeoJsonSource>(ALT_SRC)?.setGeoJson(FeatureCollection.fromFeatures(alts.map { lineFeature(it.polyline) }))
        }
        if (pins !== lastPins) {
            lastPins = pins
            s.getSourceAs<GeoJsonSource>(PIN_SRC)?.setGeoJson(FeatureCollection.fromFeatures(pins.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) }))
        }
    }

    private fun lineFeature(line: List<app.vela.core.model.LatLng>): Feature =
        Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lng, it.lat) }))

    /** The route cut into the stretches Google reports as slow or jammed. */
    private fun trafficFeatures(route: Route?): FeatureCollection {
        val r = route ?: return FeatureCollection.fromFeatures(emptyList())
        val line = r.polyline
        if (line.size < 2 || r.trafficSpans.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + metersBetween(line[i - 1], line[i])
        val out = mutableListOf<Feature>()
        for (span in r.trafficSpans) {
            if (span.level < 1) continue
            val a = span.startMeters
            val b = span.startMeters + span.lengthMeters
            val pts = line.indices.filter { cum[it] in a..b }.map { line[it] }
            if (pts.size < 2) continue
            out += Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) })).apply {
                addNumberProperty("level", span.level.coerceAtMost(3))
            }
        }
        return FeatureCollection.fromFeatures(out)
    }

    private var trafficShown: Boolean? = null
    private var lastCams: List<app.vela.core.model.LatLng>? = null

    fun showTraffic(on: Boolean) {
        val s = style ?: return
        if (trafficShown == on) return
        trafficShown = on
        s.getLayer(TRAFFIC_TILES_LAYER)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    fun drawCameras(cams: List<app.vela.core.model.LatLng>) {
        val s = style ?: return
        if (cams === lastCams) return
        lastCams = cams
        s.getSourceAs<GeoJsonSource>(CAM_SRC)?.setGeoJson(FeatureCollection.fromFeatures(cams.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) }))
    }

    private var lastTurnKey: String? = null

    /** Draws the turn after [stepIndex] on the route; nothing when [route] is null. */
    fun drawTurn(route: Route?, stepIndex: Int) {
        val s = style ?: return
        val step = route?.maneuvers?.getOrNull(stepIndex + 1)
        val key = if (route == null || step == null) "" else "${System.identityHashCode(route)}:$stepIndex"
        if (key == lastTurnKey) return
        lastTurnKey = key
        val src = s.getSourceAs<GeoJsonSource>(TURN_SRC) ?: return
        if (route == null || step == null || step.type == ManeuverType.ARRIVE || step.type == ManeuverType.CONTINUE || step.type == ManeuverType.STRAIGHT) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return
        }
        val line = route.polyline
        if (line.size < 2) return
        val at = line.indices.minByOrNull { metersBetween(line[it], step.location) } ?: return
        fun walk(from: Int, dir: Int, meters: Double): List<app.vela.core.model.LatLng> {
            val out = mutableListOf(line[from]); var i = from; var d = 0.0
            while (d < meters && i + dir in line.indices) { d += metersBetween(line[i], line[i + dir]); i += dir; out += line[i] }
            return out
        }
        val pts = walk(at, -1, 35.0).reversed() + walk(at, 1, 25.0).drop(1)
        if (pts.size < 2) return
        val a = pts[pts.size - 2]; val b = pts.last()
        val bearing = Math.toDegrees(Math.atan2(
            Math.sin(Math.toRadians(b.lng - a.lng)) * Math.cos(Math.toRadians(b.lat)),
            Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat)) -
                Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.cos(Math.toRadians(b.lng - a.lng)),
        ))
        val head = Feature.fromGeometry(Point.fromLngLat(b.lng, b.lat)).apply { addNumberProperty("b", bearing) }
        src.setGeoJson(FeatureCollection.fromFeatures(listOf(lineFeature(pts), head)))
    }

    /** One line per stretch of numbered road, labeled with its number. */
    private fun shieldFeatures(route: Route?): FeatureCollection {
        val r = route ?: return FeatureCollection.fromFeatures(emptyList())
        val line = r.polyline
        val steps = r.maneuvers
        if (line.size < 2 || steps.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
        val idx = steps.map { m -> line.indices.minByOrNull { metersBetween(line[it], m.location) } ?: 0 }
        val out = mutableListOf<Feature>()
        steps.forEachIndexed { i, m ->
            val ref = m.ref?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val from = idx[i]; val to = idx.getOrNull(i + 1) ?: line.lastIndex
            if (to - from < 2) return@forEachIndexed
            out += lineFeature(line.subList(from, to + 1)).apply { addStringProperty("ref", ref) }
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun center(): app.vela.core.model.LatLng? = map?.cameraPosition?.target?.let { app.vela.core.model.LatLng(it.latitude, it.longitude) }

    fun zoomBy(delta: Double) {
        DashNav.camera.value = DashNav.Camera.FREE
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta), 250)
    }

    private const val ROUTE_SRC = "dash-route"
    private const val ROUTE_LINE = "dash-route-line"
    private const val ROUTE_CASING = "dash-route-casing"
    private const val ALT_SRC = "dash-alts"
    private const val ALT_LINE = "dash-alts-line"
    private const val TRAFFIC_SRC = "dash-traffic"
    private const val TRAFFIC_LINE = "dash-traffic-line"
    private const val ME_SRC = "dash-me"
    private const val ME_DOT = "dash-me-dot"
    private const val ME_HALO = "dash-me-halo"
    private const val SATELLITE = "satellite"
    private val SATELLITE_JSON = """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{"sat":{"type":"raster","tiles":["https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"],"tileSize":256,"maxzoom":20}},"layers":[{"id":"sat","type":"raster","source":"sat"}]}"""
    private const val SHIELD_SRC = "dash-shields"
    private const val SHIELD_LAYER = "dash-shields-layer"
    private const val TURN_SRC = "dash-turn"
    private const val TURN_LINE = "dash-turn-line"
    private const val TURN_HEAD = "dash-turn-head"
    private const val TURN_IMG = "dash-turn-img"

    private fun turnHeadBitmap(): android.graphics.Bitmap {
        val size = 48
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply { moveTo(size / 2f, 2f); lineTo(size - 4f, size - 6f); lineTo(4f, size - 6f); close() }
        c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        return bmp
    }

    private const val ME_ARROW = "dash-me-arrow"
    private const val ARROW_IMG = "dash-arrow"

    /** A white-edged blue chevron pointing up (north), rotated by the heading. */
    private fun arrowBitmap(): android.graphics.Bitmap {
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(size / 2f, 4f); lineTo(size - 10f, size - 8f); lineTo(size / 2f, size * 0.68f); lineTo(10f, size - 8f); close()
        }
        c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 8f; strokeJoin = android.graphics.Paint.Join.ROUND })
        c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() })
        return bmp
    }

    private const val CAM_SRC = "dash-cams"
    private const val CAM_DOT = "dash-cams-dot"
    private const val TRAFFIC_TILES_SRC = "dash-traffic-tiles"
    private const val TRAFFIC_TILES_LAYER = "dash-traffic-tiles-layer"
    // Vela's traffic tile template (VelaMapView.TRAFFIC_TILES).
    private const val TRAFFIC_TILES =
        "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
            "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"
    private const val PIN_SRC = "dash-pins"
    private const val PIN_DOT = "dash-pins-dot"
    private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
}

/** The live map, camera following the car. [compact] is the dashboard pane. */
@Composable
internal fun DashMapView(modifier: Modifier, compact: Boolean) {
    val context = LocalContext.current
    val t = LocalDashTheme.current
    val dark = t.bgTop.red + t.bgTop.green + t.bgTop.blue < 1.2f
    remember { DashNav.init(context); true }
    val styleChoice by DashNav.mapStyle.collectAsState()
    val url = DashMapHolder.styleUrl(styleChoice, dark)
    val online by DashNav.online.collectAsState()
    LaunchedEffect(url, online) { DashMapHolder.setStyle(url) }
    val fix by DashNav.fix.collectAsState()
    val nav by DashNav.session.state.collectAsState()
    val ui by DashNav.state.collectAsState()
    val cam by DashNav.camera.collectAsState()
    val northUp by DashNav.northUp.collectAsState()
    val traffic by DashNav.trafficLayer.collectAsState()
    val cams by DashNav.cameras.collectAsState()

    AndroidView(modifier = modifier, factory = { c -> DashMapHolder.view(c, url) })
    LaunchedEffect(traffic, nav.navigating, cams) {
        DashMapHolder.showTraffic(traffic && !nav.navigating)
        DashMapHolder.drawCameras(cams)
    }

    LaunchedEffect(fix, nav.route, ui.routes, ui.chosen, ui.phase, ui.results, cam, northUp, compact) {
        val m = DashMapHolder.map ?: return@LaunchedEffect
        val route = nav.route ?: ui.route
        val alts = if (!nav.navigating && ui.phase == DashNav.Phase.PREVIEW) ui.routes.filterIndexed { i, _ -> i != ui.chosen } else emptyList()
        val pins = when (ui.phase) {
            DashNav.Phase.RESULTS -> ui.results.map { it.location }
            DashNav.Phase.PREVIEW, DashNav.Phase.ROUTING -> listOfNotNull(ui.destination?.location)
            else -> emptyList()
        }
        DashMapHolder.draw(route, alts, fix?.at, pins, fix?.bearing?.takeIf { (fix?.speedMps ?: 0f) > 1.5f })
        DashMapHolder.drawTurn(if (nav.navigating) nav.route else null, nav.nav.stepIndex)
        val f = fix
        val overviewLine = route?.polyline ?: pins
        when {
            cam == DashNav.Camera.FREE -> Unit
            (cam == DashNav.Camera.OVERVIEW || ui.phase == DashNav.Phase.RESULTS) && overviewLine.size >= 2 -> {
                val b = LatLngBounds.Builder().apply {
                    overviewLine.forEach { include(LatLng(it.lat, it.lng)) }
                    f?.let { include(LatLng(it.at.lat, it.at.lng)) }
                }.build()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, if (compact) 30 else 70), 700)
            }
            nav.navigating && f != null -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(f.at.lat, f.at.lng))
                        .zoom(if (compact) 15.5 else 16.5)
                        .tilt(if (compact || northUp) 0.0 else 45.0)
                        .bearing(if (northUp) 0.0 else (f.bearing ?: 0f).toDouble())
                        .padding(0.0, if (compact) 0.0 else 160.0, 0.0, 0.0)
                        .build(),
                ),
                900,
            )
            f != null -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(f.at.lat, f.at.lng)).zoom(if (compact) 13.5 else 15.0).tilt(0.0).bearing(0.0).build(),
                ),
                700,
            )
        }
    }
}

// --- the full Map screen ----------------------------------------------------------------------

/** The full Map screen: map, search (typed or spoken), places, routes and the drive. */
@Composable
internal fun DashMapScreen() {
    val t = LocalDashTheme.current
    val context = LocalContext.current
    remember { DashNav.init(context); true }
    val ui by DashNav.state.collectAsState()
    val nav by DashNav.session.state.collectAsState()
    var typing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(t.radius))) {
        DashMapView(Modifier.fillMaxSize(), compact = false)
        if (typing) {
            TypeSearch(onClose = { typing = false })
            return@Box
        }
        MapButtons(Modifier.align(Alignment.CenterEnd).padding(10.dp), navigating = nav.navigating)
        if (nav.navigating && ui.phase == DashNav.Phase.RESULTS) {
            Results(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxHeight(), ui.query, ui.results)
        } else if (nav.navigating && ui.phase == DashNav.Phase.SEARCHING) {
            Status(Modifier.align(Alignment.TopStart).padding(10.dp), "Looking for \"${ui.query}\" along the route…")
        } else if (nav.navigating) {
            DriveScreen()
        } else {
            when (ui.phase) {
                DashNav.Phase.IDLE, DashNav.Phase.ERROR -> {
                    WhereTo(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxHeight(), ui.error, onType = { typing = true })
                    ResumeCard(Modifier.align(Alignment.BottomEnd).padding(start = 340.dp, end = 74.dp, bottom = 10.dp))
                    SpeedBadge(Modifier.align(Alignment.BottomEnd).padding(end = 74.dp, bottom = 90.dp), onlyMoving = true)
                }
                DashNav.Phase.SEARCHING -> Status(Modifier.align(Alignment.TopStart).padding(10.dp), "Searching \"${ui.query}\"…")
                DashNav.Phase.RESULTS -> Results(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxHeight(), ui.query, ui.results)
                DashNav.Phase.ROUTING -> Status(Modifier.align(Alignment.TopStart).padding(10.dp), "Getting directions to ${ui.destination?.name.orEmpty()}…")
                DashNav.Phase.PREVIEW -> Preview(Modifier.align(Alignment.BottomStart).padding(10.dp))
                DashNav.Phase.NAVIGATING -> Unit
            }
        }
    }
}

/** Zoom, re-center/overview and north-up, on the right edge. */
@Composable
private fun MapButtons(modifier: Modifier, navigating: Boolean) {
    val t = LocalDashTheme.current
    val cam by DashNav.camera.collectAsState()
    val northUp by DashNav.northUp.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton("+") { DashMapHolder.zoomBy(1.0) }
        RoundButton("−") { DashMapHolder.zoomBy(-1.0) }
        RoundButton(if (northUp) "N" else "⬆", highlight = northUp) { DashNav.northUp.value = !northUp }
        val styleChoice by DashNav.mapStyle.collectAsState()
        RoundButton("🗺") {
            val order = listOf("auto", "liberty", "bright", "positron", "fiord", "dark", "satellite")
            DashNav.setMapStyle(order[(order.indexOf(styleChoice) + 1).mod(order.size)])
        }
        if (!navigating) {
            val traffic by DashNav.trafficLayer.collectAsState()
            RoundButton("🚦", highlight = traffic) { DashNav.setTrafficLayer(!traffic) }
        }
        if (cam != DashNav.Camera.FOLLOW) {
            RoundButton("◎", highlight = true) { DashNav.camera.value = DashNav.Camera.FOLLOW }
        } else if (navigating) {
            RoundButton("⤢") { DashNav.camera.value = DashNav.Camera.OVERVIEW }
        }
    }
}

@Composable
private fun RoundButton(label: String, highlight: Boolean = false, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Tap(onTap = onTap, modifier = Modifier.size(52.dp), shape = CircleShape, color = if (highlight) t.accent else t.panel.copy(alpha = 0.92f)) {
        Label(label, 20.sp, if (highlight) t.bgBottom else t.text, FontWeight.Bold)
    }
}

/** The browse panel: search box, voice, category buttons, saved and recent places. */
@Composable
private fun WhereTo(modifier: Modifier, error: String, onType: () -> Unit) {
    val t = LocalDashTheme.current
    val saved by DashPlaces.saved.collectAsState()
    val recents by DashPlaces.recents.collectAsState()
    val trips by DashPlaces.trips.collectAsState()
    Card(modifier.width(320.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tap(onTap = onType, modifier = Modifier.weight(1f).height(52.dp), color = t.panelHi, contentAlignment = Alignment.CenterStart) {
                    Label("Search places or addresses", 15.sp, t.textDim, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Tap(onTap = ::askWhereTo, modifier = Modifier.size(52.dp), shape = CircleShape, color = t.accent) {
                    GlyphIcon(Glyph.Mic, t.bgBottom, 24.dp)
                }
            }
            CategoryRow()
            if (error.isNotEmpty()) Label(error, 14.sp, Color(0xFFFF6B6B), maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp))
            val home = saved.firstOrNull { it.kind == "home" }
            val work = saved.firstOrNull { it.kind == "work" }
            val favs = saved.filter { it.kind == "fav" }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SpotRow("⌂", "Home", home?.address ?: "Not set: save Home from a place's Go card", enabled = home != null) { home?.let { DashNav.preview(it.place()) } } }
                item { SpotRow("▣", "Work", work?.address ?: "Not set: save Work from a place's Go card", enabled = work != null) { work?.let { DashNav.preview(it.place()) } } }
                items(favs.size) { i -> SpotRow("★", favs[i].name, favs[i].address) { DashNav.preview(favs[i].place()) } }
                if (trips.isNotEmpty()) item { Label("Trips", 13.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
                items(trips.size) { i -> SpotRow("⇢", trips[i].name, "${trips[i].stops.size} stop(s)") { DashNav.startTrip(trips[i]) } }
                if (recents.isNotEmpty()) item { Label("Recent", 13.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
                items(recents.size) { i -> SpotRow("↺", recents[i].name, recents[i].address) { DashNav.preview(recents[i].place()) } }
            }
        }
    }
}

@Composable
private fun SpotRow(icon: String, title: String, sub: String, enabled: Boolean = true, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Tap(onTap = { if (enabled) onTap() }, onTapUp = true, modifier = Modifier.fillMaxWidth().height(52.dp), color = t.panelHi, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(icon, 18.sp, t.accent, FontWeight.Bold, modifier = Modifier.width(26.dp))
            Column {
                Label(title, 15.sp, if (enabled) t.text else t.textDim, FontWeight.SemiBold)
                if (sub.isNotBlank()) Label(sub, 12.sp, t.textDim)
            }
        }
    }
}

/** Quick searches, like Vela's category shortcuts. */
@Composable
private fun CategoryRow() {
    val t = LocalDashTheme.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, query) in CATEGORIES) {
            Tap(onTap = { DashNav.search(query) }, onTapUp = true, modifier = Modifier.height(38.dp), shape = RoundedCornerShape(19.dp), color = t.panelHi) {
                Label(label, 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

private val CATEGORIES = listOf(
    "⛽ Gas" to "gas station",
    "🍔 Food" to "restaurants",
    "☕ Coffee" to "coffee",
    "🅿 Parking" to "parking",
    "⚡ EV" to "ev charging station",
    "🛒 Grocery" to "grocery store",
)

private fun askWhereTo() {
    CarAssistant.say("Where to?") {
        CarAssistant.dictate(
            onPartial = {},
            onResult = { heard -> if (heard.isNotBlank()) DashNav.search(heard) },
            onError = { GearslipLog.w("nav: where-to dictation: $it") },
        )
    }
}

/** Typing on the car screen: the box, live suggestions, and the keyboard. */
@Composable
private fun TypeSearch(onClose: () -> Unit) {
    val t = LocalDashTheme.current
    var text by remember { mutableStateOf("") }
    val sug by DashNav.suggestions.collectAsState()
    Column(Modifier.fillMaxSize().background(t.bgBottom.copy(alpha = 0.94f))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(t.panelHi).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Label(text.ifEmpty { "Search places or addresses" } + if (text.isNotEmpty()) "▏" else "", 17.sp, if (text.isEmpty()) t.textDim else t.text) }
            Tap(onTap = onClose, modifier = Modifier.size(80.dp, 46.dp), color = t.panel) { Label("Close", 15.sp) }
        }
        // Suggestions: places to tap, or completions that fill the box.
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sug.queries.size) { i ->
                val q = sug.queries[i]
                Tap(onTap = { text = q; DashNav.suggest(q) }, onTapUp = true, modifier = Modifier.fillMaxWidth().height(36.dp), color = Color.Transparent, outline = false, contentAlignment = Alignment.CenterStart) {
                    Label("🔍  $q", 14.sp, t.textDim, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            items(sug.places.size) { i ->
                val p = sug.places[i]
                Tap(
                    onTap = { onClose(); DashNav.preview(p) },
                    onTapUp = true,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = t.panel,
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(p.name, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Label(p.address.orEmpty(), 12.sp, t.textDim, modifier = Modifier.weight(1.2f))
                    }
                }
            }
        }
        CarKeyboard(
            text = text,
            onTextChange = { text = it; DashNav.suggest(it) },
            onSubmit = { if (text.isNotBlank()) { onClose(); DashNav.search(text) } },
            onDismiss = onClose,
        )
    }
}

@Composable
private fun Status(modifier: Modifier, text: String) {
    Card(modifier.width(320.dp)) { Label(text, 16.sp, maxLines = 2, modifier = Modifier.padding(14.dp)) }
}

private enum class Sort(val label: String) { BEST("Best"), NEAREST("Nearest"), RATING("Top rated") }

@Composable
private fun Results(modifier: Modifier, query: String, places: List<Place>) {
    val t = LocalDashTheme.current
    val fix by DashNav.fix.collectAsState()
    val ui by DashNav.state.collectAsState()
    var openNow by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Sort.BEST) }
    val shown = remember(places, openNow, sort, fix) {
        places.filter { !openNow || it.openNow == true }.let { list ->
            when (sort) {
                Sort.BEST -> list
                Sort.NEAREST -> fix?.let { f -> list.sortedBy { metersBetween(f.at, it.location) } } ?: list
                Sort.RATING -> list.sortedByDescending { it.rating ?: 0.0 }
            }
        }
    }
    Card(modifier.width(360.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(if (ui.alongRoute) "\"$query\" along the route" else "\"$query\"", 15.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(6.dp).weight(1f))
                Tap(onTap = DashNav::closeResults, modifier = Modifier.size(70.dp, 34.dp), color = t.panelHi) { Label("Back", 13.sp) }
            }
            val cam by DashNav.camera.collectAsState()
            if (cam == DashNav.Camera.FREE && !ui.alongRoute) {
                Tap(onTap = { DashMapHolder.center()?.let { DashNav.searchHere(it) } }, modifier = Modifier.fillMaxWidth().height(40.dp), color = t.accent) {
                    Label("Search this area", 14.sp, t.bgBottom, FontWeight.SemiBold)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Open now", openNow) { openNow = !openNow }
                if (!ui.alongRoute) Sort.entries.forEach { s -> Chip(s.label, sort == s) { sort = s } }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown.size) { i ->
                    val p = shown[i]
                    Tap(
                        onTap = { if (ui.alongRoute) DashNav.addStop(p) else DashNav.preview(p) },
                        onTapUp = true,
                        modifier = Modifier.fillMaxWidth().height(66.dp),
                        color = t.panelHi,
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label(p.name, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                val extra = p.detourSeconds?.let { if (it < 30) "On the way" else "+${minutes(it.toDouble())}" }
                                    ?: fix?.let { f -> miles(metersBetween(f.at, p.location)) }
                                extra?.let { Label(it, 13.sp, if (p.detourSeconds != null) Color(0xFF34C759) else t.textDim, FontWeight.SemiBold) }
                            }
                            Label(listOfNotNull(p.fuelPrice, p.rating?.let { "★ %.1f".format(it) }, p.statusText ?: p.category, p.address).joinToString(" · "), 12.sp, t.textDim)
                            if (ui.alongRoute) Label("Tap to add as a stop", 11.sp, t.accent)
                        }
                    }
                }
                if (!ui.alongRoute) item {
                    Tap(onTap = DashNav::moreResults, onTapUp = true, modifier = Modifier.fillMaxWidth().height(44.dp), color = t.panel) {
                        Label("More results", 14.sp, t.accent, FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onTap: () -> Unit) {
    val t = LocalDashTheme.current
    Tap(onTap = onTap, onTapUp = true, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(17.dp), color = if (on) t.accent else t.panelHi) {
        Label(label, 13.sp, if (on) t.bgBottom else t.text, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** Gas, food... searched along the route (the drive's + Stop, or a preview's add-stop). */
@Composable
private fun AlongRouteChips(modifier: Modifier, onDone: () -> Unit) {
    val t = LocalDashTheme.current
    Card(modifier) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Add a stop along the route", 14.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, query) in CATEGORIES) Chip(label, false) { onDone(); DashNav.searchAlongRoute(query) }
                Chip("Close", false) { onDone() }
            }
        }
    }
}

/** Route choices (up to four, with traffic), save the place, and Go. */
@Composable
private fun Preview(modifier: Modifier) {
    val t = LocalDashTheme.current
    val ui by DashNav.state.collectAsState()
    val dest = ui.destination ?: return
    var saving by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    if (steps) {
        StepList(Modifier.fillMaxHeight(), ui.route?.maneuvers.orEmpty(), from = 0) { steps = false }
        return
    }
    Column(modifier.fillMaxWidth(0.88f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The place: open now, today's hours, gas price, phone.
        val d = ui.details
        if (d != null && (d.statusText != null || d.phone != null || d.fuelPrice != null)) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        d.statusText?.let { Label(it, 14.sp, if (d.openNow == false) Color(0xFFFF6B6B) else Color(0xFF34C759), FontWeight.SemiBold) }
                        ui.route?.let { r ->
                            DashNav.closingWarning(d.statusText, System.currentTimeMillis() + ((r.durationInTrafficSeconds ?: r.durationSeconds) * 1000).toLong())
                                ?.let { Label("⚠ $it", 13.sp, Color(0xFFFFB300), FontWeight.SemiBold) }
                        }
                        Label(listOfNotNull(d.fuelPrice, d.rating?.let { "★ %.1f (${d.reviewCount ?: 0})".format(it) }, d.category).joinToString(" · "), 13.sp, t.textDim)
                    }
                    d.phone?.let { phone ->
                        Tap(onTap = { app.seb3thehacker.gearslip.car.placeCall(context, phone) }, modifier = Modifier.size(90.dp, 44.dp), color = Color(0xFF1E9E4A)) {
                            Label("Call", 15.sp, Color.White, FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (ui.stops.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Stops:", 13.sp, t.textDim)
                    ui.stops.forEachIndexed { i, stop ->
                        if (i > 0) Chip("◀", false) { DashNav.moveStopEarlier(stop) }
                        Chip("${i + 1}. ${stop.name}  ✕", true) { DashNav.removeStop(stop) }
                    }
                }
            }
        }
        if (addingStop) AlongRouteChips(Modifier.fillMaxWidth()) { addingStop = false }
        if (options) {
            val tolls by DashNav.avoidTolls.collectAsState()
            val highways by DashNav.avoidHighways.collectAsState()
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Avoid tolls", tolls) { DashNav.setAvoidTolls(!tolls) }
                    Chip("Avoid highways", highways) { DashNav.setAvoidHighways(!highways) }
                    Chip("Steps", false) { steps = true }
                    Chip("↺ Round trip", false) { DashNav.roundTrip() }
                    Chip("💾 Save trip", false) { DashNav.saveTrip(); options = false }
                    Chip("Other locations", false) { DashNav.otherLocations(dest) }
                    if (ui.stops.size >= 2) Chip("⇅ Reverse stops", false) { DashNav.reverseStops() }
                    Chip("Close", false) { options = false }
                }
            }
        }
        if (ui.routes.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.routes.forEachIndexed { i, r ->
                    Tap(
                        onTap = { DashNav.choose(i) },
                        modifier = Modifier.height(56.dp).width(130.dp),
                        color = if (i == ui.chosen) t.accent else t.panel,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Label(minutes(r.durationInTrafficSeconds ?: r.durationSeconds), 15.sp, if (i == ui.chosen) t.bgBottom else t.text, FontWeight.Bold)
                            Label(r.summary ?: miles(r.distanceMeters), 11.sp, if (i == ui.chosen) t.bgBottom else t.textDim)
                        }
                    }
                }
            }
        }
        if (saving) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Save as", 15.sp, modifier = Modifier.weight(1f))
                    Tap(onTap = { DashPlaces.save(dest, "Home", "home"); saving = false }, modifier = Modifier.size(80.dp, 44.dp), color = t.panelHi) { Label("Home", 14.sp) }
                    Tap(onTap = { DashPlaces.save(dest, "Work", "work"); saving = false }, modifier = Modifier.size(80.dp, 44.dp), color = t.panelHi) { Label("Work", 14.sp) }
                    Tap(onTap = { nameAndSave(dest); saving = false }, modifier = Modifier.size(110.dp, 44.dp), color = t.panelHi) { Label("Name it 🎤", 14.sp) }
                    Tap(onTap = { DashPlaces.save(dest, dest.name, "fav"); saving = false }, modifier = Modifier.size(90.dp, 44.dp), color = t.panelHi) { Label("★ Save", 14.sp) }
                }
            }
        }
        val r = ui.route ?: return@Column
        Card(Modifier.fillMaxWidth().height(84.dp)) {
            Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(dest.name, 17.sp, weight = FontWeight.SemiBold)
                    Label("${minutes(r.durationInTrafficSeconds ?: r.durationSeconds)} · ${miles(r.distanceMeters)} · arrive ${arrival(r.durationInTrafficSeconds ?: r.durationSeconds)}", 14.sp, t.accent)
                }
                Tap(onTap = { saving = !saving }, modifier = Modifier.size(48.dp, 52.dp), color = t.panelHi) { Label("★", 20.sp, t.accent) }
                Spacer(Modifier.width(6.dp))
                Tap(onTap = { addingStop = !addingStop }, modifier = Modifier.size(48.dp, 52.dp), color = t.panelHi) { Label("+", 22.sp, t.accent, FontWeight.Bold) }
                Spacer(Modifier.width(6.dp))
                Tap(onTap = { options = !options }, modifier = Modifier.size(48.dp, 52.dp), color = t.panelHi) { Label("⚙", 18.sp, t.accent) }
                Spacer(Modifier.width(8.dp))
                Tap(onTap = DashNav::clear, modifier = Modifier.size(74.dp, 52.dp), color = t.panelHi) { Label("Cancel", 14.sp) }
                Spacer(Modifier.width(8.dp))
                Tap(onTap = DashNav::go, modifier = Modifier.size(100.dp, 52.dp), color = Color(0xFF1E9E4A)) {
                    Label("Go", 18.sp, Color.White, FontWeight.Bold)
                }
            }
        }
    }
}

/** "Mom's house": the name said aloud. */
private fun nameAndSave(p: Place) {
    CarAssistant.say("What should I call it?") {
        CarAssistant.dictate(
            onPartial = {},
            onResult = { name ->
                if (name.isNotBlank()) {
                    DashPlaces.save(p, name.replaceFirstChar { it.uppercase() }, "fav")
                    CarAssistant.say("Saved as $name.") {}
                }
            },
            onError = { GearslipLog.w("nav: name dictation: $it") },
        )
    }
}

// --- driving ----------------------------------------------------------------------------------

/** Everything on screen during a drive. */
@Composable
private fun DriveScreen() {
    val nav by DashNav.session.state.collectAsState()
    val camera by DashNav.cameraAhead.collectAsState()
    var steps by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        when {
            nav.arrived -> ArrivedCard(Modifier.align(Alignment.Center))
            steps -> StepList(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxHeight(0.82f), nav.route?.maneuvers.orEmpty(), from = nav.nav.stepIndex + 1) { steps = false }
            else -> {
                Tap(onTap = { steps = true }, onTapUp = true, modifier = Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.Transparent, outline = false) {
                    TurnCard(Modifier)
                }
                when {
                    nav.paused -> Banner(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), "⏸ Drive paused")
                    nav.nav.offRoute -> Banner(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), "Rerouting…")
                    camera -> Banner(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), "📷 Speed camera ahead")
                }
                nav.fasterRoute?.let { FasterRoute(Modifier.align(Alignment.TopEnd).padding(10.dp), nav.fasterSavingSeconds) }
            }
        }
        if (addingStop) AlongRouteChips(Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 80.dp).fillMaxWidth()) { addingStop = false }
        SpeedBadge(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 92.dp))
        RoadAhead(Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 78.dp).fillMaxWidth().height(10.dp))
        TripBar(Modifier.align(Alignment.BottomCenter).padding(10.dp), onAddStop = { addingStop = !addingStop })
    }
}

/**
 * The rest of the route as one strip, like Vela's road-ahead bar: traffic (orange, red), speed
 * cameras (white ticks) and stops (blue ticks), with you at the left edge.
 */
@Composable
private fun RoadAhead(modifier: Modifier) {
    val nav by DashNav.session.state.collectAsState()
    val cams by DashNav.cameras.collectAsState()
    val route = nav.route ?: return
    val line = route.polyline
    if (line.size < 2) return
    val traveled = nav.nav.traveledM
    val remaining = (route.distanceMeters - traveled).coerceAtLeast(1.0)
    // Where along the route each camera sits (meters from the start).
    val camAt = remember(route, cams) {
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + metersBetween(line[i - 1], line[i])
        cams.mapNotNull { c -> line.indices.minByOrNull { metersBetween(line[it], c) }?.let { cum[it] } }
    }
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(5.dp))) {
        drawRect(Color(0xFF3D8BFF))
        fun x(m: Double) = (((m - traveled) / remaining).coerceIn(0.0, 1.0) * size.width).toFloat()
        for (span in route.trafficSpans) {
            if (span.level < 1) continue
            val a = x(span.startMeters)
            val b = x(span.startMeters + span.lengthMeters)
            if (b <= 0f) continue
            drawRect(
                when (span.level) { 1 -> Color(0xFFF5A623); 2 -> Color(0xFFE53935); else -> Color(0xFF8B1A1A) },
                topLeft = androidx.compose.ui.geometry.Offset(a, 0f),
                size = androidx.compose.ui.geometry.Size((b - a).coerceAtLeast(2f), size.height),
            )
        }
        camAt.filter { it > traveled }.forEach { m ->
            drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(x(m) - 1.5f, 0f), size = androidx.compose.ui.geometry.Size(3f, size.height))
        }
    }
}

/** The turns of the route from [from] on: road, distance, and the maneuver. */
@Composable
private fun StepList(modifier: Modifier, steps: List<app.vela.core.model.Maneuver>, from: Int, onClose: () -> Unit) {
    val t = LocalDashTheme.current
    Card(modifier.width(380.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Turns", 15.sp, t.accent, FontWeight.SemiBold, modifier = Modifier.padding(6.dp).weight(1f))
                Tap(onTap = onClose, modifier = Modifier.size(70.dp, 34.dp), color = t.panelHi) { Label("Close", 13.sp) }
            }
            val rest = steps.drop(from.coerceAtLeast(0))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rest.size) { i ->
                    val m = rest[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(t.panelHi).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(arrow(m.type), 22.sp, t.accent, FontWeight.Bold, modifier = Modifier.width(34.dp))
                        Column(Modifier.weight(1f)) {
                            Label(m.instruction, 14.sp, maxLines = 2)
                            m.ref?.let { Label(it, 11.sp, t.textDim) }
                        }
                        Label(miles(m.distanceMeters), 12.sp, t.textDim)
                    }
                }
            }
        }
    }
}

/** The next turn, its lanes and shield, and the one after if it comes right after. */
@Composable
private fun TurnCard(modifier: Modifier) {
    val nav by DashNav.session.state.collectAsState()
    val steps = nav.route?.maneuvers.orEmpty()
    val step = steps.getOrNull(nav.nav.stepIndex + 1) ?: steps.getOrNull(nav.nav.stepIndex)
    val then = steps.getOrNull(nav.nav.stepIndex + 2)
    Column(modifier.width(340.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F5132))) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(arrow(step?.type), 40.sp, Color.White, FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(miles(nav.nav.distanceToNextManeuver), 24.sp, Color.White, FontWeight.Bold)
                            step?.ref?.let { Spacer(Modifier.width(8.dp)); Shield(it) }
                        }
                        Label(step?.road ?: nav.maneuverText.ifBlank { step?.instruction.orEmpty() }, 15.sp, Color.White, maxLines = 2)
                    }
                }
                val lanes = step?.lanes.orEmpty()
                if (lanes.size >= 2) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        lanes.forEach { lane ->
                            Box(
                                Modifier.size(30.dp, 30.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (lane.valid) Color.White else Color.White.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) { Label(laneArrow(lane.indications), 16.sp, if (lane.valid) Color(0xFF0F5132) else Color.White.copy(alpha = 0.6f), FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        // "Then" when the turn after comes within ~a block.
        if (then != null && step != null && step.distanceMeters < 300) {
            Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B3D26))) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Then", 13.sp, Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(6.dp))
                    Label(arrow(then.type), 18.sp, Color.White, FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Shield(ref: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF1C4FB8)).border(1.5.dp, Color.White, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Label(ref, 13.sp, Color.White, FontWeight.Bold) }
}

@Composable
private fun Banner(modifier: Modifier, text: String) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFE65100)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Label(text, 15.sp, Color.White, FontWeight.SemiBold)
    }
}

@Composable
private fun FasterRoute(modifier: Modifier, saving: Double) {
    val t = LocalDashTheme.current
    Card(modifier.width(240.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Faster route: saves ${minutes(saving)}", 15.sp, Color(0xFF34C759), FontWeight.SemiBold, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tap(onTap = DashNav::dismissFaster, modifier = Modifier.weight(1f).height(42.dp), color = t.panelHi) { Label("No", 14.sp) }
                Tap(onTap = DashNav::acceptFaster, modifier = Modifier.weight(1f).height(42.dp), color = Color(0xFF1E9E4A)) { Label("Take it", 14.sp, Color.White, FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SpeedBadge(modifier: Modifier, onlyMoving: Boolean = false) {
    val fix by DashNav.fix.collectAsState()
    val limit by DashNav.speedLimitMph.collectAsState()
    val speeding by DashNav.speeding.collectAsState()
    val mph = ((fix?.speedMps ?: 0f) * 2.23694f).roundToInt()
    if (onlyMoving && mph < 5) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(if (speeding) Color(0xFFE53935) else Color.White)
                .border(3.dp, Color(0xFF222222), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label("$mph", 22.sp, if (speeding) Color.White else Color.Black, FontWeight.Bold)
                Label("mph", 10.sp, if (speeding) Color.White else Color(0xFF444444))
            }
        }
        // The US speed limit sign, from the installed offline region.
        limit?.let { l ->
            Box(
                Modifier.size(48.dp, 62.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                    .border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("SPEED", 8.sp, Color.Black, FontWeight.Bold)
                    Label("LIMIT", 8.sp, Color.Black, FontWeight.Bold)
                    Label("$l", 20.sp, Color.Black, FontWeight.Bold)
                }
            }
        }
    }
}

/** "Resume drive to …?" after the app or phone restarted mid-drive. */
@Composable
private fun ResumeCard(modifier: Modifier) {
    val t = LocalDashTheme.current
    val pending by DashNav.pendingResume.collectAsState()
    val p = pending ?: return
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Label("Resume your drive?", 13.sp, t.accent, FontWeight.SemiBold)
                Label(p.name, 15.sp, weight = FontWeight.SemiBold)
            }
            Tap(onTap = DashNav::dismissResume, modifier = Modifier.size(70.dp, 44.dp), color = t.panelHi) { Label("No", 14.sp) }
            Tap(onTap = DashNav::resumeDrive, modifier = Modifier.size(90.dp, 44.dp), color = Color(0xFF1E9E4A)) { Label("Resume", 14.sp, Color.White, FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun TripBar(modifier: Modifier, onAddStop: () -> Unit) {
    val t = LocalDashTheme.current
    val nav by DashNav.session.state.collectAsState()
    val voiceOn by DashNav.voiceOn.collectAsState()
    val detail by DashNav.voiceDetail.collectAsState()
    Card(modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(minutes(nav.remainingDuration), 20.sp, Color(0xFF34C759), FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label("${miles(nav.remainingDistance)} · arrive ${arrival(nav.remainingDuration)}", 15.sp, t.textDim)
                if (nav.destinationLabel.isNotBlank()) Label(nav.destinationLabel, 12.sp, t.textDim)
            }
            Tap(onTap = { DashNav.setPaused(!nav.paused) }, modifier = Modifier.size(48.dp, 46.dp), color = if (nav.paused) t.accent else t.panelHi) {
                Label(if (nav.paused) "▶" else "⏸", 16.sp, if (nav.paused) t.bgBottom else t.text)
            }
            Spacer(Modifier.width(6.dp))
            Tap(onTap = onAddStop, modifier = Modifier.size(66.dp, 46.dp), color = t.panelHi) { Label("+ Stop", 13.sp) }
            Spacer(Modifier.width(6.dp))
            // How much the voice says: everything, brief, or highway exits only.
            Tap(
                onTap = {
                    val modes = app.vela.core.nav.SpokenDetail.Mode.entries
                    DashNav.setVoiceDetail(modes[(detail.ordinal + 1) % modes.size])
                },
                modifier = Modifier.size(66.dp, 46.dp),
                color = t.panelHi,
            ) {
                Label(when (detail) { app.vela.core.nav.SpokenDetail.Mode.FULL -> "Full"; app.vela.core.nav.SpokenDetail.Mode.BRIEF -> "Brief"; else -> "Exits" }, 13.sp)
            }
            Spacer(Modifier.width(6.dp))
            Tap(onTap = { DashNav.voiceOn.value = !voiceOn }, modifier = Modifier.size(52.dp, 46.dp), color = if (voiceOn) t.panelHi else Color(0xFF8B1A1A)) {
                Label(if (voiceOn) "🔊" else "🔇", 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Tap(onTap = DashNav::stop, modifier = Modifier.size(80.dp, 46.dp), color = Color(0xFFC62828)) {
                Label("End", 16.sp, Color.White, FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ArrivedCard(modifier: Modifier) {
    val t = LocalDashTheme.current
    val nav by DashNav.session.state.collectAsState()
    Card(modifier.width(360.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("⚑ You've arrived", 22.sp, weight = FontWeight.Bold)
            Label(nav.destinationLabel, 16.sp, t.textDim, maxLines = 2)
            Label("${miles(nav.tripDistanceMeters)} in ${minutes(nav.tripElapsedSeconds)}", 14.sp, t.textDim)
            Tap(onTap = DashNav::stop, modifier = Modifier.fillMaxWidth().height(48.dp), color = t.accent) {
                Label("Done", 17.sp, t.bgBottom, FontWeight.SemiBold)
            }
        }
    }
}

private fun arrow(type: ManeuverType?) = when (type) {
    ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT -> "⬅"
    ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT -> "➡"
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> "↖"
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.MERGE -> "↗"
    ManeuverType.UTURN -> "↩"
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> "⟳"
    ManeuverType.ARRIVE -> "⚑"
    else -> "⬆"
}

private fun laneArrow(ind: List<String>): String {
    val i = ind.joinToString(" ")
    return when {
        "uturn" in i -> "↩"
        "sharp left" in i || "left" in i && "slight" !in i -> if ("straight" in i) "⬉" else "⬅"
        "slight left" in i -> "↖"
        "sharp right" in i || "right" in i && "slight" !in i -> if ("straight" in i) "⬈" else "➡"
        "slight right" in i -> "↗"
        else -> "⬆"
    }
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
