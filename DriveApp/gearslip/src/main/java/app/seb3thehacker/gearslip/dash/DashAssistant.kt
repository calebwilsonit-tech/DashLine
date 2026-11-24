package app.seb3thehacker.gearslip.dash

import android.content.Context
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.placeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * Dashline's voice assistant. Gearslip's [app.seb3thehacker.gearslip.car.CarAssistant] does the
 * listening and speaking (steering-wheel voice key or the mic button); this decides what to do.
 *
 * With a Gemini API key (entered on the phone in Dashline settings), Gemini understands the
 * request and either answers it or picks one of the actions below, which run on the phone:
 * call or text a contact, play music, pause/skip, the garage, or a web search for anything
 * current. Without a key (or offline) a small keyword fallback still handles call/text/play.
 */
object DashAssistant {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val MAX_TURNS = 6

    /** Recent exchanges, so "text him back" or "what about tomorrow?" have context. Cleared per drive. */
    private val history = ArrayDeque<JSONObject>()
    private var contacts: List<DashContact>? = null

    fun reset() {
        history.clear()
        contacts = null
    }

    /** Turns what was heard into something done plus a sentence to say back ("" says nothing). */
    suspend fun handle(context: Context, heard: String): String {
        val text = heard.trim()
        if (text.isEmpty()) return "Sorry, I didn't catch that."
        val key = DashPrefs.geminiKey(context)
        if (key.isBlank()) {
            return local(context, text) ?: "To ask me anything, add a Gemini key in Dashline settings on your phone."
        }
        return try {
            gemini(context, key, text)
        } catch (e: Exception) {
            GearslipLog.w("assistant: gemini failed: ${e.javaClass.simpleName}: ${e.message}")
            local(context, text) ?: when {
                e is GeminiHttp && (e.code == 400 || e.code == 401 || e.code == 403) -> "Gemini didn't accept your API key. Check it in Dashline settings."
                e is GeminiHttp && e.code == 429 -> "Gemini is rate limiting right now. Try again in a minute."
                else -> "I can't reach Gemini right now. Check the phone's internet connection."
            }
        }
    }

    // --- Gemini -------------------------------------------------------------------------------

    private class GeminiHttp(val code: Int, body: String) : Exception("HTTP $code: ${body.take(200)}")

