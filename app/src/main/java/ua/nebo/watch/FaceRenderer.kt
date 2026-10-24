package ua.nebo.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Малює фон циферблата. Верхню частину (~32% висоти) лишаємо порожньою:
 * там годинник сам показує час і дату (стиль із шаблону FitCloud, позиція TOP).
 */
class FaceRenderer(private val context: Context) {

    private val unbounded: Typeface = Typeface.createFromAsset(context.assets, "fonts/Unbounded-Bold.ttf")
    private val manBold: Typeface = Typeface.createFromAsset(context.assets, "fonts/Manrope-Bold.ttf")
    private val manSemi: Typeface = Typeface.createFromAsset(context.assets, "fonts/Manrope-SemiBold.ttf")

    private val cMuted = Color.parseColor("#8A8F9C")
    private val cGreen = Color.parseColor("#3DDC84")
    private val cHome = Color.WHITE

    fun render(width: Int, height: Int, state: AlertState, home: String, style: String, showRecent: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        // Макет розрахований на 410×502 (K61 Pro), для інших екранів масштабуємо
        val k = minOf(width / 410f, height / 502f)
        c.save()
        c.translate((width - 410f * k) / 2f, (height - 502f * k) / 2f)
        c.scale(k, k)
        val map = MapData.get(context)
        val h = map.home(home)
        val st = if (showRecent) state else state.copy(raions = state.raions.filterValues { it != AlertState.RECENT })
        if (style == Prefs.STYLE_HOME) drawHome(c, map, st, h) else drawMapFace(c, map, st, h)
        c.restore()
        return bmp
    }

    private fun paint(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; textAlign = align
        }

