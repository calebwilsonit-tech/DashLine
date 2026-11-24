package app.seb3thehacker.gearslip.dash

import android.content.Context
import androidx.compose.runtime.Immutable
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Offline map regions: Vela's routing files (OsmAnd .obf, one per state or country) from Vela's
 * public `obf-regions` release. Installed the way Vela's ObfStore does it (`filesDir/obf/<id>.obf`
 * plus `index.json` with each region's box), which is where Vela's route engine looks. With a
 * region installed the drive can route offline and the posted speed limit shows.
 */
object DashOffline {

    @Immutable
    data class Region(val id: String, val name: String, val url: String, val sizeMb: Int, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(lat: Double, lng: Double) = lat in s..n && lng in w..e
    }

    @Immutable
    data class State(
        val catalog: List<Region> = emptyList(),
        val installed: Set<String> = emptySet(),
        /** id → 0..100 while downloading. */
        val downloading: Map<String, Int> = emptyMap(),
        val error: String = "",
        /** Map-tile files (Vela's basemap PMTiles), by region id: catalog, installed, downloading. */
        val tiles: Map<String, Region> = emptyMap(),
        val tilesInstalled: Set<String> = emptySet(),
        val tilesDownloading: Map<String, Int> = emptyMap(),
        /** Offline place packs (Vela's SQLite POI + address databases), by region id. */
        val places: Map<String, Region> = emptyMap(),
        val placesInstalled: Set<String> = emptySet(),
        val placesDownloading: Map<String, Int> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private var root: File? = null

    // Region files are tens to hundreds of MB: no overall call timeout.
    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (root != null) return
        val dir = File(context.applicationContext.filesDir, "obf")
        root = dir
        app.vela.core.data.OfflineRoot.dir = context.applicationContext.filesDir
        tilesRoot = File(context.applicationContext.filesDir, "basemap")
        placesRoot = File(context.applicationContext.filesDir, "poi-packs")
        reloadPlaces()
        _state.value = _state.value.copy(installed = installedIds(), tilesInstalled = tilesIds(), placesInstalled = placesIds())
    }

