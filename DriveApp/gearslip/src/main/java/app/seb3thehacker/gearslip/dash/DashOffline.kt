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
        _state.value = _state.value.copy(installed = installedIds())
    }

    /** Loads Vela's region list (cached on disk for offline use). */
    fun loadCatalog(context: Context) {
        init(context)
        if (_state.value.catalog.isNotEmpty()) return
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
