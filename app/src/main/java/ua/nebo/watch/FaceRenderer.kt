package ua.nebo.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        when (style) {
            Prefs.STYLE_HOME -> drawHome(c, map, st, h)
            Prefs.STYLE_NEON -> drawNeon(c, map, st, h)
            Prefs.STYLE_RADAR -> drawRadar(c, map, st, h)
            Prefs.STYLE_RING -> drawRing(c, map, st, h)
            Prefs.STYLE_TILES -> drawTiles(c, map, st, h)
            Prefs.STYLE_DUSK -> drawDusk(c, map, st, h)
            else -> drawMapFace(c, map, st, h)
        }
        c.restore()
        return bmp
    }

    private fun paint(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width; strokeJoin = Paint.Join.ROUND
    }

    /** М'яке сяйво: та сама фігура, розмита на [radius] пікселів. */
    private fun glow(base: Paint, radius: Float, alpha: Int = 255) = Paint(base).apply {
        maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        this.alpha = this.alpha * alpha / 255
    }

    private fun fit(p: Paint, text: String, maxW: Float, minSize: Float = 11f) {
        while (p.measureText(text) > maxW && p.textSize > minSize) p.textSize -= 1f
    }

    /** Базова лінія, щоб текст стояв по центру висоти [cy]. */
    private fun midBase(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f

    /** Кольори карти: заливка за рівнем, тонкі межі районів, товсті межі областей, обвідка дому. */
    private class MapPalette(val level: (Int) -> Int, val thin: Int, val thick: Int, val home: Int, val cityRing: Int)

    private val classic = MapPalette({ levelColor(it) }, Color.BLACK, Color.BLACK, Color.WHITE, Color.BLACK)

    /** Малює карту в прямокутнику шириною w з лівим верхнім кутом (x, y). Повертає висоту. */
    private fun drawMap(
        c: Canvas, map: MapData, state: AlertState, home: MapData.Home?, x: Float, y: Float, w: Float, borderW: Float, homeW: Float,
        pal: MapPalette = classic,
    ): Float {
        val s = w / map.width
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        paintMap(c, map, state, home, s, borderW, homeW, pal)
        c.restore()
        return map.height * s
    }

    /** Малює карту на полотні, вже переведеному в координати карти (s — пікселів на одиницю карти). */
    private fun paintMap(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?, s: Float, borderW: Float, homeW: Float, pal: MapPalette) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = pal.thin; strokeWidth = borderW * 0.45f / s; strokeJoin = Paint.Join.ROUND
        }
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = pal.thick; strokeWidth = borderW / s; strokeJoin = Paint.Join.ROUND
        }
        if (map.raions.isNotEmpty()) {
            for (r in map.raions) {
                fill.color = pal.level(state.raionLevel(r.index))
                c.drawPath(r.path, fill)
                c.drawPath(r.path, thin)
            }
            for (r in map.regions) r.path?.let { c.drawPath(it, thick) }
        } else {
            for (r in map.regions) {
                val p = r.path ?: continue
                fill.color = pal.level(state.oblastLevel(map, r.name))
                c.drawPath(p, fill); c.drawPath(p, thick)
            }
        }
        val homeStroke = Paint(thick).apply { color = pal.home; strokeWidth = homeW / s }
        when (home) {
            is MapData.Home.OfRaion -> c.drawPath(home.raion.path, homeStroke)
            is MapData.Home.OfRegion -> home.region.path?.let { c.drawPath(it, homeStroke) }
            null -> {}
        }
        for (r in map.regions) {
            if (!r.isCity) continue
            val rad = (if (r.name == "м. Київ") 4.4f else 3f) / s
            fill.color = pal.level(state.cityLevel(r.name))
            c.drawCircle(r.cx, r.cy, rad, fill)
            val isHome = home is MapData.Home.OfRegion && home.region.name == r.name
            val st = if (isHome) homeStroke else Paint(thick).apply { color = pal.cityRing; strokeWidth = 1.4f / s }
            c.drawCircle(r.cx, r.cy, rad, st)
        }
    }

    /** Контур дому в координатах карти (для міст — коло навколо точки). */
    private fun homePath(home: MapData.Home?, s: Float): Path? = when (home) {
        is MapData.Home.OfRaion -> home.raion.path
        is MapData.Home.OfRegion -> home.region.path ?: Path().apply {
            addCircle(home.region.cx, home.region.cy, (if (home.region.name == "м. Київ") 4.4f else 3f) / s, Path.Direction.CW)
        }
        null -> null
    }

    /** Об'єднані контури районів кожного рівня — для сяйва під тривожними районами. */
    private fun levelPaths(map: MapData, state: AlertState, minLevel: Int): Map<Int, Path> {
        val out = HashMap<Int, Path>()
        for (r in map.raions) {
            val lv = state.raionLevel(r.index)
            if (lv >= minLevel) out.getOrPut(lv) { Path().apply { fillType = Path.FillType.EVEN_ODD } }.addPath(r.path)
        }
        return out
    }

    private fun drawMapFace(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val mh = drawMap(c, map, state, home, 14f, 172f, 382f, 1.3f, 2.4f)
        val short = home?.short ?: "—"
        val level = state.homeLevel(map, home)
        val (red, yellow) = state.counts(map)
        val y = 172f + mh + 16f
        if (level >= AlertState.YELLOW) {
            drawStatusPill(c, y, level, short)
        } else {
            val main = summary(red, yellow)
            val p1 = paint(manBold, 21f, if (red > 0 || yellow > 0) Color.WHITE else cGreen)
            c.drawText(main, 205f, y + 22f, p1)
            val sub = if (red > 0 && yellow > 0) "загроза ще в $yellow · $short — тихо" else "$short — тихо"
            val p2 = paint(manSemi, 17f, cMuted)
            while (p2.measureText(sub) > 380f && p2.textSize > 11f) p2.textSize -= 1f
            c.drawText(sub, 205f, y + 48f, p2)
        }
    }

    /** Плашка «ТРИВОГА · КИЇВЩИНА» (жовта, червона, а для тиші — зелена). */
    private fun drawStatusPill(c: Canvas, y: Float, level: Int, short: String) {
        val word = statusWord(level)
        val text = word + " · " + short.uppercase(Locale("uk"))
        val fg = when {
            level >= AlertState.PARTIAL -> Color.WHITE
            level == AlertState.YELLOW -> Color.parseColor("#1A1300")
            else -> cGreen
        }
        val p = paint(unbounded, 19f, fg)
        while (p.measureText(text) > 354f && p.textSize > 11f) p.textSize -= 1f
        val tw = p.measureText(text)
        val bg = when {
            level >= AlertState.PARTIAL -> levelColor(AlertState.RED)
            level == AlertState.YELLOW -> levelColor(AlertState.YELLOW)
            else -> Color.parseColor("#0F2A1B")
        }
        c.drawRoundRect(RectF(205f - tw / 2 - 18f, y, 205f + tw / 2 + 18f, y + 42f), 21f, 21f, fill(bg))
        c.drawText(text, 205f, y + 21f - (p.descent() + p.ascent()) / 2f, p)
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
        c.drawText(homeSub(map, state, home, level), 205f, card.top + 140f, paint(manSemi, 20f, Color.parseColor(subCol)))

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

    // ---------------- «Неон» ----------------

    private val neon = MapPalette(
        {
            when (it) {
                AlertState.RED -> Color.parseColor("#FF2E57")
                AlertState.PARTIAL -> Color.parseColor("#B81F45")
                AlertState.YELLOW -> Color.parseColor("#FFD23F")
                AlertState.RECENT -> Color.parseColor("#4A1A35")
                else -> Color.parseColor("#0B1222")
            }
        },
        thin = Color.parseColor("#14203A"), thick = Color.parseColor("#3FA7FF"), home = Color.WHITE, cityRing = Color.parseColor("#05080F"),
    )

    private fun neonStatusColor(level: Int) = when {
        level >= AlertState.PARTIAL -> Color.parseColor("#FF2E57")
        level == AlertState.YELLOW -> Color.parseColor("#FFD23F")
        else -> Color.parseColor("#3DFFA2")
    }

    /** Карта з неоновими контурами: тривожні райони світяться, межі областей — блакитні «трубки». */
    private fun drawNeon(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        c.drawRect(0f, 0f, 410f, 502f, Paint().apply {
            shader = RadialGradient(205f, 310f, 250f, intArrayOf(Color.parseColor("#0B1A38"), Color.BLACK), null, Shader.TileMode.CLAMP)
        })
        val x = 14f; val y = 174f; val w = 382f
        val s = w / map.width
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        // сяйво під тривожними районами
        for ((lv, p) in levelPaths(map, state, AlertState.YELLOW)) {
            c.drawPath(p, glow(fill(neon.level(lv)), 9f / s, 200))
        }
        paintMap(c, map, state, home, s, 1.1f, 2.4f, neon)
        // світіння меж областей поверх карти
        val borders = Path()
        for (r in map.regions) r.path?.let { borders.addPath(it) }
        c.drawPath(borders, glow(stroke(neon.thick, 2.2f / s), 3f / s, 110))
        homePath(home, s)?.let { c.drawPath(it, glow(stroke(Color.WHITE, 3f / s), 5f / s, 230)) }
        c.restore()

        val mh = map.height * s
        val level = state.homeLevel(map, home)
        val col = neonStatusColor(level)
        val text = statusWord(level) + " · " + (home?.short ?: "—").uppercase(Locale("uk"))
        val p = paint(unbounded, 19f, col).apply { letterSpacing = 0.04f }
        fit(p, text, 340f)
        val tw = p.measureText(text)
        val top = y + mh + 14f
        val pill = RectF(205f - tw / 2 - 20f, top, 205f + tw / 2 + 20f, top + 44f)
        c.drawRoundRect(pill, 22f, 22f, fill(col).apply { alpha = 30 })
        val tube = stroke(col, 2.4f)
        c.drawRoundRect(pill, 22f, 22f, glow(tube, 7f))
        c.drawRoundRect(pill, 22f, 22f, tube)
        val base = midBase(p, pill.centerY())
        c.drawText(text, 205f, base, glow(p, 6f))
        c.drawText(text, 205f, base, p)
    }

    // ---------------- «Радар» ----------------

    private val radarGreen = Color.parseColor("#3DFFA2")

    private val radar = MapPalette(
        {
            when (it) {
                AlertState.RED -> Color.parseColor("#FF3B3B")
                AlertState.PARTIAL -> Color.parseColor("#B52A2E")
                AlertState.YELLOW -> Color.parseColor("#FFC82E")
                AlertState.RECENT -> Color.parseColor("#4E2A2F")
                else -> Color.parseColor("#0E2C21")
            }
        },
        thin = Color.parseColor("#06170F"), thick = Color.parseColor("#1F6B4B"), home = Color.WHITE, cityRing = Color.parseColor("#06170F"),
    )

    /** Екран радара: ваш район у центрі, навколо — сусіди, кільця дальності й промінь. */
    private fun drawRadar(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val cx = 205f; val cy = 298f; val rr = 122f
        val circle = Path().apply { addCircle(cx, cy, rr, Path.Direction.CW) }

        // Що показуємо: область дому (для міста — сусідня область), центр — сам дім
        val focus: Path? = when (home) {
            is MapData.Home.OfRaion -> map.find(home.raion.oblast)?.path
            is MapData.Home.OfRegion -> home.region.path ?: when (home.region.name) {
                "м. Київ" -> map.find("Київська область")?.path
                else -> map.find("Автономна Республіка Крим")?.path
            }
            null -> null
        }
        val fb = RectF().also { focus?.computeBounds(it, true) }
        // Центр: район чи місто — саме воно; уся область — середина її меж (видовжені області цілком у колі)
        val (hx, hy) = when (home) {
            is MapData.Home.OfRaion -> home.raion.cx to home.raion.cy
            is MapData.Home.OfRegion -> if (home.region.path != null) fb.centerX() to fb.centerY() else home.region.cx to home.region.cy
            null -> map.width / 2f to map.height / 2f
        }
        // область займає ~60% діаметра, але не надто велике збільшення для малих областей
        val z = if (focus != null) min(2f * rr * 0.6f / max(fb.width(), fb.height()), 2f * rr / 170f) else 2f * rr / map.width

        c.save()
        c.clipPath(circle)
        c.drawCircle(cx, cy, rr, Paint().apply {
            shader = RadialGradient(cx, cy, rr, intArrayOf(Color.parseColor("#0A2419"), Color.parseColor("#03100A")), null, Shader.TileMode.CLAMP)
        })
        c.save()
        c.translate(cx, cy)
        c.scale(z, z)
        c.translate(-hx, -hy)
        for ((lv, p) in levelPaths(map, state, AlertState.YELLOW)) c.drawPath(p, glow(fill(radar.level(lv)), 8f / z, 150))
        paintMap(c, map, state, home, z, 1.2f, 2.6f, radar)
        c.restore()
        // легке затемнення до країв, ніби скло екрана
        c.drawCircle(cx, cy, rr, Paint().apply {
            shader = RadialGradient(cx, cy, rr, intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        })
        // промінь зі шлейфом
        c.save()
        c.rotate(-58f, cx, cy)
        val trail = withAlpha(radarGreen, 0)
        c.drawCircle(cx, cy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(cx, cy, intArrayOf(trail, trail, withAlpha(radarGreen, 0x70)), floatArrayOf(0f, 0.8f, 1f))
        })
        val beam = stroke(radarGreen, 2f)
        c.drawLine(cx, cy, cx + rr, cy, glow(beam, 4f))
        c.drawLine(cx, cy, cx + rr, cy, beam)
        c.restore()
        c.restore()

        // кільця дальності, перехрестя й поділки
        val grid = stroke(withAlpha(radarGreen, 0x40), 1f)
        c.drawCircle(cx, cy, rr / 3f, grid)
        c.drawCircle(cx, cy, rr * 2f / 3f, grid)
        c.drawLine(cx - rr, cy, cx + rr, cy, grid)
        c.drawLine(cx, cy - rr, cx, cy + rr, grid)
        val rim = stroke(radarGreen, 2f)
        c.drawCircle(cx, cy, rr, glow(rim, 6f, 160))
        c.drawCircle(cx, cy, rr, rim)
        val tick = stroke(withAlpha(radarGreen, 0x80), 1.5f)
        for (a in 0 until 360 step 10) {
            val rad = Math.toRadians(a.toDouble())
            val r1 = rr + 5f
            val r2 = rr + if (a % 30 == 0) 13f else 9f
            c.drawLine(cx + r1 * cos(rad).toFloat(), cy + r1 * sin(rad).toFloat(), cx + r2 * cos(rad).toFloat(), cy + r2 * sin(rad).toFloat(), tick)
        }
        // точка «ви тут»
        c.drawCircle(cx, cy, 10f, stroke(withAlpha(Color.WHITE, 0x70), 1.5f))
        c.drawCircle(cx, cy, 4f, glow(fill(Color.WHITE), 4f))
        c.drawCircle(cx, cy, 3.5f, fill(Color.WHITE))

        val level = state.homeLevel(map, home)
        val word = statusWord(level)
        val wp = paint(unbounded, 21f, radarStatusColor(level), Paint.Align.LEFT)
        val name = "  " + (home?.short ?: "—")
        val np = paint(manBold, 21f, Color.WHITE, Paint.Align.LEFT)
        while (wp.measureText(word) + np.measureText(name) > 370f && np.textSize > 12f) { np.textSize -= 1f; wp.textSize -= 1f }
        val total = wp.measureText(word) + np.measureText(name)
        c.drawText(word, 205f - total / 2f, 466f, wp)
        c.drawText(name, 205f - total / 2f + wp.measureText(word), 466f, np)
        val (red, yellow) = state.counts(map)
        val sub = paint(manSemi, 16f, cMuted)
        val subText = summary(red, yellow).replaceFirstChar { it.lowercase() }.let { if (red == 0 && yellow == 0) "в Україні тихо" else it } +
            if (red > 0 && yellow > 0) " · жовтий ще в $yellow" else ""
        fit(sub, subText, 360f)
        c.drawText(subText, 205f, 489f, sub)
    }

    private fun radarStatusColor(level: Int) = when {
        level >= AlertState.PARTIAL -> Color.parseColor("#FF4D4D")
        level == AlertState.YELLOW -> Color.parseColor("#FFC82E")
        else -> radarGreen
    }

    // ---------------- «Кільце» ----------------

    /** Кільце з 25 сегментів — по одному на область, зафарбовані за рівнем; у центрі — статус дому. */
    private fun drawRing(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val cx = 205f; val cy = 302f; val rr = 118f; val thick = 18f
        val homeOblast = when (home) {
            is MapData.Home.OfRaion -> home.raion.oblast
            is MapData.Home.OfRegion -> when {
                !home.region.isCity -> home.region.name
                home.region.name == "м. Київ" -> "Київська область"
                else -> "Автономна Республіка Крим"
            }
            null -> null
        }
        val oblasts = map.raionsByOblast.keys.filter { it != "м. Київ" }
            .map { it to state.oblastLevel(map, it) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { map.find(it.first)?.short ?: it.first })
        val n = oblasts.size.coerceAtLeast(1)
        val step = 360f / n
        val gap = 2.6f
        val oval = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = thick; strokeCap = Paint.Cap.BUTT }
        // тонка доріжка під сегментами
        c.drawCircle(cx, cy, rr, stroke(Color.parseColor("#11141C"), thick + 8f))
        for ((i, o) in oblasts.withIndex()) {
            val start = -90f + i * step + gap / 2f
            seg.color = ringColor(o.second)
            if (o.second >= AlertState.YELLOW) c.drawArc(oval, start, step - gap, false, glow(seg, 9f, 150))
        }
        for ((i, o) in oblasts.withIndex()) {
            val start = -90f + i * step + gap / 2f
            seg.color = ringColor(o.second)
            c.drawArc(oval, start, step - gap, false, seg)
            if (o.first == homeOblast) {
                val mid = Math.toRadians((start + (step - gap) / 2f).toDouble())
                val r = rr + thick / 2f + 9f
                val mx = cx + r * cos(mid).toFloat(); val my = cy + r * sin(mid).toFloat()
                c.drawCircle(mx, my, 4.5f, glow(fill(Color.WHITE), 4f, 180))
                c.drawCircle(mx, my, 4f, fill(Color.WHITE))
            }
        }
        // внутрішнє тонке коло
        c.drawCircle(cx, cy, rr - thick / 2f - 8f, stroke(Color.parseColor("#1A1F2B"), 1.2f))

        val level = state.homeLevel(map, home)
        val (fg, subCol) = when {
            level >= AlertState.PARTIAL -> Color.parseColor("#FF5A5F") to Color.parseColor("#FFB3B5")
            level == AlertState.YELLOW -> Color.parseColor("#FFC93C") to Color.parseColor("#FFE29A")
            else -> cGreen to Color.parseColor("#9FE3BD")
        }
        val word = statusWord(level)
        val wp = paint(unbounded, 34f, fg)
        fit(wp, word, 176f)
        c.drawText(word, cx, cy - 6f, wp)
        val np = paint(manBold, 21f, Color.WHITE)
        val name = home?.short ?: "Оберіть область"
        fit(np, name, 180f, 13f)
        c.drawText(name, cx, cy + 24f, np)
        val sp = paint(manSemi, 16f, subCol)
        val sub = homeSub(map, state, home, level)
        fit(sp, sub, 170f, 11f)
        c.drawText(sub, cx, cy + 50f, sp)

        // легенда під кільцем: тривога · загроза · тихо
        val (red, yellow) = state.counts(map)
        val calm = n - red - yellow
        val items = listOf(
            Triple(ringColor(AlertState.RED), red, "тривога"),
            Triple(ringColor(AlertState.YELLOW), yellow, "загроза"),
            Triple(Color.parseColor("#3A3F4E"), calm, "тихо"),
        )
        val numP = paint(unbounded, 17f, Color.WHITE, Paint.Align.LEFT)
        val labP = paint(manSemi, 15f, cMuted, Paint.Align.LEFT)
        val widths = items.map { 14f + numP.measureText(it.second.toString()) + 6f + labP.measureText(it.third) }
        val gapX = 20f
        var lx = 205f - (widths.sum() + gapX * (items.size - 1)) / 2f
        val ly = 470f
        for ((i, it) in items.withIndex()) {
            c.drawCircle(lx + 5f, ly - 6f, 5f, fill(it.first))
            val nx = lx + 14f
            c.drawText(it.second.toString(), nx, ly, numP)
            c.drawText(it.third, nx + numP.measureText(it.second.toString()) + 6f, ly, labP)
            lx += widths[i] + gapX
        }
    }

    private fun ringColor(level: Int) = when (level) {
        AlertState.RED -> Color.parseColor("#E5383B")
        AlertState.PARTIAL -> Color.parseColor("#B8282D")
        AlertState.YELLOW -> Color.parseColor("#F2B51E")
        AlertState.RECENT -> Color.parseColor("#5C3039")
        else -> Color.parseColor("#262B38")
    }

    // ---------------- «Плитки» ----------------

    private class Tile(val region: String, val col: Int, val row: Int, val label: String)

    /** Схематична карта: кожна область — плитка приблизно на своєму місці. */
    private val tiles = listOf(
        Tile("Волинська область", 1, 0, "ВОЛ"), Tile("Рівненська область", 2, 0, "РІВ"), Tile("Житомирська область", 3, 0, "ЖИТ"),
        Tile("Київська область", 4, 0, "КИЇВ"), Tile("Чернігівська область", 5, 0, "ЧРН"), Tile("Сумська область", 6, 0, "СУМ"),
        Tile("Львівська область", 0, 1, "ЛЬВ"), Tile("Тернопільська область", 1, 1, "ТЕР"), Tile("Хмельницька область", 2, 1, "ХМЛ"),
        Tile("Вінницька область", 3, 1, "ВІН"), Tile("Черкаська область", 4, 1, "ЧРК"), Tile("Полтавська область", 5, 1, "ПОЛ"),
        Tile("Харківська область", 6, 1, "ХАР"), Tile("Луганська область", 7, 1, "ЛУГ"),
        Tile("Закарпатська область", 0, 2, "ЗАК"), Tile("Івано-Франківська область", 1, 2, "ІФР"), Tile("Чернівецька область", 2, 2, "ЧРВ"),
        Tile("Одеська область", 3, 2, "ОДЕ"), Tile("Кіровоградська область", 4, 2, "КІР"), Tile("Дніпропетровська область", 5, 2, "ДНІ"),
        Tile("Донецька область", 6, 2, "ДОН"),
        Tile("Миколаївська область", 4, 3, "МИК"), Tile("Херсонська область", 5, 3, "ХЕР"), Tile("Запорізька область", 6, 3, "ЗАП"),
        Tile("Автономна Республіка Крим", 5, 4, "КРИМ"),
    )

    private fun drawTiles(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val pitch = 46f; val size = 42f; val x0 = 23f; val y0 = 176f
        val homeOblast = when (home) {
            is MapData.Home.OfRaion -> home.raion.oblast
            is MapData.Home.OfRegion -> if (home.region.isCity) null else home.region.name
            null -> null
        }
        val homeCity = (home as? MapData.Home.OfRegion)?.region?.takeIf { it.isCity }?.name
        val label = paint(manBold, 13f, Color.WHITE).apply { letterSpacing = 0.03f }
        for (t in tiles) {
            var lv = state.oblastLevel(map, t.region)
            if (t.region == "Автономна Республіка Крим") lv = max(lv, state.cityLevel("Севастополь"))
            val r = RectF(x0 + t.col * pitch, y0 + t.row * pitch, x0 + t.col * pitch + size, y0 + t.row * pitch + size)
            val base = levelColor(lv)
            if (lv >= AlertState.PARTIAL) c.drawRoundRect(r, 11f, 11f, glow(fill(base), 8f, 140))
            c.drawRoundRect(r, 11f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, r.top, 0f, r.bottom, lighten(base, 0.10f), base, Shader.TileMode.CLAMP)
            })
            label.color = when (lv) {
                AlertState.RED -> Color.WHITE
                AlertState.PARTIAL -> Color.parseColor("#FFD9DA")
                AlertState.YELLOW -> Color.parseColor("#2A1F00")
                AlertState.RECENT -> Color.parseColor("#E6BCC4")
                else -> Color.parseColor("#7F8598")
            }
            label.textSize = 13f
            fit(label, t.label, size - 6f, 9f)
            c.drawText(t.label, r.centerX(), midBase(label, r.centerY()), label)
            if (t.region == homeOblast) {
                val hr = RectF(r).apply { inset(-3f, -3f) }
                c.drawRoundRect(hr, 13f, 13f, glow(stroke(Color.WHITE, 2.5f), 4f, 150))
                c.drawRoundRect(hr, 13f, 13f, stroke(Color.WHITE, 2.5f))
            }
            if (t.region == "Київська область") {
                // Київ — крапка в куті плитки області
                val cl = state.cityLevel("м. Київ")
                val dx = r.right - 7f; val dy = r.top + 7f
                c.drawCircle(dx, dy, 5f, fill(levelColor(cl)))
                c.drawCircle(dx, dy, 5f, stroke(if (homeCity == "м. Київ") Color.WHITE else Color.BLACK, if (homeCity == "м. Київ") 2f else 1.5f))
            }
            if (t.region == "Автономна Республіка Крим" && homeCity == "Севастополь") {
                val hr = RectF(r).apply { inset(-3f, -3f) }
                c.drawRoundRect(hr, 13f, 13f, stroke(Color.WHITE, 2.5f))
            }
        }

        // У вільному куті під заходом — кількість областей у тривозі
        val (red, yellow) = state.counts(map)
        val bx = x0 + 2f
        val by = y0 + 3 * pitch
        c.drawText(red.toString(), bx, by + 40f, paint(unbounded, 40f, if (red > 0) levelColor(AlertState.RED) else cGreen, Paint.Align.LEFT))
        c.drawText(plural(red, "область", "області", "областей") + " у тривозі", bx, by + 62f, paint(manSemi, 15f, cMuted, Paint.Align.LEFT))
        if (yellow > 0) c.drawText("+$yellow жовтий рівень", bx, by + 82f, paint(manBold, 14f, levelColor(AlertState.YELLOW), Paint.Align.LEFT))

        drawStatusPill(c, y0 + 5 * pitch + 14f, state.homeLevel(map, home), home?.short ?: "—")
    }

    // ---------------- «Сутінки» ----------------

    private val dusk = MapPalette(
        {
            when (it) {
                AlertState.RED -> Color.parseColor("#FF3D55")
                AlertState.PARTIAL -> Color.parseColor("#B02A44")
                AlertState.YELLOW -> Color.parseColor("#FFBE3B")
                AlertState.RECENT -> Color.parseColor("#4E2648")
                else -> Color.parseColor("#151C3F")
            }
        },
        thin = Color.parseColor("#0B1030"), thick = Color.parseColor("#070A20"), home = Color.WHITE, cityRing = Color.parseColor("#070A20"),
    )

    /** Вечірнє небо із зорями; колір обрію — за статусом дому, карта внизу — як вогні міст. */
    private fun drawDusk(c: Canvas, map: MapData, state: AlertState, home: MapData.Home?) {
        val level = state.homeLevel(map, home)
        val (horizon, haze, accent) = when {
            level >= AlertState.PARTIAL -> Triple("#4A0C1E", "#FF3D55", "#FF8A98")
            level == AlertState.YELLOW -> Triple("#4A3208", "#FFB13B", "#FFD58A")
            else -> Triple("#2A1A52", "#C77DFF", "#C9B8FF")
        }
        c.drawRect(0f, 0f, 410f, 502f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 502f,
                intArrayOf(Color.parseColor("#02030A"), Color.parseColor("#070C24"), Color.parseColor("#101A45"), Color.parseColor(horizon)),
                floatArrayOf(0f, 0.32f, 0.62f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 200f, 410f, 502f, Paint().apply {
            shader = RadialGradient(205f, 560f, 330f, intArrayOf(withAlpha(Color.parseColor(haze), 0x80), withAlpha(Color.parseColor(haze), 0)), null, Shader.TileMode.CLAMP)
        })
        // зорі (завжди ті самі — щоб циферблат не «мерехтів» між оновленнями)
        val rnd = java.util.Random(1991)
        val star = fill(Color.WHITE)
        repeat(70) {
            val sx = rnd.nextFloat() * 410f
            val sy = rnd.nextFloat() * 330f
            val r = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.3f
            star.color = if (rnd.nextInt(5) == 0) Color.parseColor("#BFD3FF") else Color.WHITE
            // ближче до обрію зорі тьмяніші
            val fade = (1f - sy / 420f).coerceIn(0.3f, 1f)
            star.alpha = ((60 + rnd.nextInt(170)) * fade).toInt()
            c.drawCircle(sx, sy, r, star)
        }
        for ((sx, sy) in listOf(48f to 196f, 362f to 172f, 330f to 238f)) {
            val ray = stroke(withAlpha(Color.WHITE, 0x90), 1f)
            c.drawLine(sx - 6f, sy, sx + 6f, sy, ray)
            c.drawLine(sx, sy - 6f, sx, sy + 6f, ray)
            c.drawCircle(sx, sy, 2.2f, glow(fill(Color.WHITE), 3f))
            c.drawCircle(sx, sy, 1.4f, fill(Color.WHITE))
        }

        // статус між часом і картою
        val word = statusWord(level)
        // Android додає міжлітерний інтервал порівну з обох боків літери, тож центр не зсувається
        val wp = paint(unbounded, 24f, Color.parseColor(accent)).apply { letterSpacing = 0.22f }
        c.drawText(word, 205f, 203f, glow(wp, 8f, 150))
        c.drawText(word, 205f, 203f, wp)
        val sub = (home?.short ?: "—") + " · " + homeSub(map, state, home, level)
        val sp = paint(manSemi, 16f, withAlpha(Color.WHITE, 0xC0))
        fit(sp, sub, 360f)
        c.drawText(sub, 205f, 228f, sp)

        // карта як земля під небом: контурне світло обрію і вогні тривог
        val x = 30f; val y = 246f; val w = 350f
        val s = w / map.width
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        val land = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (r in map.regions) r.path?.let { land.addPath(it) }
        c.drawPath(land, glow(stroke(Color.parseColor(haze), 6f / s), 10f / s, 120))
        for ((lv, p) in levelPaths(map, state, AlertState.YELLOW)) c.drawPath(p, glow(fill(dusk.level(lv)), 10f / s, 190))
        paintMap(c, map, state, home, s, 1.0f, 2.2f, dusk)
        homePath(home, s)?.let { c.drawPath(it, glow(stroke(Color.WHITE, 3f / s), 5f / s, 200)) }
        c.restore()
    }

    // ---------------- спільне ----------------

    private fun statusWord(level: Int) = when {
        level >= AlertState.PARTIAL -> "ТРИВОГА"
        level == AlertState.YELLOW -> "ЗАГРОЗА"
        else -> "ТИХО"
    }

    /** «дрони · з 14:32», «частина області · з 03:10», «щойно відбій», «тривоги немає». */
    private fun homeSub(map: MapData, state: AlertState, home: MapData.Home?, level: Int): String {
        val t = state.homeSince(map, home)
        val partial = state.homePartial(map, home)
        return when {
            level >= AlertState.YELLOW -> {
                val kind = if (level == AlertState.YELLOW) "дрони" else if (level == AlertState.PARTIAL || partial) "частина області" else ""
                val time = if (t > 0) "з " + sinceText(t) else ""
                listOf(kind, time).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "оголошено зараз" }
            }
            level == AlertState.RECENT -> "щойно відбій"
            else -> "тривоги немає"
        }
    }

    private fun summary(red: Int, yellow: Int) =
        if (red > 0) "Тривога в $red " + plural(red, "області", "областях", "областях")
        else if (yellow > 0) "Жовтий рівень в $yellow " + plural(yellow, "області", "областях", "областях")
        else "Тривог немає"

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private fun lighten(color: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    companion object {
        fun levelColor(level: Int): Int = when (level) {
            AlertState.RED -> Color.parseColor("#E5383B")
            AlertState.PARTIAL -> Color.parseColor("#A9282C")
            AlertState.YELLOW -> Color.parseColor("#F2B51E")
            AlertState.RECENT -> Color.parseColor("#5C3039")
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
