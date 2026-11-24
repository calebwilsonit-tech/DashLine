package app.seb3thehacker.gearslip.dash

import android.content.Context
import androidx.compose.runtime.Immutable
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A place the driver saved (Home, Work, "Mom's house") or went to recently. */
@Immutable
data class Spot(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    /** "home", "work", "fav" or "recent". */
    val kind: String = "fav",
) {
    val at get() = LatLng(lat, lng)
    fun place() = Place(id = "dash:$kind:$lat,$lng", name = name, location = at, address = address.ifBlank { null })

    fun json(): JSONObject = JSONObject().put("n", name).put("la", lat).put("lo", lng).put("a", address).put("k", kind)

    companion object {
        fun of(p: Place, name: String = p.name, kind: String) =
            Spot(name, p.location.lat, p.location.lng, p.address.orEmpty(), kind)

        fun from(o: JSONObject) = Spot(o.optString("n"), o.optDouble("la"), o.optDouble("lo"), o.optString("a"), o.optString("k", "fav"))
    }
}

/** Saved places and recent destinations, on this phone only. */
object DashPlaces {
    private const val FILE = "dashline_places"
    private const val MAX_RECENTS = 10

    private val _saved = MutableStateFlow<List<Spot>>(emptyList())
    val saved: StateFlow<List<Spot>> = _saved.asStateFlow()
    private val _recents = MutableStateFlow<List<Spot>>(emptyList())
    val recents: StateFlow<List<Spot>> = _recents.asStateFlow()

    /** A saved trip: a destination and the stops on the way. */
    @Immutable
    data class Trip(val name: String, val dest: Spot, val stops: List<Spot>)

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    fun saveTrip(dest: Place, stops: List<Place>) {
        val name = if (stops.isEmpty()) dest.name else "${dest.name} via ${stops.joinToString(", ") { it.name }}"
        val trip = Trip(name, Spot.of(dest, kind = "trip"), stops.map { Spot.of(it, kind = "trip") })
        _trips.value = _trips.value.filterNot { it.name == trip.name } + trip
        write()
    }

    fun removeTrip(t: Trip) { _trips.value = _trips.value - t; write() }

    private var context: Context? = null

    fun init(c: Context) {
        if (context != null) return
        context = c.applicationContext
        val prefs = prefs() ?: return
        _saved.value = read(prefs.getString("saved", null))
        _recents.value = read(prefs.getString("recents", null))
        _trips.value = runCatching {
            val a = JSONArray(prefs.getString("trips", null) ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val st = o.getJSONArray("stops")
                Trip(o.getString("name"), Spot.from(o.getJSONObject("dest")), (0 until st.length()).map { Spot.from(st.getJSONObject(it)) })
            }
        }.getOrDefault(emptyList())
    }

    fun home() = _saved.value.firstOrNull { it.kind == "home" }
    fun work() = _saved.value.firstOrNull { it.kind == "work" }

    /** Home and Work replace the old one; favorites with the same name replace it too. */
    fun save(p: Place, name: String, kind: String) {
        val spot = Spot.of(p, name.trim().ifBlank { p.name }, kind)
        _saved.value = _saved.value.filterNot {
            (kind != "fav" && it.kind == kind) || it.name.equals(spot.name, ignoreCase = true)
        } + spot
        write()
    }

    fun remove(spot: Spot) {
        _saved.value = _saved.value - spot
        write()
    }

    fun addRecent(p: Place) {
        if (p.id.startsWith("dash:")) return // already a saved place
        val spot = Spot.of(p, kind = "recent")
        _recents.value = (listOf(spot) + _recents.value.filterNot { metersBetween(it.at, spot.at) < 60 }).take(MAX_RECENTS)
        write()
    }

    /** "home", "work", "mom's house", "moms house": a saved place by what the driver calls it. */
    fun find(said: String): Spot? {
        val q = norm(said).removePrefix("my ")
        if (q.isEmpty()) return null
        if (q == "home" || q == "house") home()?.let { return it }
        if (q == "work" || q == "the office" || q == "office") work()?.let { return it }
        return _saved.value.firstOrNull { norm(it.name) == q }
            ?: _saved.value.firstOrNull { norm(it.name).contains(q) || q.contains(norm(it.name)) }
    }

    /** Saved and recent places whose name or address contains [term], saved first. */
    fun matching(term: String): List<Spot> {
        val q = norm(term)
        if (q.isEmpty()) return emptyList()
        return (_saved.value + _recents.value).filter { norm(it.name).contains(q) || norm(it.address).contains(q) }
            .distinctBy { "${it.lat},${it.lng}" }.take(4)
    }

    private fun norm(s: String) = s.lowercase().replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun read(raw: String?): List<Spot> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        (0 until a.length()).map { Spot.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write() {
        val prefs = prefs() ?: return
        prefs.edit()
            .putString("saved", JSONArray().apply { _saved.value.forEach { put(it.json()) } }.toString())
            .putString("recents", JSONArray().apply { _recents.value.forEach { put(it.json()) } }.toString())
            .putString("trips", JSONArray().apply {
                _trips.value.forEach { t ->
                    put(JSONObject().put("name", t.name).put("dest", t.dest.json()).put("stops", JSONArray().apply { t.stops.forEach { put(it.json()) } }))
                }
            }.toString())
            .apply()
    }

    private fun prefs() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
