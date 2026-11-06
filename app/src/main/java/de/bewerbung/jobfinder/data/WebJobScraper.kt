package de.bewerbung.jobfinder.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Liest die öffentlich einsehbaren Suchergebnisseiten von LinkedIn, XING, Indeed und StepStone.
 *
 * Diese Portale haben keine freie Schnittstelle. Die Seiten werden deshalb – wie in einem normalen
 * Browser – in einer unsichtbaren WebView geladen und die Stellen-Links samt Kartentext ausgelesen.
 * Ändert ein Portal seinen Seitenaufbau oder blockiert es automatische Zugriffe, liefert diese Quelle
 * eben keine Treffer; die übrigen Quellen funktionieren weiter.
 */
class WebJobScraper(private val context: Context) {

    data class RawCard(val href: String, val title: String, val card: String, val date: String)

    // ---------------- Such-URLs ----------------

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("\\s+"), "-")

    fun searchUrl(site: WebSite, query: String, place: String, radius: RadiusOption): String {
        val q = Http.enc(query)
        val l = Http.enc(if (radius.remote && place.isBlank()) "Deutschland" else place.ifBlank { "Deutschland" })
        val km = radius.km
        return when (site) {
            WebSite.LINKEDIN -> {
                val miles = (km / 1.609).toInt().coerceAtLeast(10)
                "https://www.linkedin.com/jobs/search/?keywords=$q&location=$l&sortBy=DD" +
                        if (radius.remote) "&f_WT=2" else "&distance=$miles"
            }
            WebSite.XING -> {
                val kw = Http.enc(if (radius.remote) "$query remote" else query)
                "https://www.xing.com/jobs/search?keywords=$kw&location=$l" +
                        if (radius.remote) "" else "&radius=$km"
            }
            WebSite.INDEED ->
                "https://de.indeed.com/jobs?q=$q&l=$l&sort=date" +
                        if (radius.remote) "&sc=0kf%3Aattr(DSQF7)%3B" else "&radius=$km"
            WebSite.STEPSTONE -> {
                val qs = Http.enc(slug(query.ifBlank { "jobs" }))
                if (radius.remote) "https://www.stepstone.de/jobs/$qs?wfh=1&sort=2"
                else "https://www.stepstone.de/jobs/$qs/in-${Http.enc(slug(place.ifBlank { "deutschland" }))}?radius=$km&sort=2"
            }
        }
    }

    /** Erkennungsmuster für Stellen-Links je Portal (als JavaScript-RegExp). */
    private fun linkPattern(site: WebSite): String = when (site) {
        WebSite.LINKEDIN -> "/jobs/view/"
        WebSite.XING -> "/jobs/[^/?#]*-\\\\d{5,}"
        WebSite.INDEED -> "(jk=|/viewjob|/rc/clk|/pagead/clk)"
        WebSite.STEPSTONE -> "stellenangebote--"
    }

    // ---------------- Suche ----------------

    suspend fun search(site: WebSite, query: String, place: String, radius: RadiusOption): List<Job> {
        // LinkedIn hat eine öffentliche "Gast"-Ergebnisliste, die sich ohne Browser lesen lässt
        if (site == WebSite.LINKEDIN) {
            val direct = runCatching { linkedInGuest(query, place, radius) }.getOrDefault(emptyList())
            if (direct.isNotEmpty()) return direct
        }
        val cards = scrape(searchUrl(site, query, place, radius), extractJs(linkPattern(site)))
        return cards.mapIndexedNotNull { i, c -> toJob(site, c, i) }
    }

    private suspend fun linkedInGuest(query: String, place: String, radius: RadiusOption): List<Job> =
        withContext(Dispatchers.IO) {
            val miles = (radius.km / 1.609).toInt().coerceAtLeast(10)
            val loc = if (place.isBlank()) "Deutschland" else place
            val url = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search" +
                    "?keywords=${Http.enc(query)}&location=${Http.enc(loc)}&start=0&sortBy=DD" +
                    if (radius.remote) "&f_WT=2" else "&distance=$miles"
            val html = Http.get(url, mapOf("Accept" to "text/html", "Accept-Language" to "de-DE,de;q=0.9"))
            html.split("<li").mapIndexedNotNull { i, part ->
                val href = Regex("href=\"(https://[a-z.]*linkedin\\.com/jobs/view/[^\"?]+)").find(part)
                    ?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                fun cls(c: String) = Regex("class=\"[^\"]*$c[^\"]*\"[^>]*>([\\s\\S]*?)</(h3|h4|span|a)>")
                    .find(part)?.groupValues?.get(1)?.stripHtml()?.trim() ?: ""
                val title = cls("base-search-card__title")
                if (title.isBlank()) return@mapIndexedNotNull null
                val date = Regex("<time[^>]*datetime=\"([^\"]+)\"").find(part)?.groupValues?.get(1)
                Job(
                    id = "web-li-" + href.substringAfterLast('-').ifBlank { i.toString() },
                    source = "LinkedIn",
                    title = title,
                    company = cls("base-search-card__subtitle"),
                    location = cls("job-search-card__location"),
                    lat = null, lon = null, distanceKm = null,
                    publishedMillis = date?.let { parseIsoDay(it) },
                    url = href,
                    snippet = ""
                )
            }
        }

    // ---------------- Umwandlung Karte -> Job ----------------

    private fun toJob(site: WebSite, c: RawCard, index: Int): Job? {
        val title = c.title.lines().map { it.trim() }.firstOrNull { it.length > 3 } ?: return null
        val lines = c.card.lines().map { it.trim() }
            .filter { it.isNotBlank() && it != title && it.length < 120 }
        val dateLine = lines.firstOrNull { parseRelativeDate(it) != null }
        val locLine = lines.firstOrNull {
            it != dateLine && (Regex("\\b\\d{5}\\b").containsMatchIn(it) ||
                    Regex("(?i)homeoffice|remote|hybrid|deutschland|bundesweit").containsMatchIn(it) ||
                    Regex("^[A-ZÄÖÜ][a-zäöüß]+([ -][A-ZÄÖÜ]?[a-zäöüß]+)*(,.*)?$").matches(it))
        } ?: ""
        val company = lines.firstOrNull {
            it != locLine && it != dateLine &&
                    !Regex("(?i)neu|new|gesponsert|sponsored|bewerb|€|stunde|vollzeit|teilzeit|merken|speichern")
                        .containsMatchIn(it)
        } ?: ""
        val url = normalizeUrl(site, c.href)
        val published = parseIsoDay(c.date) ?: parseRelativeDate(dateLine ?: "") ?: parseRelativeDate(c.card)
        return Job(
            id = "web-${site.name.lowercase()}-" + url.hashCode().toString(),
            source = site.label,
            title = title,
            company = company,
            location = locLine,
            lat = null, lon = null, distanceKm = null,
            publishedMillis = published,
            url = url,
            snippet = c.card.take(400)
        ).takeIf { index < 60 }
    }

    private fun normalizeUrl(site: WebSite, href: String): String {
        if (site == WebSite.INDEED) {
            Regex("[?&]jk=([a-z0-9]+)").find(href)?.let { return "https://de.indeed.com/viewjob?jk=${it.groupValues[1]}" }
        }
        return href.substringBefore("#").let { if (site == WebSite.LINKEDIN) it.substringBefore("?") else it }
    }

    // ---------------- WebView ----------------

    /** JavaScript: alle Stellen-Links samt umgebender "Karte" einsammeln. */
    private fun extractJs(pattern: String): String = """
(function(){
  var re = new RegExp('$pattern');
  var seen = {}; var out = [];
  var links = document.querySelectorAll('a[href]');
  for (var i = 0; i < links.length && out.length < 60; i++) {
    var a = links[i]; var href = a.href;
    if (!re.test(href)) continue;
    var key = href.split('#')[0];
    var jk = href.match(/jk=([a-z0-9]+)/); if (jk) key = jk[1];
    if (seen[key]) continue;
    var title = (a.innerText || a.getAttribute('aria-label') || a.title || '').trim();
    if (title.length < 4) { var h = a.querySelector('h2,h3,span'); if (h) title = h.innerText.trim(); }
    var card = a;
    for (var k = 0; k < 7 && card.parentElement; k++) {
      card = card.parentElement;
      var t = card.tagName;
      if (t === 'LI' || t === 'ARTICLE' || (card.innerText && card.innerText.split('\n').length >= 4)) break;
    }
    if (title.length < 4) { var hh = card.querySelector('h2,h3'); if (hh) title = hh.innerText.trim(); }
    if (title.length < 4) continue;
    var time = card.querySelector('time');
    seen[key] = 1;
    out.push({href: href, title: title, card: (card.innerText || '').substring(0, 700),
              date: time ? (time.getAttribute('datetime') || '') : ''});
  }
  return JSON.stringify(out);
})();
""".trimIndent()

    private val pageTextJs = """
(function(){
  var sel = ['.show-more-less-html__markup', '#jobDescriptionText', '[data-testid*="description"]',
             '[data-at="job-ad-content"]', '[class*="description"]', 'article', 'main'];
  for (var i = 0; i < sel.length; i++) {
    var e = document.querySelector(sel[i]);
    if (e && e.innerText && e.innerText.length > 300) return e.innerText;
  }
  return document.body ? document.body.innerText : '';
})();
""".trimIndent()

    /** Lädt eine Anzeige und gibt deren Text zurück (für Detailansicht und Anschreiben). */
    suspend fun pageText(url: String): String {
        val result = runWebView(url, pageTextJs, minLength = 300, timeoutMs = 18_000)
        val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: ""
        return text.replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim().take(15_000)
    }

    private suspend fun scrape(url: String, js: String): List<RawCard> {
        val result = runWebView(url, js, minLength = 10, timeoutMs = 20_000)
        val str = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(str) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RawCard(o.optString("href"), o.optString("title"), o.optString("card"), o.optString("date"))
        }
    }

    /**
     * Öffnet die Seite in einer unsichtbaren WebView und führt [js] wiederholt aus,
     * bis ein brauchbares Ergebnis da ist (Seiten laden Inhalte oft nach) oder die Zeit abläuft.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebView(url: String, js: String, minLength: Int, timeoutMs: Long): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val web = WebView(context.applicationContext)
                var finished = false
                var best = ""
                var stableCount = 0
                val start = System.currentTimeMillis()

                fun finish(value: String) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { web.stopLoading(); web.destroy() }
                    if (cont.isActive) cont.resume(value)
                }

                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.loadsImagesAutomatically = false
                web.settings.blockNetworkImage = true
                web.webViewClient = WebViewClient()
                // Ohne Größe rendern manche Seiten nichts
                web.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(4000, android.view.View.MeasureSpec.EXACTLY)
                )
                web.layout(0, 0, 1080, 4000)

                val poll = object : Runnable {
                    override fun run() {
                        if (finished) return
                        web.evaluateJavascript(js) { res ->
                            val value = res ?: ""
                            if (value.length > best.length) {
                                best = value; stableCount = 0
                            } else if (best.length >= minLength) {
                                stableCount++
                            }
                            val elapsed = System.currentTimeMillis() - start
                            when {
                                best.length >= minLength && stableCount >= 1 -> finish(best)
                                elapsed > timeoutMs -> finish(best)
                                else -> handler.postDelayed(this, 1500)
                            }
                        }
                    }
                }
                cont.invokeOnCancellation { handler.post { finish("") } }
                web.loadUrl(url, mapOf("Accept-Language" to "de-DE,de;q=0.9"))
                handler.postDelayed(poll, 3000)
            }
        }

    companion object {
        fun parseIsoDay(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }

        /** Versteht z. B. "Heute", "Gestern", "vor 3 Tagen", "Vor 30+ Tagen", "2 weeks ago", "05.11.2026". */
        fun parseRelativeDate(text: String): Long? {
            val t = text.lowercase()
            val today = LocalDate.now()
            fun days(n: Long) = today.minusDays(n).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b").find(t)?.let {
                return runCatching {
                    LocalDate.parse(it.value.split(".").let { p -> "${p[2]}-${p[1].padStart(2, '0')}-${p[0].padStart(2, '0')}" })
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            Regex("vor (\\d+)\\+? ?(tag|woche|monat)").find(t)?.let {
                val n = it.groupValues[1].toLong()
                return days(n * when (it.groupValues[2]) { "woche" -> 7; "monat" -> 30; else -> 1 })
            }
            Regex("(\\d+)\\+? ?(day|week|month)s? ago").find(t)?.let {
                val n = it.groupValues[1].toLong()
                return days(n * when (it.groupValues[2]) { "week" -> 7; "month" -> 30; else -> 1 })
            }
            if (Regex("vor \\d+ (stunde|minute)|gerade|soeben|heute|today|hours? ago|minutes? ago").containsMatchIn(t)) return days(0)
            if (Regex("gestern|yesterday").containsMatchIn(t)) return days(1)
            return null
        }
    }
}
