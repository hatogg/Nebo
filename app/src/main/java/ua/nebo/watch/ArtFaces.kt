package ua.nebo.watch

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Декоративні циферблати без карти тривог. Макет 410×502, верх (~150 px) лишаємо спокійним:
 * там годинник сам малює час і дату. Усе випадкове — з фіксованим зерном, тож картинка щоразу однакова.
 */
class ArtFaces {

    fun draw(c: Canvas, style: String) {
        when (style) {
            Prefs.STYLE_AURORA -> aurora(c)
            Prefs.STYLE_RETRO -> retro(c)
            Prefs.STYLE_MOUNTAINS -> mountains(c)
            Prefs.STYLE_VYSHYVANKA -> vyshyvanka(c)
            else -> cosmos(c)
        }
    }

    // ---------------- допоміжне ----------------

    private fun col(hex: String) = Color.parseColor(hex)
    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun blurred(p: Paint, r: Float) = p.apply { maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) }
    private fun vertical(top: Float, bottom: Float, colors: IntArray, pos: FloatArray? = null) =
        LinearGradient(0f, top, 0f, bottom, colors, pos, Shader.TileMode.CLAMP)
    private fun radial(cx: Float, cy: Float, r: Float, colors: IntArray, pos: FloatArray? = null) =
        RadialGradient(cx, cy, r, colors, pos, Shader.TileMode.CLAMP)

    private fun background(c: Canvas, shader: Shader) = c.drawRect(0f, 0f, 410f, 502f, Paint().apply { this.shader = shader })

    /** Зорі: дрібні, тьмяніші донизу; кілька яскравих — із сяйвом. */
    private fun stars(c: Canvas, seed: Long, count: Int, yMax: Float, bright: Int = 0, tint: Int = col("#C7D6FF")) {
        val rnd = Random(seed)
        val p = fill(Color.WHITE)
        repeat(count) {
            val x = rnd.nextFloat() * 410f
            val y = rnd.nextFloat() * yMax
            val r = 0.45f + rnd.nextFloat() * rnd.nextFloat() * 1.25f
            val fade = (1f - y / (yMax * 1.25f)).coerceIn(0.25f, 1f)
            p.color = withAlpha(if (rnd.nextInt(4) == 0) tint else Color.WHITE, ((70 + rnd.nextInt(170)) * fade).toInt())
            c.drawCircle(x, y, r, p)
        }
        repeat(bright) {
            val x = 20f + rnd.nextFloat() * 370f
            val y = 30f + rnd.nextFloat() * (yMax - 30f)
            sparkle(c, x, y, 5f + rnd.nextFloat() * 4f)
        }
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, len: Float) {
        val ray = stroke(withAlpha(Color.WHITE, 0x80), 1f)
        c.drawLine(x - len, y, x + len, y, ray)
        c.drawLine(x, y - len, x, y + len, ray)
        c.drawCircle(x, y, 2.4f, blurred(fill(Color.WHITE), 3f))
        c.drawCircle(x, y, 1.3f, fill(Color.WHITE))
    }

    /** Лінія хребта: зміщення середньої точки, 65 точок від x=-10 до x=420. */
    private fun ridge(seed: Long, base: Float, amp: Float, rough: Float = 0.52f): FloatArray {
        val rnd = Random(seed)
        val n = 64
        val h = FloatArray(n + 1)
        h[0] = base + (rnd.nextFloat() - 0.5f) * amp
        h[n] = base + (rnd.nextFloat() - 0.5f) * amp
        var step = n
        var a = amp
        while (step > 1) {
            val half = step / 2
            var i = half
            while (i < n) {
                h[i] = (h[i - half] + h[i + half]) / 2f + (rnd.nextFloat() - 0.5f) * a
                i += step
            }
            a *= rough
            step = half
        }
        return h
    }

    private fun ridgeX(i: Int) = -10f + i * (430f / 64f)

    /** Висота хребта в точці x (лінійно між вузлами). */
    private fun ridgeAt(h: FloatArray, x: Float): Float {
        val f = ((x + 10f) / (430f / 64f)).coerceIn(0f, 63.999f)
        val i = f.toInt()
        return h[i] + (h[i + 1] - h[i]) * (f - i)
    }

    private fun ridgePath(h: FloatArray, bottom: Float = 520f) = Path().apply {
        moveTo(-10f, bottom)
        for (i in h.indices) lineTo(ridgeX(i), h[i])
        lineTo(420f, bottom)
        close()
    }

    /** Ялинка-смерека: вузький силует із зубчастими гілками. */
    private fun spruce(p: Path, x: Float, base: Float, h: Float) {
        val tiers = 6
        val top = base - h
        val w = h * 0.2f
        p.moveTo(x, top)
        for (i in 1..tiers) {
            val y = top + h * 0.88f * i / tiers
            val ww = w * i / tiers
            p.lineTo(x + ww, y)
            p.lineTo(x + ww * 0.45f, y - h * 0.02f)
        }
        p.lineTo(x + w * 0.12f, base)
        p.lineTo(x - w * 0.12f, base)
        for (i in tiers downTo 1) {
            val y = top + h * 0.88f * i / tiers
            val ww = w * i / tiers
            p.lineTo(x - ww * 0.45f, y - h * 0.02f)
            p.lineTo(x - ww, y)
        }
        p.close()
    }

    // ---------------- «Сяйво» ----------------

    private fun aurora(c: Canvas) {
        background(c, vertical(0f, 502f,
            intArrayOf(col("#010309"), col("#020A17"), col("#041829"), col("#07263A")), floatArrayOf(0f, 0.3f, 0.66f, 1f)))
        stars(c, 61, 110, 430f, bright = 3)
        // дві завіси: далека фіолетово-бірюзова і ближча зелена
        curtain(c, 7, base = 232f, amp = 30f, freq = 0.016f, phase = 2.4f, len = 90f, low = col("#2DE3C8"), high = col("#7B5CFF"), alpha = 70)
        curtain(c, 11, base = 304f, amp = 36f, freq = 0.011f, phase = 4.1f, len = 175f, low = col("#45FF9C"), high = col("#B05CFF"), alpha = 140)

        // далекі гори
        val far = ridge(3, 400f, 84f, 0.5f)
        c.drawPath(ridgePath(far), fill(col("#020A12")))
        val rim = Path().apply { for (i in far.indices) if (i == 0) moveTo(ridgeX(i), far[i]) else lineTo(ridgeX(i), far[i]) }
        c.drawPath(rim, blurred(stroke(withAlpha(col("#45FF9C"), 0x38), 2f), 2f))

        // ліс: задній ряд, а передній — чорний і вищий по краях
        val rnd = Random(21)
        val back = Path()
        var x = -6f
        while (x < 416f) {
            val edge = abs(x - 205f) / 205f
            spruce(back, x, 472f + rnd.nextFloat() * 8f, 34f + edge * 30f + rnd.nextFloat() * 18f)
            x += 9f + rnd.nextFloat() * 7f
        }
        c.drawPath(back, fill(col("#03131D")))
        val front = Path()
        x = -10f
        while (x < 420f) {
            val edge = abs(x - 205f) / 205f
            spruce(front, x, 506f, 26f + edge.pow(1.6f) * 100f + rnd.nextFloat() * 22f)
            x += 11f + rnd.nextFloat() * 9f
        }
        c.drawPath(front, fill(col("#010409")))
        c.drawRect(0f, 494f, 410f, 502f, fill(col("#010409")))
    }

    /** Завіса сяйва: найяскравіший нижній край, над ним — промені, що тануть угорі. */
    private fun curtain(c: Canvas, seed: Long, base: Float, amp: Float, freq: Float, phase: Float, len: Float, low: Int, high: Int, alpha: Int) {
        val rnd = Random(seed)
        fun curve(x: Float) = base + amp * sin(x * freq + phase) + amp * 0.35f * sin(x * freq * 2.7f + phase * 1.7f)
        val line = Path()
        var x = -10f
        while (x <= 420f) { if (x == -10f) line.moveTo(x, curve(x)) else line.lineTo(x, curve(x)); x += 6f }
        // розсіяне світло всередині завіси (трохи вище нижнього краю)
        c.save()
        c.translate(0f, -len * 0.32f)
        c.drawPath(line, blurred(stroke(withAlpha(low, alpha / 4), len * 0.45f), len * 0.28f))
        c.restore()
        // промені: перекриваються, тож завіса суцільна, з легкою вертикальною текстурою
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL) }
        x = -8f
        while (x < 418f) {
            val w = 3f + rnd.nextFloat() * 4f
            val y = curve(x)
            val wave = 0.5f + 0.5f * sin(x * 0.03f + phase * 2f)
            val h = len * (0.45f + 0.4f * wave + 0.15f * rnd.nextFloat())
            val a = (alpha * (0.3f + 0.45f * wave + 0.25f * rnd.nextFloat())).toInt()
            ray.shader = vertical(y - h, y + 12f,
                intArrayOf(withAlpha(high, 0), withAlpha(high, a * 9 / 20), withAlpha(low, a * 4 / 5), withAlpha(low, a), withAlpha(low, 0)),
                floatArrayOf(0f, 0.35f, 0.7f, 0.88f, 1f))
            c.drawRect(x, y - h, x + w, y + 12f, ray)
            x += w * 0.55f + rnd.nextFloat() * 1.5f
        }
        // яскравий нижній край
        c.drawPath(line, blurred(stroke(withAlpha(low, alpha * 3 / 5), 4f), 6f))
    }

    // ---------------- «Ретро» ----------------

    private fun retro(c: Canvas) {
        val horizon = 322f
        val sky = vertical(0f, horizon,
            intArrayOf(col("#06011A"), col("#1A0640"), col("#55126B"), col("#C22C6E")), floatArrayOf(0f, 0.42f, 0.78f, 1f))
        background(c, sky)
        stars(c, 5, 60, 230f, bright = 2, tint = col("#FFC2F0"))

        // сонце зі смугами
        val cx = 205f; val cy = horizon - 8f; val r = 98f
        c.drawCircle(cx, cy, r + 14f, blurred(fill(withAlpha(col("#FF4F9A"), 0x90)), 30f))
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = vertical(cy - r, cy + r * 0.35f, intArrayOf(col("#FFF07A"), col("#FFA24C"), col("#FF3F8E")), floatArrayOf(0f, 0.5f, 1f))
        })
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        val gapPaint = Paint().apply { shader = sky }
        var y = cy - 34f
        var t = 2f
        while (y < cy + r) {
            c.drawRect(cx - r, y, cx + r, y + t, gapPaint)
            y += 15f
            t += 1.7f
        }
        c.restore()

        // гори по боках, з неоновим контуром
        for ((seed, base, color, rimA) in listOf(Quad(17L, 278f, col("#2C0A4E"), 0x90), Quad(29L, 300f, col("#16052E"), 0xE0))) {
            val h = ridge(seed, base, 60f, 0.55f)
            for (i in h.indices) {
                val side = (abs(ridgeX(i) - 205f) / 205f).coerceIn(0f, 1f)
                h[i] = horizon - (horizon - h[i]) * side.pow(1.15f) * 1.25f
            }
            c.drawPath(ridgePath(h, horizon + 1f), fill(color))
            val rim = Path().apply { for (i in h.indices) if (i == 0) moveTo(ridgeX(i), h[i]) else lineTo(ridgeX(i), h[i]) }
            c.drawPath(rim, blurred(stroke(withAlpha(col("#FF4FD8"), rimA), 2.5f), 4f))
            c.drawPath(rim, stroke(withAlpha(col("#FF8BE6"), rimA), 1.2f))
        }

        // підлога з перспективною сіткою
        c.drawRect(0f, horizon, 410f, 502f, Paint().apply { shader = vertical(horizon, 502f, intArrayOf(col("#1A0434"), col("#07010F"))) })
        c.save()
        c.clipRect(0f, horizon, 410f, 502f)
        val grid = Path()
        for (i in -14..14) grid.apply { moveTo(cx, horizon); lineTo(cx + i * 70f, 560f) }
        var d = 5f
        while (horizon + d < 510f) { grid.moveTo(0f, horizon + d); grid.lineTo(410f, horizon + d); d *= 1.38f; d += 3f }
        c.drawPath(grid, blurred(stroke(withAlpha(col("#FF2BD6"), 0xB0), 3f), 4f))
        c.drawPath(grid, stroke(withAlpha(col("#FF7BEA"), 0xE0), 1.2f))
        // сітка розчиняється біля обрію
        c.drawRect(0f, horizon, 410f, horizon + 60f, Paint().apply {
            shader = vertical(horizon, horizon + 60f, intArrayOf(col("#1A0434"), withAlpha(col("#1A0434"), 0)))
        })
        c.restore()
        c.drawLine(0f, horizon, 410f, horizon, blurred(stroke(col("#FF6FD8"), 3f), 5f))
        c.drawLine(0f, horizon, 410f, horizon, stroke(col("#FFC2F2"), 1.2f))
    }

    // ---------------- «Карпати» ----------------

    private fun mountains(c: Canvas) {
        background(c, vertical(0f, 380f,
            intArrayOf(col("#090D26"), col("#222556"), col("#5E437A"), col("#C66E72"), col("#F3A867")), floatArrayOf(0f, 0.32f, 0.55f, 0.78f, 1f)))
        stars(c, 77, 45, 170f)
        // сонце сідає за хребти
        val sx = 292f; val sy = 306f
        c.drawCircle(sx, sy, 70f, blurred(fill(withAlpha(col("#FFB46B"), 0x90)), 34f))
        c.drawCircle(sx, sy, 28f, fill(col("#FFE6B8")))
        // птахи
        val bird = stroke(withAlpha(col("#1B1430"), 0xC0), 1.6f)
        for ((bx, by, s) in listOf(Triple(112f, 238f, 7f), Triple(128f, 230f, 5f), Triple(140f, 241f, 4f))) {
            c.drawPath(Path().apply { moveTo(bx - s, by - s * 0.5f); quadTo(bx - s * 0.4f, by - s * 0.7f, bx, by); quadTo(bx + s * 0.4f, by - s * 0.7f, bx + s, by - s * 0.5f) }, bird)
        }
        // шари хребтів: від далеких і туманних до близьких і темних; на ближчих — ліс
        val layers = listOf(
            Quad(292f, 120f, "#A9718F", 0x40), Quad(328f, 110f, "#7C5383", 0x38), Quad(364f, 96f, "#523C6E", 0x30),
            Quad(400f, 78f, "#33284F", 0x26), Quad(438f, 58f, "#1D1834", 0x1A),
        )
        for ((i, l) in layers.withIndex()) {
            val (base, amp, color, fogA) = l
            val h = ridge(100L + i * 13L, base, amp, 0.5f)
            val path = ridgePath(h)
            if (i >= 2) {
                val trees = Random(300L + i)
                var tx = -6f
                while (tx < 416f) {
                    spruce(path, tx, ridgeAt(h, tx) + 2f, (3f + trees.nextFloat() * 4f) * (0.7f + i * 0.25f))
                    tx += 2.5f + trees.nextFloat() * 3f
                }
            }
            c.drawPath(path, fill(col(color)))
            // туман у долині під хребтом — без різких країв
            val top = base - amp * 0.05f
            c.drawRect(0f, top, 410f, top + 72f, Paint().apply {
                shader = vertical(top, top + 72f, intArrayOf(withAlpha(col("#F0A9A0"), 0), withAlpha(col("#F0A9A0"), fogA), withAlpha(col("#F0A9A0"), 0)), floatArrayOf(0f, 0.45f, 1f))
            })
        }
        // передній схил зі смереками
        val near = ridge(211, 474f, 34f, 0.5f)
        val front = ridgePath(near)
        val rnd = Random(9)
        var x = -6f
        while (x < 416f) {
            spruce(front, x, ridgeAt(near, x) + 6f, 22f + rnd.nextFloat() * 32f)
            x += 7f + rnd.nextFloat() * 9f
        }
        c.drawPath(front, fill(col("#0B0916")))
    }

    // ---------------- «Вишиванка» ----------------

    private val red = col("#D0232B")
    private val darkRed = col("#7E1220")
    private val cream = col("#EFE4CC")
    private val gold = col("#D9A132")

    private fun vyshyvanka(c: Canvas) {
        background(c, vertical(0f, 502f, intArrayOf(col("#16121A"), col("#0E0B10"))))
        // ледь помітне полотно
        val weave = stroke(withAlpha(Color.WHITE, 0x07), 1f).apply { strokeCap = Paint.Cap.BUTT }
        var g = 0f
        while (g < 502f) { c.drawLine(0f, g, 410f, g, weave); g += 4f }
        g = 0f
        while (g < 410f) { c.drawLine(g, 0f, g, 502f, weave); g += 4f }

        // тонка кайма під датою
        band(c, top = 170f, cell = 5f, rows = 7) { u, v -> zigzagBand(u, v) }
        // восьмипелюсткова «рожа» в центрі
        rose(c, cx = 205f, cy = 296f, cell = 6.6f)
        // широка кайма внизу
        band(c, top = 398f, cell = 6.2f, rows = 13) { u, v -> diamondBand(u, v) }
    }

    /** Хрестик: спершу нижній стібок темнішим, потім верхній — як справжня вишивка. */
    private fun stitch(c: Canvas, x: Float, y: Float, s: Float, color: Int, under: Paint, over: Paint) {
        val m = s * 0.16f
        under.color = shade(color, 0.72f)
        over.color = color
        c.drawLine(x + s - m, y + m, x + m, y + s - m, under)
        c.drawLine(x + m, y + m, x + s - m, y + s - m, over)
    }

    private fun shade(color: Int, f: Float) =
        Color.argb(Color.alpha(color), (Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    /** Смуга орнаменту на всю ширину; pattern(u, v) — колір клітинки (0 — порожньо), u від центру. */
    private fun band(c: Canvas, top: Float, cell: Float, rows: Int, pattern: (Int, Int) -> Int) {
        val cols = (410f / cell).toInt() + 2
        val x0 = 205f - cell / 2f - (cols / 2) * cell
        val under = stroke(0, cell * 0.3f)
        val over = stroke(0, cell * 0.34f)
        for (j in 0 until rows) for (i in 0 until cols) {
            val colr = pattern(i - cols / 2, j - rows / 2)
            if (colr != 0) stitch(c, x0 + i * cell, top + j * cell, cell, colr, under, over)
        }
    }

    private fun zigzagBand(u: Int, v: Int): Int {
        val a = abs(v)
        if (a == 3) return darkRed
        val z = abs(((u % 6) + 6) % 6 - 3) - 1   // зигзаг: -1..2
        return when {
            v == z || v == z - 1 -> red
            a == 0 && ((u % 6) + 6) % 6 == 0 -> gold
            else -> 0
        }
    }

    private fun diamondBand(u: Int, v: Int): Int {
        val a = abs(v)
        if (a == 6) return darkRed
        if (a == 5) return if (((u % 2) + 2) % 2 == 0) red else 0
        val p = 12
        val lu = ((u + p / 2) % p + p) % p - p / 2   // -6..5, ромб у центрі кожного періоду
        val d = abs(lu) + a
        if (d <= 4) return when (d) { 4 -> red; 3 -> 0; 2 -> darkRed; 1 -> cream; else -> gold }
        // між ромбами — маленький ромбик
        val j = abs(abs(lu) - 6) + a
        return when (j) { 0 -> gold; 1 -> red; else -> 0 }
    }

    /** Восьмикутна зірка-«рожа»: квадрат і ромб разом, кількома кільцями з ажурним проміжком. */
    private fun rose(c: Canvas, cx: Float, cy: Float, cell: Float) {
        val n = 12
        fun inStar(x: Int, y: Int, r: Int) = maxOf(abs(x), abs(y)) <= (r * 0.64f).toInt() || abs(x) + abs(y) <= r
        val under = stroke(0, cell * 0.3f)
        val over = stroke(0, cell * 0.34f)
        // ледь помітне тепле сяйво під візерунком
        c.drawCircle(cx, cy, 100f, Paint().apply { shader = radial(cx, cy, 100f, intArrayOf(withAlpha(red, 0x34), withAlpha(red, 0))) })
        fun put(x: Int, y: Int, colr: Int) = stitch(c, cx + (x - 0.5f) * cell, cy + (y - 0.5f) * cell, cell, colr, under, over)
        for (y in -n..n) for (x in -n..n) {
            val colr = when {
                !inStar(x, y, 12) -> 0
                !inStar(x, y, 11) -> red          // зовнішній контур
                !inStar(x, y, 9) -> 0             // ажурний проміжок
                !inStar(x, y, 8) -> darkRed
                !inStar(x, y, 6) -> red
                !inStar(x, y, 5) -> cream
                !inStar(x, y, 3) -> darkRed
                !inStar(x, y, 2) -> gold
                !inStar(x, y, 1) -> red
                else -> cream
            }
            if (colr != 0) put(x, y, colr)
        }
        // ромбики обабіч
        for (sx in listOf(-17, 17)) for (y in -2..2) for (x in -2..2) {
            val d = abs(x) + abs(y)
            if (d == 2) put(sx + x, y, red) else if (d == 0) put(sx + x, y, gold)
        }
    }

    // ---------------- «Космос» ----------------

    private fun cosmos(c: Canvas) {
        background(c, vertical(0f, 502f, intArrayOf(col("#000003"), col("#03040F"), col("#070A1E"))))
        // туманність
        for ((x, y, r, color) in listOf(
            Quad(96f, 262f, 210f, withAlpha(col("#5B2BD6"), 0x58)), Quad(318f, 300f, 190f, withAlpha(col("#C13AE0"), 0x40)),
            Quad(220f, 205f, 150f, withAlpha(col("#1C8FBF"), 0x34)), Quad(150f, 380f, 140f, withAlpha(col("#E0457B"), 0x28)),
        )) c.drawCircle(x, y, r, Paint().apply { shader = radial(x, y, r, intArrayOf(color, withAlpha(color, 0))) })
        stars(c, 404, 170, 502f, bright = 5, tint = col("#FFD9F5"))

        // падаюча зоря
        c.drawLine(64f, 222f, 142f, 190f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(64f, 222f, 142f, 190f, withAlpha(Color.WHITE, 0), Color.WHITE, Shader.TileMode.CLAMP)
        })
        c.drawCircle(142f, 190f, 2.4f, blurred(fill(Color.WHITE), 3f))

        // місяць
        val mx = 88f; val my = 318f; val mr = 15f
        c.drawCircle(mx, my, mr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = radial(mx - 6f, my - 6f, mr * 1.6f, intArrayOf(col("#DCE3F2"), col("#8A93AC"), col("#1C2030")), floatArrayOf(0f, 0.55f, 1f))
        })

        // планета з кільцями
        val px = 288f; val py = 458f; val pr = 128f
        val tilt = -17f
        val ring = RectF(px - 222f, py - 50f, px + 222f, py + 50f)
        c.save(); c.rotate(tilt, px, py); ringArc(c, ring, 180f, 180f); c.restore()   // задня половина
        c.drawCircle(px, py, pr + 10f, blurred(fill(withAlpha(col("#FF9A6B"), 0x50)), 18f))
        val planet = Path().apply { addCircle(px, py, pr, Path.Direction.CW) }
        c.drawCircle(px, py, pr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = radial(px - 60f, py - 70f, pr * 1.7f,
                intArrayOf(col("#FFD3A1"), col("#E08A56"), col("#9C3F3B"), col("#3A1328"), col("#10050C")), floatArrayOf(0f, 0.25f, 0.5f, 0.78f, 1f))
        })
        c.save()
        c.clipPath(planet)
        c.rotate(tilt, px, py)
        val rnd = Random(12)
        var by = py - pr
        while (by < py + pr) {
            val h = 6f + rnd.nextFloat() * 16f
            val light = rnd.nextBoolean()
            c.drawRect(px - pr - 20f, by, px + pr + 20f, by + h, fill(withAlpha(if (light) col("#FFE2C0") else col("#5A1A22"), 0x18 + rnd.nextInt(0x22))))
            by += h + rnd.nextFloat() * 6f
        }
        c.restore()
        // тінь з боку, протилежного світлу
        c.drawCircle(px, py, pr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = radial(px - 70f, py - 80f, pr * 2.1f, intArrayOf(0, 0, 0xB0000000.toInt()), floatArrayOf(0f, 0.55f, 1f))
        })
        c.save(); c.rotate(tilt, px, py); ringArc(c, ring, 0f, 180f); c.restore()       // передня половина
    }

    private fun ringArc(c: Canvas, oval: RectF, start: Float, sweep: Float) {
        val bands = listOf(Triple(0f, 7f, 0x70), Triple(9f, 3f, 0x40), Triple(14f, 10f, 0x88), Triple(26f, 4f, 0x50), Triple(32f, 2f, 0x30))
        for ((inset, w, a) in bands) {
            val r = RectF(oval).apply { inset(inset + w / 2f, (inset + w / 2f) * 0.225f) }
            c.drawArc(r, start, sweep, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = w * 0.6f; color = withAlpha(col("#F2D3A6"), a)
            })
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
