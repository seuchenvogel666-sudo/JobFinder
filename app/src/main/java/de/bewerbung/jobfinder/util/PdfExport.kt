package de.bewerbung.jobfinder.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Erstellt DIN-A4-PDFs (aus Text, Bildern und vorhandenen PDFs) und teilt sie. */
object PdfExport {

    // DIN A4 in Punkten (1/72 Zoll)
    const val PAGE_W = 595
    const val PAGE_H = 842
    private const val MARGIN_LEFT = 71f   // 25 mm
    private const val MARGIN_RIGHT = 57f  // 20 mm
    private const val MARGIN_TOP = 57f
    private const val MARGIN_BOTTOM = 57f

    private fun layoutFor(text: String, fontSize: Float): StaticLayout {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = fontSize
        }
        val width = (PAGE_W - MARGIN_LEFT - MARGIN_RIGHT).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
    }

    /** Schreibt Text auf eine oder mehrere Seiten. Gibt die nächste freie Seitennummer zurück. */
    fun drawText(doc: PdfDocument, text: String, startPage: Int, fontSize: Float = 10.5f): Int {
        val layout = layoutFor(text, fontSize)
        val width = (PAGE_W - MARGIN_LEFT - MARGIN_RIGHT)
        val usable = PAGE_H - MARGIN_TOP - MARGIN_BOTTOM
        var pageNo = startPage
        if (layout.lineCount == 0) {
            doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()))
            return pageNo + 1
        }
        var line = 0
        while (line < layout.lineCount) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val startY = layout.getLineTop(line)
            var endLine = line
            while (endLine < layout.lineCount && layout.getLineBottom(endLine) - startY <= usable) endLine++
            if (endLine == line) endLine = line + 1
            val endY = layout.getLineBottom(endLine - 1)
            val canvas = page.canvas
            canvas.save()
            canvas.translate(MARGIN_LEFT, MARGIN_TOP - startY)
            canvas.clipRect(0f, startY.toFloat(), width, endY.toFloat())
            layout.draw(canvas)
            canvas.restore()
            doc.finishPage(page)
            line = endLine
            pageNo++
        }
        return pageNo
    }

    /** Zeichnet ein Bild seitenfüllend (Seitenverhältnis bleibt) auf eine neue A4-Seite. */
    fun drawBitmapPage(doc: PdfDocument, bmp: Bitmap, pageNo: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        val scale = minOf(PAGE_W.toFloat() / bmp.width, PAGE_H.toFloat() / bmp.height)
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val left = (PAGE_W - w) / 2
        val top = (PAGE_H - h) / 2
        c.drawBitmap(bmp, null, Rect(left, top, left + w, top + h), null)
        doc.finishPage(page)
    }

    /** Übernimmt alle Seiten einer vorhandenen PDF (als Bild, ca. 150 dpi). */
    fun appendPdfFile(doc: PdfDocument, file: File, startPage: Int, maxPages: Int = 20): Int {
        var pageNo = startPage
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            for (i in 0 until minOf(renderer.pageCount, maxPages)) {
                val page = renderer.openPage(i)
                val bmp = try {
                    val w = 1240
                    val h = (page.height * w / page.width.toFloat()).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    b
                } finally {
                    page.close()
                }
                drawBitmapPage(doc, bmp, pageNo++)
                bmp.recycle()
            }
        } finally {
            renderer.close()
            runCatching { pfd.close() }
        }
        return pageNo
    }

    /** Lädt ein Bild verkleinert (max. ca. 2000 px), um Speicher zu sparen. */
    fun decodeSampled(context: Context, uri: Uri, maxSide: Int = 2000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Macht aus Fotos/Scans eine PDF (Original für die Bewerbungsmappe). */
    fun imagesToPdf(context: Context, uris: List<Uri>, out: File) {
        val doc = PdfDocument()
        var pageNo = 1
        for (u in uris) {
            val bmp = decodeSampled(context, u) ?: continue
            drawBitmapPage(doc, bmp, pageNo++)
            bmp.recycle()
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    fun createPdf(context: Context, title: String, text: String, fontSize: Float = 10.5f): File {
        val doc = PdfDocument()
        drawText(doc, text, 1, fontSize)
        val file = File(File(context.cacheDir, "pdf").apply { mkdirs() }, safeName(title) + ".pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "").trim().replace(' ', '_').ifBlank { "Dokument" }

    /** Anzahl Seiten, die der Text als PDF belegen würde. */
    fun pageCount(text: String, fontSize: Float = 10.5f): Int {
        val layout = layoutFor(text, fontSize)
        val usable = PAGE_H - MARGIN_TOP - MARGIN_BOTTOM
        return kotlin.math.ceil(layout.height / usable.toDouble()).toInt().coerceAtLeast(1)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun shareFile(context: Context, file: File, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "PDF teilen / speichern"))
    }

    fun sharePdf(context: Context, title: String, text: String) {
        shareFile(context, createPdf(context, title, text), title)
    }

    fun viewFile(context: Context, file: File) {
        val mime = if (file.name.endsWith(".pdf")) "application/pdf" else "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Text", text))
    }
}