    private var tilesRoot: File? = null
    private var placesRoot: File? = null
    private fun tilesIds(): Set<String> = tilesRoot?.listFiles { f -> f.name.endsWith(".pmtiles") && f.length() > 0 }
        ?.map { it.name.removeSuffix(".pmtiles") }?.toSet().orEmpty()
    private fun placesIds(): Set<String> = placesRoot?.listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }
        ?.map { it.name.removeSuffix(".db") }?.toSet().orEmpty()
    private fun reloadPlaces() {
        val paths = placesRoot?.listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }?.map { it.absolutePath }.orEmpty()
        app.vela.core.data.OfflinePacks.reload(paths)
    }

    /** The installed map-tile file covering a point (smallest region first), or null. */
    fun tilesFor(lat: Double, lng: Double): File? {
        val dir = tilesRoot ?: return null
        val st = _state.value
        return st.tilesInstalled.mapNotNull { id -> st.tiles[id]?.let { it to File(dir, "$id.pmtiles") } ?: boxes[id]?.let { it to File(dir, "$id.pmtiles") } }
            .filter { (r, _) -> r.covers(lat, lng) }
            .minByOrNull { (r, _) -> (r.n - r.s) * (r.e - r.w) }?.second
    }

    /** Boxes of installed tile files, kept so [tilesFor] works before the catalog loads (offline). */
    private val boxes: Map<String, Region> by lazy {
        val f = tilesRoot?.let { File(it, "boxes.json") }
        runCatching {
            val o = JSONObject(f!!.readText())
            o.keys().asSequence().associateWith { id ->
                val b = o.getJSONArray(id)
                Region(id, id, "", 0, b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadTiles(context: Context) {
        scope.launch {
            val cache = File(context.cacheDir, "basemap-manifest.json")
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(app.vela.core.config.VelaLinks.basemapManifest()).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body!!.string().also { cache.writeText(it) }
                    }
                }.getOrNull() ?: cache.takeIf { it.exists() }?.readText()
            } ?: return@launch
            val list = runCatching {
                val arr = JSONObject(json).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox")
                    Region(o.getString("id"), o.getString("name"), o.getString("url"), o.optDouble("sizeMb").toInt(), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(tiles = list.associateBy { it.id })
        }
    }

    fun downloadTiles(region: Region) {
        val dir = tilesRoot ?: return
        val key = "tiles:${region.id}"
        if (key in jobs) return
        _state.value = _state.value.copy(tilesDownloading = _state.value.tilesDownloading + (region.id to 0), error = "")
        jobs[key] = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                fetch(region.url, File(dir, "${region.id}.pmtiles")) { pct ->
                    _state.value = _state.value.copy(tilesDownloading = _state.value.tilesDownloading + (region.id to pct))
                }.also { done ->
                    if (done) runCatching {
                        val f = File(dir, "boxes.json")
                        val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
                        o.put(region.id, JSONArray().put(region.s).put(region.w).put(region.n).put(region.e))
                        f.writeText(o.toString())
                    }
                }
            }
            jobs.remove(key)
            _state.value = _state.value.copy(
                tilesDownloading = _state.value.tilesDownloading - region.id,
                tilesInstalled = tilesIds(),
                error = if (ok) "" else "${region.name} map tiles didn't download. Try again on Wi-Fi.",
            )
        }
    }

    fun deleteTiles(region: Region) {
        File(tilesRoot ?: return, "${region.id}.pmtiles").delete()
        _state.value = _state.value.copy(tilesInstalled = tilesIds())
    }

    private fun loadPlaces(context: Context) {
        scope.launch {
            val cache = File(context.cacheDir, "poi-pack-manifest.json")
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(app.vela.core.config.VelaLinks.poiPackManifest()).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body!!.string().also { cache.writeText(it) }
                    }
                }.getOrNull() ?: cache.takeIf { it.exists() }?.readText()
            } ?: return@launch
            val list = runCatching {
                val arr = JSONObject(json).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox")
                    Region(o.getString("id"), o.getString("name"), o.getString("url"), o.optDouble("sizeMb").toInt(), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(places = list.associateBy { it.id })
        }
    }

    fun downloadPlaces(region: Region) {
        val dir = placesRoot ?: return
        val key = "places:${region.id}"
        if (key in jobs) return
        _state.value = _state.value.copy(placesDownloading = _state.value.placesDownloading + (region.id to 0), error = "")
        jobs[key] = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                fetch(region.url, File(dir, "${region.id}.db")) { pct ->
                    _state.value = _state.value.copy(placesDownloading = _state.value.placesDownloading + (region.id to pct))
                }.also { done -> if (done) reloadPlaces() }
            }
            jobs.remove(key)
            _state.value = _state.value.copy(
                placesDownloading = _state.value.placesDownloading - region.id,
                placesInstalled = placesIds(),
                error = if (ok) "" else "${region.name} place pack didn't download. Try again on Wi-Fi.",
            )
        }
    }

    fun deletePlaces(region: Region) {
        File(placesRoot ?: return, "${region.id}.db").delete()
        reloadPlaces()
        _state.value = _state.value.copy(placesInstalled = placesIds())
    }

    /** Streams [url] into [dest] through a temp file; true when it landed. */
    private suspend fun fetch(url: String, dest: File, progress: (Int) -> Unit): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp").apply { delete() }
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val total = resp.body!!.contentLength()
            resp.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (100 * read / total).toInt()
                            if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                        }
                    }
                }
            }
        }
        check(tmp.length() > 1024) { "download is implausibly small" }
        dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        true
    }.onFailure { GearslipLog.w("offline: ${dest.name} download failed: ${it.message}") }.getOrDefault(false)

    /** Loads Vela's region list (cached on disk for offline use). */
    fun loadCatalog(context: Context) {
        init(context)
        if (_state.value.catalog.isNotEmpty()) return
        loadTiles(context)
        loadPlaces(context)
        scope.launch {
            val cache = File(context.cacheDir, "obf-manifest.json")
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(app.vela.core.config.VelaLinks.obfManifest()).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body!!.string().also { cache.writeText(it) }
                    }
                }.getOrNull() ?: cache.takeIf { it.exists() }?.readText()
            }
            if (json == null) {
                _state.value = _state.value.copy(error = "Couldn't load the region list. Check the internet.")
                return@launch
            }
            val regions = runCatching {
                val arr = JSONObject(json).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox")
                    Region(o.getString("id"), o.getString("name"), o.getString("url"), o.optInt("sizeMb"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }.sortedBy { it.name }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(catalog = regions, error = "")
        }
    }

    fun download(region: Region) {
        val dir = root ?: return
        if (region.id in jobs) return
        _state.value = _state.value.copy(downloading = _state.value.downloading + (region.id to 0), error = "")
        jobs[region.id] = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, "${region.id}.obf.tmp").apply { delete() }
                    http.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val total = resp.body!!.contentLength()
                        resp.body!!.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                var read = 0L
                                var last = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val pct = (100 * read / total).toInt()
                                        if (pct != last) {
                                            last = pct
                                            withContext(Dispatchers.Main) {
                                                _state.value = _state.value.copy(downloading = _state.value.downloading + (region.id to pct))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    check(tmp.length() > 1024) { "download is implausibly small" }
                    val dest = File(dir, "${region.id}.obf")
                    dest.delete()
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                    writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e)))
                    true
                }.onFailure { GearslipLog.w("offline: ${region.id} download failed: ${it.message}") }.getOrDefault(false)
            }
            jobs.remove(region.id)
            _state.value = _state.value.copy(
                downloading = _state.value.downloading - region.id,
                installed = installedIds(),
                error = if (ok) "" else "${region.name} didn't download. Try again on Wi-Fi.",
            )
        }
    }

    fun delete(region: Region) {
        val dir = root ?: return
        File(dir, "${region.id}.obf").delete()
        writeIndex(readIndex() - region.id)
        _state.value = _state.value.copy(installed = installedIds())
    }

    private fun installedIds(): Set<String> {
        val dir = root ?: return emptySet()
        return readIndex().keys.filter { File(dir, "$it.obf").let { f -> f.exists() && f.length() > 0 } }.toSet()
    }

    private fun readIndex(): Map<String, DoubleArray> = runCatching {
        val f = File(root ?: return emptyMap(), "index.json")
        if (!f.exists()) return emptyMap()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            o.getString("id") to doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun writeIndex(entries: Map<String, DoubleArray>) {
        val dir = root ?: return
        dir.mkdirs()
        val arr = JSONArray()
        entries.forEach { (id, b) -> arr.put(JSONObject().put("id", id).put("bbox", JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3]))) }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(File(dir, "index.json"))) { File(dir, "index.json").writeText(arr.toString()); tmp.delete() }
    }
}
