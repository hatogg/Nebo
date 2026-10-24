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

    private val cCalm = Color.parseColor("#252833")
    private val cAlert = Color.parseColor("#E5383B")
    private val cHome = Color.parseColor("#FFC857")
    private val cMuted = Color.parseColor("#8A8F9C")
    private val cGreen = Color.parseColor("#3DDC84")

    fun render(width: Int, height: Int, state: AlertState, home: String, style: String): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        // Макет розрахований на 410×502 (K61 Pro), для інших екранів масштабуємо
        val k = minOf(width / 410f, height / 502f)
        c.save()
        c.translate((width - 410f * k) / 2f, (height - 502f * k) / 2f)
        c.scale(k, k)
        val map = MapData.get(context)
        if (style == Prefs.STYLE_HOME) drawHome(c, map, state, home) else drawMapFace(c, map, state, home)
        c.restore()
        return bmp
    }

    private fun paint(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; textAlign = align
        }

    /** Малює карту в прямокутнику шириною w з лівим верхнім кутом (x, y). Повертає висоту. */
    private fun drawMap(c: Canvas, map: MapData, state: AlertState, home: String, x: Float, y: Float, w: Float, borderW: Float, homeW: Float): Float {
        val s = w / map.width
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = borderW / s
            strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        for (r in map.regions) {
            val p = r.path ?: continue
            fill.color = if (state.isActive(r.name)) cAlert else cCalm
            c.drawPath(p, fill)
            c.drawPath(p, stroke)
        }
        val homePath = map.find(home)?.path
        if (homePath != null) {
            stroke.color = cHome; stroke.strokeWidth = homeW / s
            c.drawPath(homePath, stroke)
        }
        for (r in map.regions) {
            if (!r.isCity) continue
            val rad = (if (r.name == "м. Київ") 4.2f else 3f) / s
            fill.color = if (state.isActive(r.name)) cAlert else cCalm
            c.drawCircle(r.cx, r.cy, rad, fill)
            stroke.color = if (r.name == home) cHome else Color.BLACK
            stroke.strokeWidth = (if (r.name == home) homeW else 1.4f) / s
            c.drawCircle(r.cx, r.cy, rad, stroke)
        }
        c.restore()
        return map.height * s
    }

    private fun drawMapFace(c: Canvas, map: MapData, state: AlertState, home: String) {
        val mh = drawMap(c, map, state, home, 14f, 172f, 382f, 1.3f, 2.6f)
        val homeRegion = map.find(home)
        val short = homeRegion?.short ?: home
        val n = map.oblastCount(state.active)
        val y = 172f + mh + 16f
        if (homeRegion != null && state.isActive(homeRegion.name)) {
            val text = "ТРИВОГА · " + short.uppercase(Locale("uk"))
            val p = paint(unbounded, 20f, Color.WHITE)
            while (p.measureText(text) > 350f && p.textSize > 12f) p.textSize -= 1f
            val tw = p.measureText(text)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAlert }
            c.drawRoundRect(RectF(205f - tw / 2 - 18f, y, 205f + tw / 2 + 18f, y + 44f), 22f, 22f, bg)
            c.drawText(text, 205f, y + 22f - (p.descent() + p.ascent()) / 2f, p)
        } else {
            val main = if (n > 0) "Тривога в $n " + plural(n, "області", "областях", "областях") else "Тривог немає"
            val p1 = paint(manBold, 22f, if (n > 0) Color.WHITE else cGreen)
            c.drawText(main, 205f, y + 22f, p1)
            val p2 = paint(manSemi, 18f, cMuted)
            c.drawText("$short — тихо", 205f, y + 48f, p2)
        }
    }

    private fun drawHome(c: Canvas, map: MapData, state: AlertState, home: String) {
        val r = map.find(home)
        val active = r != null && state.isActive(r.name)
        val card = RectF(16f, 166f, 394f, 326f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(if (active) "#3B0E12" else "#0F2A1B") }
        c.drawRoundRect(card, 28f, 28f, bg)

        val big = paint(unbounded, 46f, if (active) Color.parseColor("#FF5A5F") else cGreen)
        val bigText = if (active) "ТРИВОГА" else "ТИХО"
        while (big.measureText(bigText) > 350f) big.textSize -= 1f
        c.drawText(bigText, 205f, card.top + 70f, big)
        c.drawText(r?.short ?: home, 205f, card.top + 108f, paint(manBold, 28f, Color.WHITE))
        val sub = if (active) {
            val t = state.since[r!!.name] ?: 0L
            if (t > 0) "з " + SimpleDateFormat("HH:mm", Locale.US).format(Date(t)) else "оголошено зараз"
        } else "тривоги немає"
        c.drawText(sub, 205f, card.top + 140f, paint(manSemi, 20f, Color.parseColor(if (active) "#FFB3B5" else "#9FE3BD")))

        val mw = 200f
        val top = card.bottom + 18f
        drawMap(c, map, state, home, 16f, top, mw, 0.9f, 2f)
        val n = map.oblastCount(state.active)
        val tx = 16f + mw + 18f
        c.drawText(n.toString(), tx, top + 62f, paint(unbounded, 52f, if (n > 0) cAlert else cGreen, Paint.Align.LEFT))
        val lp = paint(manSemi, 18f, cMuted, Paint.Align.LEFT)
        c.drawText(plural(n, "область", "області", "областей"), tx, top + 92f, lp)
        c.drawText("у тривозі", tx, top + 116f, lp)
    }

    companion object {
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
