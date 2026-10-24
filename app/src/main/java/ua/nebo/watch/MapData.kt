package ua.nebo.watch

import android.content.Context
import android.graphics.Path
import org.json.JSONObject

/** Спрощені контури областей України (OpenStreetMap, ODbL) у координатах 1000×h. */
class MapData private constructor(val width: Float, val height: Float, val regions: List<Region>) {

    class Region(
        val name: String,
        val short: String,
        val path: Path?,
        val cx: Float,
        val cy: Float,
        val isCity: Boolean,
    ) {
        val key: String = normalize(name)
    }

    fun find(name: String): Region? {
        val k = normalize(name)
        return regions.firstOrNull { it.key == k }
    }

    /** Кількість саме областей (без Києва й Севастополя як міст). */
    fun oblastCount(alerts: Set<String>): Int = regions.count { !it.isCity && it.name in alerts }

    companion object {
        @Volatile private var cached: MapData? = null

        fun get(context: Context): MapData = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        private fun load(context: Context): MapData {
            val text = context.assets.open("map.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("regions")
            val list = ArrayList<Region>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rings = o.getJSONArray("rings")
                var path: Path? = null
                if (rings.length() > 0) {
                    path = Path().apply { fillType = Path.FillType.EVEN_ODD }
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
                }
                val c = o.getJSONArray("c")
                list += Region(
                    name = o.getString("name"),
                    short = o.getString("short"),
                    path = path,
                    cx = c.getDouble(0).toFloat(),
                    cy = c.getDouble(1).toFloat(),
                    isCity = o.optBoolean("city", false),
                )
            }
            return MapData(root.getDouble("w").toFloat(), root.getDouble("h").toFloat(), list)
        }

        /** Зводить різні написання назв («м. Київ», «Київська обл.», «АР Крим») до одного ключа. */
        fun normalize(raw: String): String {
            var s = raw.lowercase().replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').trim()
            if (s.contains("крим")) return "крим"
            if (s.contains("севастоп")) return "севастополь"
            s = s.replace("область", "").replace("обл.", "").replace("обл", "")
            s = s.replace("місто", "").replace("м.", "").trim()
            s = s.replace(Regex("\\s+"), " ")
            return s
        }
    }
}
