package fr.ilevia.departs

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Client des données ouvertes « ilévia – Prochains passages bus et tramway » (MEL).
 * Catalogue : https://data.lillemetropole.fr/catalogue/search?producerOrg=Il%C3%A9via
 *
 * Les jeux de la MEL ont changé plusieurs fois de noms de collection et de champs
 * (ex. `nomstation` / `nom_station`). Pour rester robuste, on essaie plusieurs endpoints
 * et on lit les champs sous plusieurs noms possibles. Si l'API évolue encore, il suffit
 * d'ajuster [ENDPOINTS] et les listes de champs ci-dessous.
 */
object IleviaApi {

    val ENDPOINTS = listOf(
        // Service GeoServer OGC API Features (celui indiqué comme disponible sur data.gouv.fr)
        "https://data.lillemetropole.fr/geoserver/ogc/features/v1/collections/dsp_ilevia:prochains_passages/items?f=application/json&limit=20000",
        // Anciens/autres noms de collection
        "https://data.lillemetropole.fr/data/ogcapi/collections/ilevia:prochains_passages/items?f=json&limit=20000",
        "https://data.lillemetropole.fr/data/ogcapi/collections/prochains_passages/items?f=json&limit=20000",
    )

    private val STATION_KEYS = listOf("nom_station", "nomstation", "station", "nom_arret", "nomarret")
    private val LINE_KEYS = listOf("code_ligne_public", "code_ligne", "codeligne", "ligne", "num_ligne")
    private val DIRECTION_KEYS = listOf("sens_ligne", "sensligne", "sens", "destination", "direction")
    private val TIME_KEYS = listOf(
        "heure_estimee_depart", "heureestimeedepart", "heure_estimee_arrivee",
        "heure_theorique_depart", "heuretheoriquedepart", "heure_depart", "depart",
    )

    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    @Volatile private var cache: Pair<Long, List<Passage>>? = null

    /** Tous les prochains passages du réseau (cache 30 s pour ne pas retélécharger inutilement). */
    fun fetchAll(force: Boolean = false): List<Passage> {
        val now = System.currentTimeMillis()
        cache?.let { (t, v) -> if (!force && now - t < 30_000) return v }
        var lastError: Exception? = null
        for (url in ENDPOINTS) {
            try {
                val list = parse(download(url))
                if (list.isNotEmpty()) {
                    cache = now to list
                    return list
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Aucun passage reçu de l'API Ilévia")
    }

    fun passagesFor(sel: StopSelection, force: Boolean = false): List<Passage> =
        fetchAll(force).filter { sel.matches(it) }.sortedBy { it.time }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "IleviaDeparts/1.0 (Android)")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): List<Passage> {
        val root = JSONObject(body)
        // GeoJSON : features[].properties ; sinon enregistrements à plat : results / records
        val rows: List<JSONObject> = when {
            root.has("features") -> root.getJSONArray("features").objects().map { it.optJSONObject("properties") ?: it }
            root.has("results") -> root.getJSONArray("results").objects()
            root.has("records") -> root.getJSONArray("records").objects().map { it.optJSONObject("fields") ?: it }
            else -> emptyList()
        }
        return rows.mapNotNull { r ->
            val station = r.firstString(STATION_KEYS) ?: return@mapNotNull null
            val line = r.firstString(LINE_KEYS) ?: return@mapNotNull null
            val direction = r.firstString(DIRECTION_KEYS) ?: return@mapNotNull null
            val time = r.firstString(TIME_KEYS)?.let(::parseTime) ?: return@mapNotNull null
            Passage(station.trim(), line.trim(), direction.trim(), time)
        }
    }

    /** Accepte ISO avec ou sans fuseau (sans fuseau = heure de Paris). */
    internal fun parseTime(s: String): Instant? = try {
        OffsetDateTime.parse(s).toInstant()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s.replace(' ', 'T')).atZone(PARIS).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.firstString(keys: List<String>): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
