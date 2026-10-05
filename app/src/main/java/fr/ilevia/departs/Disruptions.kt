package fr.ilevia.departs

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Une information trafic ou perturbation publiée par Ilévia pour une ou plusieurs lignes. */
data class Disruption(val id: String, val line: String, val type: String, val message: String, val end: Instant?)

object Disruptions {
    private const val URL_D =
        "https://data.lillemetropole.fr/geoserver/ogc/features/v1/collections/dsp_ilevia:perturbations/items?f=application/json&limit=3000"
    private val LINE_REF = Regex("LineRef::(.+?):LOC")

    @Volatile private var cache: Pair<Long, List<Disruption>>? = null

    /** Perturbations en cours (cache 5 min). Lève une exception si le réseau est indisponible. */
    fun fetch(force: Boolean = false): List<Disruption> {
        val now = System.currentTimeMillis()
        cache?.let { (t, v) -> if (!force && now - t < 300_000) return v }
        val c = URL(URL_D).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/json")
        val body = try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val feats = JSONObject(body).optJSONArray("features")
        val list = ArrayList<Disruption>()
        val limit = Instant.now()
        if (feats != null) for (i in 0 until feats.length()) {
            val p = feats.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val line = LINE_REF.find(p.optString("cible", ""))?.groupValues?.get(1) ?: continue
            val end = p.optString("heure_fin_prevue", "").takeIf { it.isNotBlank() && it != "null" }?.let { IleviaApi.parseTime(it) }
            if (end != null && end.isBefore(limit)) continue
            val msg = p.optString("message", "").replace('​', ' ').trim()
            if (msg.isEmpty()) continue
            list += Disruption(p.optString("identifiant_message", "$i-$line"), line, p.optString("type_perturbation", "Information"), msg, end)
        }
        cache = now to list
        return list
    }

    /** Convertit le HTML des messages Ilévia en texte lisible (paragraphes, listes, entités). */
    private const val NL = "§§NL§§"

    internal fun cleanHtml(raw: String): String {
        // Le HTML arrive parfois échappé (&lt;b&gt;…) : on décode jusqu'à stabilité (3 passes max).
        var text = raw
        repeat(3) {
            val prepared = text
                .replace(Regex("(?i)<\\s*br\\s*/?>"), NL)
                .replace(Regex("(?i)</\\s*(p|div|h[1-6]|tr)\\s*>"), NL)
                .replace(Regex("(?i)<\\s*li[^>]*>"), NL + "• ")
            val next = androidx.core.text.HtmlCompat.fromHtml(prepared, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            if (next == text) return@repeat
            text = next
        }
        return text
            .replace(NL, "\n")
            .replace('\u200B', ' ').replace('\uFFFC', ' ').replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun forLines(all: List<Disruption>, lines: Set<String>): List<Disruption> =
        all.filter { d -> lines.any { it.equals(d.line, true) } }
}
