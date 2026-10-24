package ua.nebo.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Знімок стану тривог по районах.
 * Рівні: [CALM], [RECENT] (нещодавній відбій), [YELLOW] (жовтий рівень, дронова загроза),
 * [PARTIAL] (тривога лише в частині району — громаді чи місті), [RED] (повітряна тривога).
 */
data class AlertState(
    val raions: Map<Int, Int>,
    /** «м. Київ», «Севастополь» -> рівень */
    val cities: Map<String, Int>,
    /** "r<індекс>" або "c<місто>" -> коли почалась тривога */
    val since: Map<String, Long>,
    val fetchedAt: Long,
    val source: String = "",
) {
    fun raionLevel(i: Int) = raions[i] ?: CALM
    fun cityLevel(name: String) = cities[name] ?: CALM

    fun oblastLevel(map: MapData, oblast: String): Int =
        map.raionsByOblast[oblast]?.maxOfOrNull { raionLevel(it.index) } ?: CALM

    /** Рівень для обраного дому: район, область (найвищий серед її районів) чи місто. */
    fun homeLevel(map: MapData, home: MapData.Home?): Int = when (home) {
        is MapData.Home.OfRaion -> raionLevel(home.raion.index)
        is MapData.Home.OfRegion -> if (home.region.isCity) cityLevel(home.region.name) else oblastLevel(map, home.region.name)
        null -> CALM
    }

    /** Чи тривога охоплює лише частину області (для підпису «частина області»). */
    fun homePartial(map: MapData, home: MapData.Home?): Boolean {
        if (home !is MapData.Home.OfRegion || home.region.isCity) return false
        val list = map.raionsByOblast[home.region.name] ?: return false
        val top = list.maxOfOrNull { raionLevel(it.index) } ?: return false
        return top >= YELLOW && list.any { raionLevel(it.index) < top }
    }

    fun homeSince(map: MapData, home: MapData.Home?): Long {
        val keys = when (home) {
            is MapData.Home.OfRaion -> listOf("r${home.raion.index}")
            is MapData.Home.OfRegion -> if (home.region.isCity) listOf("c${home.region.name}")
                else map.raionsByOblast[home.region.name].orEmpty().filter { raionLevel(it.index) >= YELLOW }.map { "r${it.index}" }
            null -> emptyList()
        }
        return keys.mapNotNull { since[it] }.filter { it > 0 }.minOrNull() ?: 0L
    }

    /** Кількість областей з тривогою (хоч у частині) і з лише жовтим рівнем. */
    fun counts(map: MapData): Pair<Int, Int> {
        var red = 0
        var yellow = 0
        for ((oblast, list) in map.raionsByOblast) {
            if (oblast == "м. Київ") continue
            val top = list.maxOfOrNull { raionLevel(it.index) } ?: CALM
            if (top >= PARTIAL) red++ else if (top == YELLOW) yellow++
        }
        return red to yellow
    }

    /** Підпис для порівняння: змінюється лише коли змінюється картина на мапі. */
    val signature: String
        get() = raions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" } + "|" +
            cities.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        const val CALM = 0
        const val RECENT = 1
        const val YELLOW = 2
        const val PARTIAL = 3
        const val RED = 4
        val EMPTY = AlertState(emptyMap(), emptyMap(), emptyMap(), 0L)

        fun levelName(level: Int) = when (level) {
            RED -> "тривога"; PARTIAL -> "тривога в частині"; YELLOW -> "жовтий рівень"; RECENT -> "нещодавній відбій"; else -> "тихо"
        }
    }
}

/**
 * Дані з відкритого API ubilling.net.ua/aerialalerts (без ключа, ліміт 2 запити/с).
 * Основне джерело — дані alerts.in.ua з рівнями (червоний / жовтий) по районах і громадах;
 * запасне — стан областей за замовчуванням.
 * Джерело неофіційне — для важливих рішень користуйтесь офіційною «Повітряною тривогою».
 */
object Alerts {
    private const val URL_DETAILED = "https://ubilling.net.ua/aerialalerts/?source=aiu&raw="
    private const val URL_OBLASTS = "https://ubilling.net.ua/aerialalerts/"

    /** Сервер попросив зачекати (HTTP 429). */
    class RateLimited(val retryAfterSec: Long) : Exception("Забагато запитів до джерела тривог, повтор через $retryAfterSec с")

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val changedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Kyiv") }

