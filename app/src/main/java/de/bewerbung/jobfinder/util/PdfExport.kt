package de.bewerbung.jobfinder.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Erstellt aus Text eine DIN-A4-PDF und öffnet den Teilen-Dialog. */
object PdfExport {

    // DIN A4 in Punkten (1/72 Zoll)
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_LEFT = 71f   // 25 mm
    private const val MARGIN_RIGHT = 57f  // 20 mm
    private const val MARGIN_TOP = 57f
    private const val MARGIN_BOTTOM = 57f

    fun createPdf(context: Context, title: String, text: String, fontSize: Float = 10.5f): File {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = fontSize
        }
        val width = (PAGE_W - MARGIN_LEFT - MARGIN_RIGHT).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

        val doc = PdfDocument()
        val usable = PAGE_H - MARGIN_TOP - MARGIN_BOTTOM
        var line = 0
        var pageNo = 1
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
            canvas.clipRect(0f, startY.toFloat(), width.toFloat(), endY.toFloat())
            layout.draw(canvas)
            canvas.restore()
            doc.finishPage(page)
            line = endLine
            pageNo++
        }
        if (layout.lineCount == 0) {
            doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()))
        }

        val safe = title.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "").trim().ifBlank { "Dokument" }
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Anzahl Seiten, die der Text als PDF belegen würde. */
    fun pageCount(text: String, fontSize: Float = 10.5f): Int {
        val paint = TextPaint().apply { textSize = fontSize }
        val width = (PAGE_W - MARGIN_LEFT - MARGIN_RIGHT).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, 1.15f).setIncludePad(false).build()
        val usable = PAGE_H - MARGIN_TOP - MARGIN_BOTTOM
        return kotlin.math.ceil(layout.height / usable.toDouble()).toInt().coerceAtLeast(1)
    }

    fun sharePdf(context: Context, title: String, text: String) {
        val file = createPdf(context, title, text)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "PDF teilen / speichern"))
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Text", text))
    }
}