    private suspend fun gemini(context: Context, key: String, text: String): String {
        val model = DashPrefs.geminiModel(context)
        val user = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        val contents = JSONArray().apply { history.forEach(::put); put(user) }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions())))
        val reply = post(model, key, body)
        val parts = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return "Sorry, I didn't get an answer."

        for (i in 0 until parts.length()) {
            val call = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val name = call.optString("name")
            val args = call.optJSONObject("args") ?: JSONObject()
            GearslipLog.i("assistant: gemini chose $name")
            val said = act(context, key, model, name, args)
            remember(user, said)
            return said
        }
        val answer = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            .joinToString(" ").let(::spoken)
        remember(user, answer)
        return answer.ifBlank { "Sorry, I don't have an answer for that." }
    }

    /** One plain search-grounded answer, for news, weather, scores, hours: anything current. */
    private suspend fun search(key: String, model: String, query: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", query)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val parts = post(model, key, body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return "I couldn't find that."
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            .joinToString(" ").let(::spoken).ifBlank { "I couldn't find that." }
    }

    private suspend fun post(model: String, key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(ENDPOINT.format(model)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // In a header, never in the URL, so the key doesn't end up in logs.
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GeminiHttp(code, text)
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun remember(user: JSONObject, said: String) {
        history.addLast(user)
        history.addLast(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", said.ifBlank { "Done." }))))
        while (history.size > MAX_TURNS * 2) history.removeFirst()
    }

    private fun systemPrompt() = """
        You are Dashline, the voice assistant on the car screen of the driver's 2017 GMC Sierra.
        Everything you say is read aloud while they drive: answer in one to three short, natural
        sentences. No lists, markdown, emojis or URLs. Be direct and friendly.
        Use the tools to call or text someone, play or control music, or open/close the garage.
        For anything that depends on current information (news, weather, scores, hours, prices,
        traffic, recent events) call web_search instead of guessing.
        Now: ${DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date())}.
    """.trimIndent()

    private fun functions() = JSONArray()
        .put(fn("call_contact", "Phone call to someone in the driver's contacts.", "name" to "Contact name as said"))
        .put(fn(
            "text_contact",
            "Send a text message to a contact. Include message only if the driver already said what to send.",
            "name" to "Contact name as said",
            "message" to "The message text, if given",
            required = listOf("name"),
        ))
        .put(fn("play_music", "Play a song, artist, album, playlist or genre on YouTube Music.", "query" to "What to play"))
        .put(fn("media_control", "Control what's playing.", "action" to "One of: pause, resume, next, previous"))
        .put(fn("garage_door", "Open or close the garage door (it toggles).", required = emptyList()))
        .put(fn("navigate_to", "Start turn-by-turn directions to a place, address or business.", "destination" to "Where to go"))
        .put(fn("drive_control", "Control the current drive.", "action" to "One of: eta, cancel, mute_directions, unmute_directions"))
        .put(fn("find_on_route", "Find a stop along the current route, like gas or coffee.", "query" to "What to look for"))
        .put(fn("web_search", "Look up current information on the web.", "query" to "Search query"))

    private fun fn(name: String, description: String, vararg params: Pair<String, String>, required: List<String>? = null): JSONObject {
        val props = JSONObject()
        params.forEach { (p, d) -> props.put(p, JSONObject().put("type", "string").put("description", d)) }
        val schema = JSONObject().put("type", "object").put("properties", props)
        val req = required ?: params.map { it.first }
        if (req.isNotEmpty()) schema.put("required", JSONArray(req))
        return JSONObject().put("name", name).put("description", description).apply {
            if (params.isNotEmpty()) put("parameters", schema)
        }
    }

    private suspend fun act(context: Context, key: String, model: String, name: String, args: JSONObject): String =
        when (name) {
            "call_contact" -> call(context, args.optString("name"))
            "text_contact" -> text(context, args.optString("name"), args.optString("message"))
            "play_music" -> play(context, args.optString("query"))
            "media_control" -> media(args.optString("action"))
            "garage_door" -> {
                Garage.press(context)
                "Okay, the garage door."
            }
            "web_search" -> search(key, model, args.optString("query"))
            "drive_control" -> drive(args.optString("action"))
            "find_on_route" -> { DashNav.searchAlongRoute(args.optString("query")); DashNav.showMap.value++; "Looking along your route." }
            "navigate_to" -> {
                DashNav.navigateTo(args.optString("destination"))
                "Getting directions to ${args.optString("destination")}."
            }
            else -> "Sorry, I can't do that yet."
        }

    // --- actions ------------------------------------------------------------------------------

    private suspend fun findContact(context: Context, said: String): DashContact? {
        val all = contacts ?: withContext(Dispatchers.IO) { DashContacts.load(context) }.also { contacts = it }
        val q = said.lowercase().trim().removePrefix("my ")
        if (q.isEmpty()) return null
        val ranked = all.filter { c ->
            val n = c.name.lowercase()
            n == q || n.startsWith("$q ") || n.split(' ').any { it == q } || n.contains(q)
        }.sortedWith(compareByDescending<DashContact> { it.name.lowercase() == q }.thenByDescending { it.starred })
        return ranked.firstOrNull()
    }

    private suspend fun call(context: Context, who: String): String {
        val c = findContact(context, who) ?: return "I couldn't find $who in your contacts."
        placeCall(context, c.number)
        return "Calling ${c.name}."
    }

    private suspend fun text(context: Context, who: String, message: String): String {
        val c = findContact(context, who) ?: return "I couldn't find $who in your contacts."
        // DashText does the asking/reading back and the cancellable countdown, and speaks itself.
        if (message.isBlank()) DashText.start(c) else DashText.startWith(c, message)
        return ""
    }

    private fun play(context: Context, query: String): String {
        PhoneMedia.playFromSearch(context, query)
        return if (query.isBlank()) "Playing music." else "Playing $query."
    }

    private fun media(action: String): String = when (action.lowercase()) {
        "pause", "stop" -> { PhoneMedia.pause(); "" }
        "resume", "play" -> { PhoneMedia.play(); "" }
        "next", "skip" -> { PhoneMedia.next(); "" }
        "previous", "back" -> { PhoneMedia.previous(); "" }
        else -> "Sorry, I can't do that with the music."
    }

    private fun drive(action: String): String {
        val nav = DashNav.session.state.value
        return when (action.lowercase()) {
            "eta" -> if (!nav.navigating) "You're not navigating right now."
                else "${minutes(nav.remainingDuration)} to go, ${miles(nav.remainingDistance)}. You'll get there at ${android.text.format.DateFormat.format("h:mm a", System.currentTimeMillis() + (nav.remainingDuration * 1000).toLong())}."
            "cancel" -> { DashNav.stop(); "Navigation ended." }
            "mute_directions" -> { DashNav.voiceOn.value = false; "Directions muted." }
            "unmute_directions" -> { DashNav.voiceOn.value = true; "Directions on." }
            else -> "Sorry, I can't do that with the drive."
        }
    }

    /** Works without a key or a connection: the commands a driver needs most. */
    private suspend fun local(context: Context, said: String): String? {
        val t = said.lowercase().replace(Regex("[.,!?]"), "").trim()
        Regex("^(?:call|phone|dial) (.+)$").find(t)?.let { return call(context, it.groupValues[1]) }
        Regex("^(?:text|message|send a text to|send a message to) (\\S+(?: \\S+)?)(?: (?:saying|that) (.+))?$").find(t)?.let {
            return text(context, it.groupValues[1], it.groupValues[2])
        }
        when (t) {
            "whats my eta", "what's my eta", "eta", "when will i get there", "how long left" -> return drive("eta")
            "cancel navigation", "stop navigation", "end navigation", "end route" -> return drive("cancel")
            "mute directions", "mute navigation", "quiet" -> return drive("mute_directions")
            "unmute directions", "unmute navigation" -> return drive("unmute_directions")
            "pause", "stop the music", "pause the music" -> return media("pause")
            "resume", "play", "play music" -> return media("resume")
            "next", "skip", "next song" -> return media("next")
            "previous", "previous song", "go back" -> return media("previous")
            "open the garage", "close the garage", "garage", "open garage", "close garage" -> {
                Garage.press(context)
                return "Okay, the garage door."
            }
        }
        Regex("^(?:find|add a|add|look for) (.+?) (?:on the way|along the way|on my route|along the route|stop)$").find(t)?.let {
            DashNav.searchAlongRoute(it.groupValues[1]); DashNav.showMap.value++
            return "Looking along your route."
        }
        Regex("^(?:navigate to|directions to|take me to|drive to|go to) (.+)$").find(t)?.let {
            DashNav.navigateTo(it.groupValues[1])
            return "Getting directions to ${it.groupValues[1]}."
        }
        Regex("^play (.+)$").find(t)?.let { return play(context, it.groupValues[1]) }
        return null
    }

    /** Strips formatting that sounds wrong read aloud. */
    private fun spoken(s: String) = s.replace(Regex("[*_#`]"), "").replace(Regex("\\[(\\d+(,\\s*\\d+)*)]"), "")
        .replace(Regex("\\s+"), " ").trim()
}