    fun fetch(context: Context): AlertState {
        return try {
            parseDetailed(context, get(URL_DETAILED))
        } catch (e: RateLimited) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber.w(e, "detailed source failed, fallback to oblasts")
            Thread.sleep(700)
            parseOblasts(context, get(URL_OBLASTS))
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", "Nebo/" + BuildConfig.VERSION_NAME + " (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code == 429) {
                val wait = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 60L
                throw RateLimited(wait.coerceIn(15L, 600L))
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseIso(s: String?): Long {
        if (s.isNullOrEmpty() || s == "null") return 0L
        return runCatching { synchronized(isoFormat) { isoFormat.parse(s.take(19))?.time ?: 0L } }.getOrDefault(0L)
    }

    /** Список активних тривог alerts.in.ua: області, райони, громади, міста. */
    fun parseDetailed(context: Context, body: String): AlertState {
        val map = MapData.get(context)
        val root = JSONObject(body)
        val list: JSONArray = root.optJSONArray("raw") ?: throw IllegalStateException("немає даних raw")
        val raions = HashMap<Int, Int>()
        val cities = HashMap<String, Int>()
        val since = HashMap<String, Long>()
        fun setRaion(i: Int, level: Int, start: Long) {
            if (level > (raions[i] ?: 0)) raions[i] = level
            if (start > 0) since["r$i"] = minOf(since["r$i"] ?: Long.MAX_VALUE, start)
        }
        fun setCity(name: String, level: Int, start: Long) {
            if (level > (cities[name] ?: 0)) cities[name] = level
            if (start > 0) since["c$name"] = minOf(since["c$name"] ?: Long.MAX_VALUE, start)
        }
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val fin = o.opt("finished_at")
            if (fin != null && fin != JSONObject.NULL && fin.toString().isNotEmpty() && fin.toString() != "null") continue
            if (o.optString("alert_type") != "air_raid") continue
            val yellow = o.optString("alert_level").equals("yellow", true)
            val type = o.optString("location_type")
            val title = o.optString("location_title")
            val oblast = o.optString("location_oblast")
            val start = parseIso(o.optString("started_at"))
            val titleKey = MapData.normalize(title)
            // Київ і Севастополь — окремі міста
            if (titleKey == "київ" || titleKey == "севастополь") {
                val city = map.find(title)?.name ?: continue
                setCity(city, if (yellow) AlertState.YELLOW else AlertState.RED, start)
                continue
            }
            when (type) {
                "oblast" -> {
                    val region = map.find(title) ?: continue
                    val lvl = if (yellow) AlertState.YELLOW else AlertState.RED
                    map.raionsByOblast[region.name]?.forEach { setRaion(it.index, lvl, start) }
                    if (region.name == "Автономна Республіка Крим") setCity("Севастополь", lvl, start)
                }
                "raion" -> {
                    val r = map.findRaion(title) ?: continue
                    setRaion(r.index, if (yellow) AlertState.YELLOW else AlertState.RED, start)
                }
                "hromada", "city" -> {
                    val idx = map.raionOfHromada(oblast, title) ?: continue
                    setRaion(idx, if (yellow) AlertState.YELLOW else AlertState.PARTIAL, start)
                }
            }
        }
        return AlertState(raions, cities, since, System.currentTimeMillis(), "alerts.in.ua")
    }

    /** Запасний варіант: лише ознака тривоги по областях. */
    fun parseOblasts(context: Context, body: String): AlertState {
        val map = MapData.get(context)
        val states = JSONObject(body).getJSONObject("states")
        val raions = HashMap<Int, Int>()
        val cities = HashMap<String, Int>()
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
                if (changed < 86_400_000L) changed = 0L
            } else {
                val s = v.toString().trim().lowercase()
                on = s !in setOf("false", "0", "null", "none", "", "no")
            }
            if (!on) continue
            if (region.isCity) {
                cities[region.name] = AlertState.RED
                if (changed > 0) since["c${region.name}"] = changed
            } else {
                map.raionsByOblast[region.name]?.forEach {
                    raions[it.index] = AlertState.RED
                    if (changed > 0) since["r${it.index}"] = changed
                }
            }
        }
        return AlertState(raions, cities, since, System.currentTimeMillis(), "області")
    }
}
