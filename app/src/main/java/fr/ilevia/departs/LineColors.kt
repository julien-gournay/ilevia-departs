package fr.ilevia.departs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Couleurs officielles des lignes (collection « couleurs_lignes » de la MEL), mises en cache 7 jours. */
object LineColors {
    private const val URL_COLORS =
        "https://data.lillemetropole.fr/geoserver/ogc/features/v1/collections/dsp_ilevia:couleurs_lignes/items?f=application/json&limit=2000"

    /** Incrémenté quand les couleurs changent, pour que l'interface se redessine. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var map: Map<String, Pair<Int, Int>> = emptyMap()
    @Volatile private var started = false

    /** Charge le cache local puis rafraîchit en arrière-plan si besoin. Idempotent. */
    fun init(context: Context) {
        if (started) return
        started = true
        val prefs = context.applicationContext.getSharedPreferences("line_colors", Context.MODE_PRIVATE)
        prefs.getString("json", null)?.let { map = parse(it) }
        val age = System.currentTimeMillis() - prefs.getLong("at", 0L)
        if (map.isEmpty() || age > 7 * 24 * 3600 * 1000L) {
            Thread {
                try {
                    val body = download()
                    val m = parse(body)
                    if (m.isNotEmpty()) {
                        prefs.edit().putString("json", body).putLong("at", System.currentTimeMillis()).apply()
                        map = m
                        version++
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

    fun bg(line: String): Int? = map[line.trim().uppercase()]?.first
    fun fg(line: String): Int? = map[line.trim().uppercase()]?.second

    private fun download(): String {
        val c = URL(URL_COLORS).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(body: String): Map<String, Pair<Int, Int>> {
        val out = HashMap<String, Pair<Int, Int>>()
        val feats = JSONObject(body).optJSONArray("features") ?: return out
        for (i in 0 until feats.length()) {
            val p = feats.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val bg = hex(p.optString("couleur_fond_hexadecimal")) ?: continue
            val fg = hex(p.optString("couleur_texte_hexadecimal")) ?: 0xFFFFFFFF.toInt()
            for (k in listOf("code_ligne", "code_ligne_public")) {
                val code = p.optString(k, "").trim().uppercase()
                if (code.isNotEmpty() && code != "NULL") out.putIfAbsent(code, bg to fg)
            }
        }
        return out
    }

    private fun hex(s: String?): Int? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        return h.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }
}
