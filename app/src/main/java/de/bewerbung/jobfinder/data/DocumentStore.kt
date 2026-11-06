package de.bewerbung.jobfinder.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Speichert die digitalen Bewerbungsunterlagen als JSON im privaten App-Speicher. */
class DocumentStore(context: Context) {

    private val file = File(context.filesDir, "documents.json")

    /** Ordner für Originaldateien (PDFs, Scans) der Unterlagen. */
    val originalsDir: File = File(context.filesDir, "originals").apply { mkdirs() }

    @Synchronized
    fun load(): List<AppDocument> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AppDocument(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    type = runCatching { DocType.valueOf(o.optString("type")) }.getOrDefault(DocType.SONSTIGES),
                    text = o.optString("text"),
                    updatedMillis = o.optLong("updated"),
                    originalPath = o.optString("original").takeIf { it.isNotBlank() && File(it).exists() }
                )
            }.sortedByDescending { it.updatedMillis }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(docs: List<AppDocument>) {
        val arr = JSONArray()
        docs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("type", it.type.name)
                    .put("text", it.text)
                    .put("updated", it.updatedMillis)
                    .put("original", it.originalPath ?: "")
            )
        }
        val tmp = File(file.parentFile, "documents.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun upsert(doc: AppDocument): List<AppDocument> {
        val list = load().filter { it.id != doc.id } + doc.copy(updatedMillis = System.currentTimeMillis())
        saveAll(list)
        return load()
    }

    fun delete(id: String): List<AppDocument> {
        load().firstOrNull { it.id == id }?.originalPath?.let { runCatching { File(it).delete() } }
        saveAll(load().filter { it.id != id })
        return load()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Rät den Dokumenttyp anhand des Inhalts. */
        fun guessType(text: String): DocType {
            val t = text.lowercase()
            return when {
                "zwischenzeugnis" in t -> DocType.ZWISCHENZEUGNIS
                "lebenslauf" in t || "curriculum vitae" in t || "berufserfahrung" in t -> DocType.LEBENSLAUF
                Regex("weiterbildung|fortbildung|zertifikat|teilnahmebescheinigung|lehrgang|seminar|schulung|qualifizierung")
                    .containsMatchIn(t) && "arbeitszeugnis" !in t -> DocType.WEITERBILDUNG
                "zeugnis" in t || "bescheinigung" in t -> DocType.ZEUGNIS
                "sehr geehrte" in t && "bewerbung" in t -> DocType.ANSCHREIBEN
                else -> DocType.SONSTIGES
            }
        }
    }
}