    /** Малює карту в прямокутнику шириною w з лівим верхнім кутом (x, y). Повертає висоту. */
    private fun drawMap(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?, x: Float, y: Float, w: Float, borderW: Float, homeW: Float): Float {
        val s = w / map.width
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = borderW * 0.45f / s; strokeJoin = Paint.Join.ROUND
        }
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = borderW / s; strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        if (map.raions.isNotEmpty()) {
            for (r in map.raions) {
                fill.color = levelColor(state.raionLevel(r.index))
                c.drawPath(r.path, fill)
                c.drawPath(r.path, thin)
            }
            for (r in map.regions) r.path?.let { c.drawPath(it, thick) }
        } else {
            for (r in map.regions) {
                val p = r.path ?: continue
                fill.color = levelColor(state.oblastLevel(map, r.name))
                c.drawPath(p, fill); c.drawPath(p, thick)
            }
        }
        val homeStroke = Paint(thick).apply { color = cHome; strokeWidth = homeW / s }
        when (home) {
            is MapData.Home.OfRaion -> c.drawPath(home.raion.path, homeStroke)
            is MapData.Home.OfRegion -> home.region.path?.let { c.drawPath(it, homeStroke) }
            null -> {}
        }
        for (r in map.regions) {
            if (!r.isCity) continue
            val rad = (if (r.name == "м. Київ") 4.4f else 3f) / s
            fill.color = levelColor(state.cityLevel(r.name))
            c.drawCircle(r.cx, r.cy, rad, fill)
            val isHome = home is MapData.Home.OfRegion && home.region.name == r.name
            val st = if (isHome) homeStroke else Paint(thick).apply { strokeWidth = 1.4f / s }
            c.drawCircle(r.cx, r.cy, rad, st)
        }
        c.restore()
        return map.height * s
    }

    private fun drawMapFace(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val mh = drawMap(c, map, state, home, 14f, 172f, 382f, 1.3f, 2.4f)
        val short = home?.short ?: "—"
        val level = state.homeLevel(map, home)
        val (red, yellow) = state.counts(map)
        val y = 172f + mh + 16f
        if (level >= AlertState.YELLOW) {
            val word = if (level >= AlertState.PARTIAL) "ТРИВОГА" else "ЗАГРОЗА"
            val text = word + " · " + short.uppercase(Locale("uk"))
            val p = paint(unbounded, 19f, if (level >= AlertState.PARTIAL) Color.WHITE else Color.parseColor("#1A1300"))
            while (p.measureText(text) > 354f && p.textSize > 11f) p.textSize -= 1f
            val tw = p.measureText(text)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = levelColor(if (level >= AlertState.PARTIAL) AlertState.RED else AlertState.YELLOW) }
            c.drawRoundRect(RectF(205f - tw / 2 - 18f, y, 205f + tw / 2 + 18f, y + 42f), 21f, 21f, bg)
            c.drawText(text, 205f, y + 21f - (p.descent() + p.ascent()) / 2f, p)
        } else {
            val main = if (red > 0) "Тривога в $red " + plural(red, "області", "областях", "областях") else if (yellow > 0) "Жовтий рівень в $yellow " + plural(yellow, "області", "областях", "областях") else "Тривог немає"
            val p1 = paint(manBold, 21f, if (red > 0 || yellow > 0) Color.WHITE else cGreen)
            c.drawText(main, 205f, y + 22f, p1)
            val sub = if (red > 0 && yellow > 0) "загроза ще в $yellow · $short — тихо" else "$short — тихо"
            val p2 = paint(manSemi, 17f, cMuted)
            while (p2.measureText(sub) > 380f && p2.textSize > 11f) p2.textSize -= 1f
            c.drawText(sub, 205f, y + 48f, p2)
        }
    }

    private fun drawHome(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val level = state.homeLevel(map, home)
        val card = RectF(16f, 166f, 394f, 326f)
        val (bgCol, fg, word, subCol) = when {
            level >= AlertState.PARTIAL -> Quad("#3B0E12", "#FF5A5F", "ТРИВОГА", "#FFB3B5")
            level == AlertState.YELLOW -> Quad("#3A2D05", "#FFC93C", "ЗАГРОЗА", "#FFE29A")
            else -> Quad("#0F2A1B", "#3DDC84", "ТИХО", "#9FE3BD")
        }
        c.drawRoundRect(card, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(bgCol) })
        val big = paint(unbounded, 46f, Color.parseColor(fg))
        while (big.measureText(word) > 350f) big.textSize -= 1f
        c.drawText(word, 205f, card.top + 68f, big)
        val namePaint = paint(manBold, 27f, Color.WHITE)
        val name = home?.short ?: "Оберіть область"
        while (namePaint.measureText(name) > 350f && namePaint.textSize > 14f) namePaint.textSize -= 1f
        c.drawText(name, 205f, card.top + 106f, namePaint)
        val t = state.homeSince(map, home)
        val partial = state.homePartial(map, home)
        val sub = when {
            level >= AlertState.YELLOW -> {
                val kind = if (level == AlertState.YELLOW) "дрони" else if (level == AlertState.PARTIAL || partial) "частина області" else ""
                val time = if (t > 0) "з " + sinceText(t) else ""
                listOf(kind, time).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "оголошено зараз" }
            }
            level == AlertState.RECENT -> "щойно відбій"
            else -> "тривоги немає"
        }
        c.drawText(sub, 205f, card.top + 140f, paint(manSemi, 20f, Color.parseColor(subCol)))

        val mw = 200f
        val top = card.bottom + 18f
        drawMap(c, map, state, home, 16f, top, mw, 0.9f, 1.8f)
        val (red, yellow) = state.counts(map)
        val tx = 16f + mw + 18f
        c.drawText(red.toString(), tx, top + 58f, paint(unbounded, 50f, if (red > 0) levelColor(AlertState.RED) else cGreen, Paint.Align.LEFT))
        val lp = paint(manSemi, 17f, cMuted, Paint.Align.LEFT)
        c.drawText(plural(red, "область", "області", "областей"), tx, top + 86f, lp)
        c.drawText("у тривозі", tx, top + 108f, lp)
        if (yellow > 0) c.drawText("+$yellow жовтий рівень", tx, top + 134f, paint(manBold, 16f, levelColor(AlertState.YELLOW), Paint.Align.LEFT))
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    companion object {
        fun levelColor(level: Int): Int = when (level) {
            AlertState.RED -> Color.parseColor("#E5383B")
            AlertState.PARTIAL -> Color.parseColor("#A9282C")
            AlertState.YELLOW -> Color.parseColor("#F2B51E")
            AlertState.RECENT -> Color.parseColor("#4A2A30")
            else -> Color.parseColor("#252833")
        }

        /** «14:32» сьогодні, «24.10 21:05» цього року, «04.04.2022» — для давніх тривог. */
        fun sinceText(t: Long): String {
            val now = java.util.Calendar.getInstance()
            val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
            val sameYear = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
            val sameDay = sameYear && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
            val pattern = when {
                sameDay -> "HH:mm"
                sameYear -> "dd.MM HH:mm"
                else -> "dd.MM.yyyy"
            }
            return SimpleDateFormat(pattern, Locale.US).format(Date(t))
        }

        fun plural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10
            val m100 = n % 100
            return when {
                m10 == 1 && m100 != 11 -> one
                m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
                else -> many
            }
        }
    }
}
