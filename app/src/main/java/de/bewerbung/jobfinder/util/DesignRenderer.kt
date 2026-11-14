package de.bewerbung.jobfinder.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import de.bewerbung.jobfinder.data.DesignConfig
import de.bewerbung.jobfinder.data.DesignTemplate
import de.bewerbung.jobfinder.data.Profile
import de.bewerbung.jobfinder.util.CvParser.Item
import de.bewerbung.jobfinder.util.CvParser.Kind

/**
 * Zeichnet Lebenslauf, Anschreiben und Deckblatt im gewählten Design (DIN A4).
 * Alle Texte werden als echter Text in die PDF geschrieben – damit bleiben die Unterlagen
 * für Bewerbermanagement-Systeme lesbar und Text ist markier-/kopierbar.
 */
object DesignRenderer {

    const val W = 595f
    const val H = 842f

    data class Assets(val photo: Bitmap?, val signature: Bitmap?)

    // ---------------- Seitenausgabe ----------------

    interface PageSink {
        fun begin(): Canvas
        fun end()
    }

    /** Schreibt Seiten in ein PdfDocument. */
    class PdfSink(private val doc: PdfDocument, startPage: Int) : PageSink {
        var nextPage = startPage
            private set
        private var page: PdfDocument.Page? = null
        override fun begin(): Canvas {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, nextPage++).create())
            page = p
            p.canvas.drawColor(Color.WHITE)
            return p.canvas
        }
        override fun end() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }

    /** Zeichnet nur die erste Seite in ein Bild (für die Vorschau) und zählt alle Seiten. */
    class PreviewSink(widthPx: Int) : PageSink {
        private val scale = widthPx / W
        val bitmap: Bitmap = Bitmap.createBitmap(widthPx, (H * scale).toInt(), Bitmap.Config.ARGB_8888)
        var pages = 0
            private set
        private val dummy = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        override fun begin(): Canvas {
            pages++
            return if (pages == 1) Canvas(bitmap).apply { drawColor(Color.WHITE); scale(scale, scale) } else dummy
        }
        override fun end() {}
    }

    /** Zählt nur Seiten. */
    class CountSink : PageSink {
        var pages = 0
            private set
        private val dummy = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        override fun begin(): Canvas { pages++; return dummy }
        override fun end() {}
    }

    // ---------------- Hilfsfunktionen ----------------

    private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val sansItalic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)

    private const val DARK = 0xFF222222.toInt()
    private const val GRAY = 0xFF5F6368.toInt()

    private fun tp(size: Float, color: Int, bold: Boolean = false, italic: Boolean = false) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = when {
            bold -> sansBold
            italic -> sansItalic
            else -> sans
        }
    }

    private fun fill(color: Int) = Paint().apply { isAntiAlias = true; this.color = color; style = Paint.Style.FILL }

    /** Mischt eine Farbe mit Weiß (f = Anteil Weiß). */
    fun tint(c: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    private fun layout(
        text: CharSequence, p: TextPaint, width: Float,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, mult: Float = 1.18f
    ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(10))
        .setAlignment(align).setLineSpacing(0f, mult).setIncludePad(false).build()

    /** Zeichnet ein Layout direkt (ohne Seitenumbruch) und gibt die Höhe zurück. */
    private fun drawAt(c: Canvas, l: StaticLayout, x: Float, y: Float): Float {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    /** Fließtext über mehrere Seiten. */
    private class Flow(
        val sink: PageSink,
        val firstTop: Float,
        val nextTop: Float,
        val bottom: Float,
        val decorate: (Canvas, Int) -> Unit
    ) {
        lateinit var c: Canvas
        var page = -1
        var y = 0f

        fun start(): Flow { newPage(); return this }

        fun newPage() {
            if (page >= 0) sink.end()
            c = sink.begin()
            page++
            decorate(c, page)
            y = if (page == 0) firstTop else nextTop
        }

        private fun top() = if (page == 0) firstTop else nextTop

        fun ensure(h: Float) {
            if (y + h > bottom && y > top() + 1f) newPage()
        }

        fun draw(l: StaticLayout, x: Float) {
            for (i in 0 until l.lineCount) {
                val t = l.getLineTop(i)
                val b = l.getLineBottom(i)
                val h = (b - t).toFloat()
                ensure(h)
                c.save()
                c.translate(x, y - t)
                c.clipRect(0f, t.toFloat(), l.width.toFloat(), b.toFloat())
                l.draw(c)
                c.restore()
                y += h
            }
        }

        fun finish() = sink.end()
    }

    private fun centerCrop(bmp: Bitmap, w: Float, h: Float): Rect {
        val target = w / h
        val src = bmp.width.toFloat() / bmp.height
        return if (src > target) {
            val nw = (bmp.height * target).toInt()
            val l = (bmp.width - nw) / 2
            Rect(l, 0, l + nw, bmp.height)
        } else {
            val nh = (bmp.width / target).toInt()
            // Bei Porträts eher den oberen Teil (Gesicht) zeigen
            val t = ((bmp.height - nh) * 0.25f).toInt()
            Rect(0, t, bmp.width, t + nh)
        }
    }

    private fun drawPhotoCircle(c: Canvas, bmp: Bitmap, cx: Float, cy: Float, r: Float, ring: Int?) {
        c.save()
        val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.clipPath(path)
        val p = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        c.drawBitmap(bmp, centerCrop(bmp, 1f, 1f), RectF(cx - r, cy - r, cx + r, cy + r), p)
        c.restore()
        if (ring != null) {
            c.drawCircle(cx, cy, r, Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2.5f; color = ring })
        }
    }

    private fun drawPhotoRect(c: Canvas, bmp: Bitmap, left: Float, top: Float, w: Float, h: Float) {
        c.save()
        val path = Path().apply { addRoundRect(RectF(left, top, left + w, top + h), 6f, 6f, Path.Direction.CW) }
        c.clipPath(path)
        c.drawBitmap(bmp, centerCrop(bmp, w, h), RectF(left, top, left + w, top + h), Paint().apply { isFilterBitmap = true })
        c.restore()
    }

    private fun footer(c: Canvas, text: String, page: Int) {
        val p = tp(7.5f, GRAY)
        val l = layout("$text – Seite ${page + 1}", p, W - 80, Layout.Alignment.ALIGN_CENTER)
        drawAt(c, l, 40f, H - 30f)
    }

    // ---------------- Lebenslauf ----------------

    private class Style(
        val accent: Int,
        val light: Int,
        val headingColor: Int,
        val headingLine: Boolean,
        val timeline: Boolean
    )

    fun renderCv(sink: PageSink, cvText: String, profile: Profile, cfg: DesignConfig, assets: Assets) {
        val cv = CvParser.parse(cvText)
        val name = profile.name.ifBlank { cv.intro.firstOrNull() ?: "" }
        val contact = profile.contactLines().ifEmpty { cv.intro.drop(1).take(4) }
        val accent = if (cfg.template == DesignTemplate.SIMPLE) DARK else cfg.accent.argb
        val light = tint(accent, 0.88f)
        val profileSections = cv.sections.filter { it.kind == Kind.PROFILE }
        val others = cv.sections.filter { it.kind != Kind.PROFILE }
        when (cfg.template) {
            DesignTemplate.HEADER -> cvHeader(sink, name, profile.headline, contact, profileSections, others, accent, light, assets)
            DesignTemplate.SIDEBAR -> cvSidebar(sink, name, profile.headline, contact, profileSections, others, accent, light, assets)
            DesignTemplate.TIMELINE -> cvTimeline(sink, name, profile.headline, contact, profileSections, others, accent, light, assets)
            DesignTemplate.SIMPLE -> cvSimple(sink, name, profile.headline, contact, profileSections, others)
        }
    }

    private fun cvHeader(
        sink: PageSink, name: String, headline: String, contact: List<String>,
        profileSecs: List<CvParser.Section>, others: List<CvParser.Section>,
        accent: Int, light: Int, assets: Assets
    ) {
        val bandH = 112f
        val st = Style(accent, light, accent, headingLine = true, timeline = false)
        val flow = Flow(sink, bandH + 26f, 44f, H - 48f) { c, page ->
            if (page == 0) c.drawRect(0f, 0f, W, bandH, fill(accent))
            else {
                c.drawRect(0f, 0f, W, 14f, fill(accent))
                footer(c, "$name · Lebenslauf", page)
            }
        }.start()
        val c = flow.c
        val photoD = 80f
        val textW = W - 80f - (if (assets.photo != null) photoD + 16f else 0f)
        var y = 26f
        y += drawAt(c, layout(name, tp(24f, Color.WHITE, bold = true), textW), 40f, y)
        if (headline.isNotBlank()) y += 2f + drawAt(c, layout(headline, tp(11.5f, tint(accent, 0.75f)), textW), 40f, y + 2f)
        if (contact.isNotEmpty()) drawAt(c, layout(contact.joinToString("   ·   "), tp(8.8f, Color.WHITE), textW), 40f, y + 8f)
        assets.photo?.let { drawPhotoCircle(c, it, W - 40f - photoD / 2, bandH / 2, photoD / 2, Color.WHITE) }

        profileSecs.forEach { profileBlock(flow, it, 40f, W - 80f, st) }
        others.forEach { mainSection(flow, it, 40f, W - 80f, st) }
        flow.finish()
    }

    private fun cvSidebar(
        sink: PageSink, name: String, headline: String, contact: List<String>,
        profileSecs: List<CvParser.Section>, others: List<CvParser.Section>,
        accent: Int, light: Int, assets: Assets
    ) {
        val sideW = 178f
        val mx = sideW + 26f
        val mw = W - mx - 36f
        val st = Style(accent, light, accent, headingLine = true, timeline = false)

        // Höhe des Namensbereichs vorab bestimmen
        val nameL = layout(name, tp(22f, accent, bold = true), mw)
        val headL = if (headline.isNotBlank()) layout(headline, tp(11f, GRAY), mw) else null
        val firstTop = 44f + nameL.height + (headL?.height ?: 0) + 26f

        val flow = Flow(sink, firstTop, 44f, H - 48f) { c, page ->
            c.drawRect(0f, 0f, sideW, H, fill(light))
            if (page > 0) footer(c, "$name · Lebenslauf", page)
        }.start()
        val c = flow.c
        var hy = 44f
        hy += drawAt(c, nameL, mx, hy)
        if (headL != null) hy += drawAt(c, headL, mx, hy + 2f)
        c.drawRect(mx, hy + 10f, mx + 46f, hy + 12.5f, fill(accent))

        // Seitenleiste: Foto, Kontakt, Kenntnisse/Sprachen/… – was nicht passt, wandert in die Hauptspalte
        var sy = 36f
        val sx = 20f
        val sw = sideW - 40f
        assets.photo?.let {
            val d = 112f
            drawPhotoCircle(c, it, sideW / 2, sy + d / 2, d / 2, Color.WHITE)
            sy += d + 22f
        }
        if (contact.isNotEmpty()) {
            sy = sideHeading(c, "KONTAKT", sx, sw, sy, accent)
            contact.forEach { line -> sy += drawAt(c, layout(line, tp(8.6f, DARK), sw), sx, sy) + 3f }
            sy += 12f
        }
        val sideCandidates = others.filter { it.kind == Kind.SIDE || it.kind == Kind.PERSONAL }
        val overflow = mutableListOf<CvParser.Section>()
        val measure = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        for (s in sideCandidates) {
            val h = sideSection(measure, s, sx, sw, 0f, accent)
            if (sy + h < H - 40f) sy = sideSection(c, s, sx, sw, sy, accent) + 10f else overflow += s
        }

        profileSecs.forEach { profileBlock(flow, it, mx, mw, st) }
        others.filter { it.kind == Kind.MAIN }.forEach { mainSection(flow, it, mx, mw, st) }
        overflow.forEach { mainSection(flow, it, mx, mw, st) }
        flow.finish()
    }

    private fun cvTimeline(
        sink: PageSink, name: String, headline: String, contact: List<String>,
        profileSecs: List<CvParser.Section>, others: List<CvParser.Section>,
        accent: Int, light: Int, assets: Assets
    ) {
        val st = Style(accent, light, accent, headingLine = false, timeline = true)
        val photoW = 76f
        val textW = W - 80f - (if (assets.photo != null) photoW + 16f else 0f)
        val nameL = layout(name, tp(25f, DARK, bold = true), textW)
        val headL = if (headline.isNotBlank()) layout(headline, tp(12f, accent, bold = true), textW) else null
        val conL = if (contact.isNotEmpty()) layout(contact.joinToString("   ·   "), tp(8.8f, GRAY), textW) else null
        val headerBottom = maxOf(
            40f + nameL.height + (headL?.height ?: 0) + (conL?.height ?: 0) + 14f,
            if (assets.photo != null) 40f + 98f + 8f else 0f
        )
        val flow = Flow(sink, headerBottom + 22f, 44f, H - 48f) { c, page ->
            if (page == 0) c.drawRect(40f, headerBottom, W - 40f, headerBottom + 2.5f, fill(accent))
            else footer(c, "$name · Lebenslauf", page)
        }.start()
        val c = flow.c
        var y = 40f
        y += drawAt(c, nameL, 40f, y)
        headL?.let { y += drawAt(c, it, 40f, y + 2f) + 2f }
        conL?.let { drawAt(c, it, 40f, y + 6f) }
        assets.photo?.let { drawPhotoRect(c, it, W - 40f - photoW, 36f, photoW, 98f) }

        profileSecs.forEach { profileBlock(flow, it, 40f, W - 80f, st) }
        others.forEach { mainSection(flow, it, 40f, W - 80f, st) }
        flow.finish()
    }

    private fun cvSimple(
        sink: PageSink, name: String, headline: String, contact: List<String>,
        profileSecs: List<CvParser.Section>, others: List<CvParser.Section>
    ) {
        val st = Style(DARK, 0xFFEEEEEE.toInt(), DARK, headingLine = true, timeline = false)
        val nameL = layout(name, tp(20f, DARK, bold = true), W - 100f)
        val headL = if (headline.isNotBlank()) layout(headline, tp(11f, GRAY), W - 100f) else null
        val conL = if (contact.isNotEmpty()) layout(contact.joinToString("  |  "), tp(9f, GRAY), W - 100f) else null
        val headerBottom = 46f + nameL.height + (headL?.height ?: 0) + (conL?.height ?: 0) + 10f
        val flow = Flow(sink, headerBottom + 18f, 48f, H - 48f) { c, page ->
            if (page == 0) c.drawRect(50f, headerBottom, W - 50f, headerBottom + 0.8f, fill(DARK))
            else footer(c, "$name · Lebenslauf", page)
        }.start()
        val c = flow.c
        var y = 46f
        y += drawAt(c, nameL, 50f, y)
        headL?.let { y += drawAt(c, it, 50f, y) }
        conL?.let { drawAt(c, it, 50f, y + 4f) }
        profileSecs.forEach { profileBlock(flow, it, 50f, W - 100f, st) }
        others.forEach { mainSection(flow, it, 50f, W - 100f, st) }
        flow.finish()
    }

    /** Kurzprofil mit farbigem Balken links. */
    private fun profileBlock(f: Flow, s: CvParser.Section, x: Float, w: Float, st: Style) {
        val text = s.items.joinToString("\n") {
            when (it) {
                is Item.Para -> it.text
                is Item.Bullet -> "• " + it.text
                is Item.KeyValue -> "${it.key}: ${it.value}"
                is Item.Station -> listOf(it.date, it.title, it.sub).filter { t -> t.isNotBlank() }.joinToString(" ")
            }
        }
        if (text.isBlank()) return
        val l = layout(text, tp(10.2f, DARK, italic = true), w - 14f, mult = 1.25f)
        f.ensure(minOf(l.height.toFloat(), 120f))
        val top = f.y
        val page = f.page
        f.draw(l, x + 12f)
        if (f.page == page) f.c.drawRect(x, top, x + 3f, f.y, f.fill(st.accent))
        f.y += 16f
    }

    private fun Flow.fill(color: Int) = Paint().apply { isAntiAlias = true; this.color = color }

    private fun mainSection(f: Flow, s: CvParser.Section, x: Float, w: Float, st: Style) {
        val hp = tp(11.5f, st.headingColor, bold = true).apply { letterSpacing = 0.06f }
        val hl = layout(s.title.uppercase(), hp, w)
        f.ensure(hl.height + 48f)
        f.draw(hl, x)
        if (st.headingLine) {
            f.c.drawRect(x, f.y + 2f, x + w, f.y + 2.8f, f.fill(st.accent))
            f.y += 10f
        } else f.y += 6f

        val dateW = if (st.timeline) 0f else 92f
        for (item in s.items) {
            when (item) {
                is Item.Station -> station(f, item, x, w, dateW, st)
                is Item.Bullet -> bullet(f, item.text, x, w, tp(9.8f, DARK), st.accent)
                is Item.Para -> { f.draw(layout(item.text, tp(10f, DARK), w), x); f.y += 3f }
                is Item.KeyValue -> {
                    val sb = SpannableStringBuilder()
                    sb.append(item.key)
                    sb.setSpan(StyleSpan(Typeface.BOLD), 0, item.key.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("  –  ").append(item.value)
                    f.draw(layout(sb, tp(9.8f, DARK), w), x)
                    f.y += 2f
                }
            }
        }
        f.y += 14f
    }

    private fun station(f: Flow, s: Item.Station, x: Float, w: Float, dateW: Float, st: Style) {
        if (st.timeline) {
            val gx = x + 5f
            val cx = x + 20f
            val cw = w - 20f
            val dateL = layout(s.date, tp(8.6f, st.accent, bold = true), cw)
            val titleL = layout(s.title, tp(10.6f, DARK, bold = true), cw)
            val subL = if (s.sub.isNotBlank()) layout(s.sub, tp(9.4f, GRAY), cw) else null
            f.ensure(dateL.height + titleL.height + (subL?.height ?: 0) + 14f)
            val top = f.y
            val page = f.page
            f.c.drawCircle(gx, top + 5f, 4.2f, f.fill(st.accent))
            f.draw(dateL, cx)
            f.draw(titleL, cx)
            subL?.let { f.draw(it, cx) }
            s.bullets.forEach { bullet(f, it, cx, cw, tp(9.6f, DARK), st.accent) }
            if (f.page == page) f.c.drawRect(gx - 0.8f, top + 10f, gx + 0.8f, f.y + 6f, f.fill(st.light))
            f.y += 10f
        } else {
            val cx = x + dateW
            val cw = w - dateW
            val dateL = layout(s.date, tp(8.8f, GRAY), dateW - 8f)
            val titleL = layout(s.title.ifBlank { " " }, tp(10.6f, DARK, bold = true), cw)
            val subL = if (s.sub.isNotBlank()) layout(s.sub, tp(9.4f, st.accent), cw) else null
            f.ensure(titleL.height + (subL?.height ?: 0) + 14f)
            drawAt(f.c, dateL, x, f.y + 1f)
            f.draw(titleL, cx)
            subL?.let { f.draw(it, cx) }
            s.bullets.forEach { bullet(f, it, cx, cw, tp(9.6f, DARK), st.accent) }
            f.y += 9f
        }
    }

    private fun bullet(f: Flow, text: String, x: Float, w: Float, p: TextPaint, dot: Int) {
        val l = layout(text, p, w - 11f)
        if (l.lineCount == 0) return
        f.ensure((l.getLineBottom(0) - l.getLineTop(0)).toFloat())
        val baseline = f.y + l.getLineBaseline(0)
        f.c.drawCircle(x + 3f, baseline - p.textSize * 0.32f, 1.7f, f.fill(dot))
        f.draw(l, x + 11f)
        f.y += 1.5f
    }

    /** Abschnitt in der Seitenleiste (ohne Seitenumbruch). Gibt die neue y-Position zurück. */
    private fun sideSection(c: Canvas, s: CvParser.Section, x: Float, w: Float, y0: Float, accent: Int): Float {
        var y = sideHeading(c, s.title.uppercase(), x, w, y0, accent)
        for (item in s.items) {
            when (item) {
                is Item.KeyValue -> {
                    y += drawAt(c, layout(item.key, tp(9f, DARK, bold = true), w), x, y)
                    y += drawAt(c, layout(item.value, tp(8.5f, GRAY), w), x, y) + 4f
                }
                is Item.Bullet -> y += drawAt(c, layout("• " + item.text, tp(8.8f, DARK), w), x, y) + 2f
                is Item.Para -> y += drawAt(c, layout(item.text, tp(8.8f, DARK), w), x, y) + 2f
                is Item.Station -> {
                    y += drawAt(c, layout(item.date, tp(8.3f, GRAY), w), x, y)
                    y += drawAt(c, layout(item.title, tp(8.8f, DARK, bold = true), w), x, y) + 3f
                }
            }
        }
        return y
    }

    private fun sideHeading(c: Canvas, title: String, x: Float, w: Float, y: Float, accent: Int): Float {
        val p = tp(10f, accent, bold = true).apply { letterSpacing = 0.08f }
        val h = drawAt(c, layout(title, p, w), x, y)
        c.drawRect(x, y + h + 2f, x + 26f, y + h + 4f, fill(accent))
        return y + h + 10f
    }

    // ---------------- Anschreiben ----------------

    fun renderLetter(sink: PageSink, letterText: String, profile: Profile, cfg: DesignConfig, assets: Assets) {
        val accent = if (cfg.template == DesignTemplate.SIMPLE) DARK else cfg.accent.argb
        val light = tint(accent, 0.88f)
        val name = profile.name
        val contact = profile.contactLines()

        // Kopfbereich je Vorlage, Textbereich festlegen
        val bodyX: Float
        val bodyTop: Float
        val decorate: (Canvas, Int) -> Unit
        when (cfg.template) {
            DesignTemplate.HEADER -> {
                bodyX = 62f; bodyTop = 112f
                decorate = { c, page ->
                    if (page == 0) {
                        c.drawRect(0f, 0f, W, 82f, fill(accent))
                        drawAt(c, layout(name, tp(20f, Color.WHITE, bold = true), W - 124f), 62f, 22f)
                        drawAt(c, layout(contact.joinToString("   ·   "), tp(8.5f, Color.WHITE), W - 124f), 62f, 52f)
                    } else c.drawRect(0f, 0f, W, 10f, fill(accent))
                }
            }
            DesignTemplate.SIDEBAR -> {
                bodyX = 182f; bodyTop = 64f
                decorate = { c, page ->
                    c.drawRect(0f, 0f, 150f, H, fill(light))
                    if (page == 0) {
                        var y = 40f
                        assets.photo?.let { drawPhotoCircle(c, it, 75f, y + 42f, 42f, Color.WHITE); y += 100f }
                        y += drawAt(c, layout(name, tp(14f, accent, bold = true), 118f), 18f, y) + 10f
                        contact.forEach { y += drawAt(c, layout(it, tp(8.2f, DARK), 118f), 18f, y) + 3f }
                    }
                }
            }
            DesignTemplate.TIMELINE, DesignTemplate.SIMPLE -> {
                bodyX = 62f; bodyTop = 112f
                val nameColor = if (cfg.template == DesignTemplate.SIMPLE) DARK else accent
                decorate = { c, page ->
                    if (page == 0) {
                        drawAt(c, layout(name, tp(20f, nameColor, bold = true), W - 124f), 62f, 36f)
                        drawAt(c, layout(contact.joinToString("   ·   "), tp(8.5f, GRAY), W - 124f), 62f, 64f)
                        c.drawRect(62f, 84f, W - 52f, if (cfg.template == DesignTemplate.SIMPLE) 84.8f else 86.5f, fill(accent))
                    }
                }
            }
        }
        val bodyW = W - bodyX - 52f
        val flow = Flow(sink, bodyTop, 56f, H - 50f, decorate).start()

        // Text gliedern: Empfänger, Datum, Betreff, Brieftext, Grußformel
        val profileBits = (listOf(name) + contact).filter { it.isNotBlank() }.map { it.lowercase() }
        val lines = letterText.replace("\r", "").lines()
        val head = lines.take(18)
        val subjectIdx = head.indexOfFirst { Regex("(?i)^\\s*(betreff:?\\s*)?bewerbung (als|um|für)").containsMatchIn(it) }
            .takeIf { it >= 0 } ?: head.indexOfFirst { it.trim().startsWith("Betreff", true) }
        val dateIdx = head.indexOfFirst { Regex("\\b\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}\\b").containsMatchIn(it) && it.length < 60 }

        val body: List<String>
        if (subjectIdx >= 0) {
            val headEnd = if (dateIdx in 0 until subjectIdx) dateIdx else subjectIdx
            val recipient = lines.take(headEnd).map { it.trim() }
                .filter { it.isNotBlank() && profileBits.none { b -> it.lowercase() == b } }
            // Rücksendeangabe (DIN 5008) klein über dem Empfänger
            if (cfg.template != DesignTemplate.SIDEBAR && profile.address.isNotBlank()) {
                val ret = (listOf(name) + profile.address.lines()).filter { it.isNotBlank() }.joinToString(" · ")
                flow.draw(layout(ret, tp(7f, GRAY), bodyW), bodyX)
                flow.y += 4f
            }
            recipient.forEach { flow.draw(layout(it, tp(10.5f, DARK), bodyW), bodyX) }
            flow.y += 22f
            if (dateIdx in 0 until subjectIdx) {
                flow.draw(layout(lines[dateIdx].trim(), tp(10.5f, DARK), bodyW, Layout.Alignment.ALIGN_OPPOSITE), bodyX)
                flow.y += 16f
            }
            flow.draw(layout(lines[subjectIdx].trim().removePrefix("Betreff:").trim(), tp(11.2f, DARK, bold = true), bodyW), bodyX)
            flow.y += 16f
            body = lines.drop(subjectIdx + 1)
        } else {
            body = lines
        }

        // Absätze
        val paragraphs = mutableListOf<String>()
        val cur = StringBuilder()
        for (l in body) {
            if (l.isBlank()) {
                if (cur.isNotEmpty()) { paragraphs += cur.toString(); cur.setLength(0) }
            } else {
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(l.trim())
            }
        }
        if (cur.isNotEmpty()) paragraphs += cur.toString()

        val bodyP = tp(10.5f, DARK)
        var signed = false
        for (para in paragraphs) {
            val closingLine = Regex("(?i)(mit freundlichen grüßen|freundliche grüße|beste grüße|mit besten grüßen|viele grüße)")
            if (!signed && closingLine.containsMatchIn(para)) {
                // Grußformel, dann Unterschrift, dann Name
                val parts = para.lines()
                val idx = parts.indexOfFirst { closingLine.containsMatchIn(it) }
                parts.take(idx + 1).forEach { flow.draw(layout(it, bodyP, bodyW, mult = 1.25f), bodyX) }
                signature(flow, assets.signature, bodyX)
                val rest = parts.drop(idx + 1)
                if (rest.isNotEmpty()) rest.forEach { flow.draw(layout(it, bodyP, bodyW), bodyX) }
                signed = true
                flow.y += 10f
                continue
            }
            if (signed && paragraphs.indexOf(para) > 0 && para.lines().size == 1 && para.length < 60 &&
                !para.contains(":") && name.isNotBlank() && para.trim() == name
            ) {
                flow.draw(layout(para, bodyP, bodyW), bodyX); flow.y += 10f; continue
            }
            val isAttach = para.trim().startsWith("Anlage", true)
            flow.draw(layout(para, if (isAttach) tp(9.5f, GRAY) else bodyP, bodyW, mult = 1.25f), bodyX)
            flow.y += 9f
        }
        if (!signed && name.isNotBlank()) {
            signature(flow, assets.signature, bodyX)
            flow.draw(layout(name, bodyP, bodyW), bodyX)
        }
        flow.finish()
    }

    private fun signature(f: Flow, sig: Bitmap?, x: Float) {
        if (sig == null) { f.y += 26f; return }
        val h = 40f
        val w = (sig.width.toFloat() / sig.height * h).coerceAtMost(200f)
        f.ensure(h + 8f)
        f.c.drawBitmap(sig, null, RectF(x, f.y + 4f, x + w, f.y + 4f + h), Paint().apply { isFilterBitmap = true; isAntiAlias = true })
        f.y += h + 8f
    }

    // ---------------- Deckblatt ----------------

    fun renderCover(
        sink: PageSink, profile: Profile, cfg: DesignConfig, assets: Assets,
        jobTitle: String, company: String, attachments: List<String>
    ) {
        val simple = cfg.template == DesignTemplate.SIMPLE
        val accent = if (simple) DARK else cfg.accent.argb
        val c = sink.begin()
        if (!simple) {
            c.drawRect(0f, 0f, W, 16f, fill(accent))
            c.drawRect(0f, H - 16f, W, H, fill(tint(accent, 0.85f)))
        }
        val x = 70f
        val w = W - 140f
        var y = 110f
        y += drawAt(c, layout("Bewerbung", tp(36f, accent, bold = true), w), x, y)
        if (jobTitle.isNotBlank()) y += drawAt(c, layout("als $jobTitle", tp(16f, DARK), w), x, y + 6f) + 6f
        if (company.isNotBlank()) y += drawAt(c, layout("bei $company", tp(13f, GRAY), w), x, y + 4f) + 4f
        y += 8f
        c.drawRect(x, y + 10f, x + 60f, y + 13f, fill(accent))
        y += 44f

        if (!simple && assets.photo != null) {
            val pw = 150f
            val ph = 190f
            drawPhotoRect(c, assets.photo, (W - pw) / 2, y, pw, ph)
            y += ph + 28f
        } else y += 30f
        y += drawAt(c, layout(profile.name, tp(20f, DARK, bold = true), w, Layout.Alignment.ALIGN_CENTER), x, y) + 6f
        if (profile.headline.isNotBlank()) {
            y += drawAt(c, layout(profile.headline, tp(11.5f, accent), w, Layout.Alignment.ALIGN_CENTER), x, y) + 6f
        }
        profile.contactLines().forEach {
            y += drawAt(c, layout(it, tp(9.8f, GRAY), w, Layout.Alignment.ALIGN_CENTER), x, y) + 2f
        }

        if (attachments.isNotEmpty()) {
            var ay = H - 90f - attachments.size * 15f
            ay += drawAt(c, layout("INHALT", tp(10.5f, accent, bold = true).apply { letterSpacing = 0.08f }, w), x, ay) + 6f
            attachments.forEach { a -> ay += drawAt(c, layout("•  $a", tp(10f, DARK), w), x, ay) + 2f }
        }
        sink.end()
    }

    // ---------------- Bequeme Aufrufe ----------------

    fun letterPageCount(text: String, profile: Profile, cfg: DesignConfig, assets: Assets): Int {
        val s = CountSink()
        renderLetter(s, text, profile, cfg, assets)
        return s.pages
    }

    fun previewCv(text: String, profile: Profile, cfg: DesignConfig, assets: Assets, widthPx: Int): Pair<Bitmap, Int> {
        val s = PreviewSink(widthPx); renderCv(s, text, profile, cfg, assets); return s.bitmap to s.pages
    }

    fun previewLetter(text: String, profile: Profile, cfg: DesignConfig, assets: Assets, widthPx: Int): Pair<Bitmap, Int> {
        val s = PreviewSink(widthPx); renderLetter(s, text, profile, cfg, assets); return s.bitmap to s.pages
    }

    fun previewCover(profile: Profile, cfg: DesignConfig, assets: Assets, widthPx: Int, title: String, company: String): Bitmap {
        val s = PreviewSink(widthPx)
        renderCover(s, profile, cfg, assets, title, company, listOf("Anschreiben", "Lebenslauf", "Zeugnisse"))
        return s.bitmap
    }
}
