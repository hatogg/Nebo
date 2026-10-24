package ua.nebo.watch

import android.content.Context
import android.graphics.Path
import org.json.JSONObject

/**
 * Контури областей і районів України в координатах 1000×h.
 * Області: OpenStreetMap (ODbL). Райони й громади: slawomirmatuszak/ukrainian_geodata (CC BY 4.0).
 */
class MapData private constructor(
    val width: Float,
    val height: Float,
    /** Області, Автономна Республіка Крим і міста зі спецстатусом (Київ, Севастополь — без контуру). */
    val regions: List<Region>,
    val raions: List<Raion>,
    /** область -> (назва громади без «територіальна громада») -> індекс району */
    private val hromady: Map<String, Map<String, Int>>,
) {

    class Region(val name: String, val short: String, val path: Path?, val cx: Float, val cy: Float, val isCity: Boolean) {
        val key: String = normalize(name)
    }

    class Raion(val index: Int, val name: String, val oblast: String, val path: Path, val cx: Float, val cy: Float) {
        val key: String = raionKey(name)
        /** «Вишгородський р-н» */
        val short: String = name.replace(" район", " р-н")
    }

    private val raionByKey: Map<String, Raion> = raions.associateBy { it.key }
    val raionsByOblast: Map<String, List<Raion>> = raions.groupBy { it.oblast }

    fun find(name: String): Region? {
        val k = normalize(name)
        return regions.firstOrNull { it.key == k }
    }

    fun findRaion(name: String): Raion? = raionByKey[raionKey(name)]

    /** Район за назвою громади в межах області (для тривог рівня громади). */
    fun raionOfHromada(oblast: String, title: String): Int? {
        val o = find(oblast)?.name ?: return null
        val table = hromady[o] ?: return null
        val key = hromadaKey(title)
        table[key]?.let { return it }
        // «м. Нікополь» -> «нікопольська»
        val city = key.removePrefix("м.").removePrefix("м ").trim()
        if (city.length >= 4) {
            val stem = city.take(minOf(5, city.length - 1))
            table.entries.firstOrNull { it.key.startsWith(stem) }?.let { return it.value }
        }
        return null
    }

    /** Що обрано домашнім: район, область чи місто. */
    sealed class Home(val name: String, val short: String) {
        class OfRaion(val raion: Raion) : Home(raion.name, raion.short)
        class OfRegion(val region: Region) : Home(region.name, region.short)
    }

    fun home(name: String): Home? {
        findRaion(name)?.let { return Home.OfRaion(it) }
        find(name)?.let { return Home.OfRegion(it) }
        return null
    }

    companion object {
        @Volatile private var cached: MapData? = null

        fun get(context: Context): MapData = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        private fun ringsToPath(rings: org.json.JSONArray): Path? {
            if (rings.length() == 0) return null
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (r in 0 until rings.length()) {
                val pts = rings.getString(r).split(' ')
                for ((j, p) in pts.withIndex()) {
                    val comma = p.indexOf(',')
                    val x = p.substring(0, comma).toFloat()
                    val y = p.substring(comma + 1).toFloat()
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            return path
        }

        private fun load(context: Context): MapData {
            val text = context.assets.open("map.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("regions")
            val regions = ArrayList<Region>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = o.getJSONArray("c")
                regions += Region(
                    o.getString("name"), o.getString("short"), ringsToPath(o.getJSONArray("rings")),
                    c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), o.optBoolean("city", false)
                )
            }
            val ra = root.optJSONArray("raions") ?: org.json.JSONArray()
            val raions = ArrayList<Raion>(ra.length())
            for (i in 0 until ra.length()) {
                val o = ra.getJSONObject(i)
                val c = o.getJSONArray("c")
                val path = ringsToPath(o.getJSONArray("rings")) ?: continue
                raions += Raion(raions.size, o.getString("name"), o.getString("oblast"), path, c.getDouble(0).toFloat(), c.getDouble(1).toFloat())
            }
            val hromady = HashMap<String, Map<String, Int>>()
            root.optJSONObject("hromady")?.let { h ->
                val keys = h.keys()
                while (keys.hasNext()) {
                    val ob = keys.next()
                    val inner = h.getJSONObject(ob)
                    val m = HashMap<String, Int>()
                    val ik = inner.keys()
                    while (ik.hasNext()) { val k = ik.next(); m[k] = inner.getInt(k) }
                    hromady[ob] = m
                }
            }
            return MapData(root.getDouble("w").toFloat(), root.getDouble("h").toFloat(), regions, raions, hromady)
        }

        private fun apos(s: String) = s.lowercase().replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').trim()

        /** Зводить різні написання назв («м. Київ», «Київська обл.», «АР Крим») до одного ключа. */
        fun normalize(raw: String): String {
            var s = apos(raw)
            if (s.contains("крим")) return "крим"
            if (s.contains("севастоп")) return "севастополь"
            s = s.replace("область", "").replace("обл.", "").replace("обл", "")
            s = s.replace("місто", "").replace("м.", "").trim()
            s = s.replace(Regex("\\s+"), " ")
            return s
        }

        fun raionKey(raw: String): String = apos(raw).replace(" район", "").replace(" р-н", "").replace(Regex("\\s+"), " ").trim()

        fun hromadaKey(raw: String): String = apos(raw).replace(Regex("\\s*територіальна громада\\s*$"), "").trim()
    }
}
