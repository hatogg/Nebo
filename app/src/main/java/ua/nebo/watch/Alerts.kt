package ua.nebo.watch

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Знімок стану тривог: назви регіонів (як у map.json), де зараз тривога, і коли вона почалась. */
data class AlertState(
    val active: Set<String>,
    val since: Map<String, Long>,
    val fetchedAt: Long,
) {
    fun isActive(region: String) = region in active

    /** Підпис для порівняння: змінюється лише коли змінюється набір тривог. */
    val signature: String get() = active.sorted().joinToString("|")

    companion object {
        val EMPTY = AlertState(emptySet(), emptyMap(), 0L)
    }
}

/**
 * Дані з відкритого API ubilling.net.ua/aerialalerts (без ключа, ліміт 2 запити/с).
 * Джерело неофіційне — для важливих рішень користуйтесь офіційною «Повітряною тривогою».
 */
object Alerts {
    private const val URL_API = "https://ubilling.net.ua/aerialalerts/"

    private val changedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Kyiv")
    }

    fun fetch(context: Context): AlertState {
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Nebo/" + BuildConfig.VERSION_NAME + " (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(context, body)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(context: Context, body: String): AlertState {
        val map = MapData.get(context)
        val states = JSONObject(body).getJSONObject("states")
        val active = HashSet<String>()
        val since = HashMap<String, Long>()
        val keys = states.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val region = map.find(name) ?: continue
            val v = states.get(name)
            var on: Boolean
            var changed = 0L
            if (v is JSONObject) {
                on = v.optBoolean("alertnow", false) || v.optBoolean("status", false)
                val ch = v.optString("changed", "")
                if (ch.isNotEmpty()) changed = runCatching { synchronized(changedFormat) { changedFormat.parse(ch)?.time ?: 0L } }.getOrDefault(0L)
            } else {
                val s = v.toString().trim().lowercase()
                on = s !in setOf("false", "0", "null", "none", "", "no")
            }
            if (on) {
                active += region.name
                if (changed > 0) since[region.name] = changed
            }
        }
        return AlertState(active, since, System.currentTimeMillis())
    }
}
