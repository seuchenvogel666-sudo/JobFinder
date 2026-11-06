package de.bewerbung.jobfinder.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wandelt Dateien und Fotos in bearbeitbaren Text um.
 * Unterstützt: Bilder (Texterkennung), PDF (Seiten werden per Texterkennung gelesen),
 * Word (.docx) und Textdateien.
 */
class TextExtractor(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun displayName(uri: Uri): String {
        var name = "Dokument"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        return name
    }

    suspend fun extract(uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = displayName(uri).lowercase()
        when {
            mime == "application/pdf" || name.endsWith(".pdf") -> extractPdf(uri)
            mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp") -> ocrUri(uri)
            mime.contains("wordprocessingml") || name.endsWith(".docx") -> extractDocx(uri)
            else -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.trim()
    }

    suspend fun ocrUri(uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        return recognizer.process(image).await().text
    }

    private suspend fun ocrBitmap(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text
    }

    private suspend fun extractPdf(uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("PDF konnte nicht geöffnet werden")
        val sb = StringBuilder()
        // Bewusst try/finally statt use{}: PdfRenderer ist auf alten Android-Versionen kein AutoCloseable
        val renderer = PdfRenderer(pfd)
        try {
            val pages = minOf(renderer.pageCount, 15)
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                val bmp: Bitmap = try {
                    // ca. 200 dpi für gute Texterkennung
                    val scale = 200f / 72f
                    val w = (page.width * scale).toInt().coerceAtMost(2400)
                    val h = (page.height * w / page.width.toFloat()).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
                val text = ocrBitmap(bmp)
                bmp.recycle()
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(text)
            }
        } finally {
            renderer.close()
            runCatching { pfd.close() }
        }
        return sb.toString()
    }

    private fun extractDocx(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return parseDocxXml(zip)
                }
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun parseDocxXml(stream: java.io.InputStream): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")
        val sb = StringBuilder()
        var event = parser.eventType
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab" -> sb.append('\t')
                    "w:br", "w:cr" -> sb.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p" -> sb.append('\n')
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }
}

/** Wandelt eine Google-Play-Services-Task in eine Coroutine um. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
