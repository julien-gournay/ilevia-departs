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

    private const val MAX_PAGES = 60

    private data class Fetched(val endpoint: String, val pages: Int, val rows: List<JSONObject>)

    /** Lignes et nombre de passages présents dans le dernier flux reçu (pour expliquer l'absence de données). */
    @Volatile var feedLines: Set<String> = emptySet()
    @Volatile var feedSize: Int = 0
    @Volatile var feedCommunes: Map<String, String> = emptyMap()

    fun lineInFeed(line: String) = feedLines.contains(line.trim().uppercase())

    @Volatile private var cache: Pair<Long, List<Passage>>? = null
    @Volatile private var lastFetched: Fetched? = null

    /** Tous les prochains passages du réseau (cache 30 s pour ne pas retélécharger inutilement). */
    fun fetchAll(force: Boolean = false): List<Passage> {
        val now = System.currentTimeMillis()
        cache?.let { (t, v) -> if (!force && now - t < 30_000) return v }
        var lastError: Exception? = null
        for (url in ENDPOINTS) {
            try {
                val fetched = fetchRows(url)
                val list = toPassages(fetched.rows).distinct()
                if (list.isNotEmpty()) {
                    cache = now to list
                    feedLines = list.map { it.line.uppercase() }.toSet()
                    feedSize = list.size
                    feedCommunes = list.filter { it.commune != null }.associate { (it.line.uppercase() + "|" + norm(it.station)) to it.commune!! }
                    lastFetched = fetched
                    return list
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Aucun passage reçu de l'API Ilévia")
    }

    /** Télécharge toutes les pages (le serveur plafonne le nombre d'éléments par réponse). */
    private fun fetchRows(startUrl: String): Fetched {
        val rows = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        var url: String? = startUrl
        var pages = 0
        while (url != null && pages < MAX_PAGES && seen.add(url)) {
            val root = JSONObject(download(url))
            val pageRows = rowsOf(root)
            if (pageRows.isEmpty()) break
            rows += pageRows
            pages++
            url = nextLink(root)
        }
        return Fetched(startUrl, pages, rows)
    }

    private fun nextLink(root: JSONObject): String? {
        val links = root.optJSONArray("links") ?: return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (l.optString("rel") == "next") {
                return l.optString("href").ifBlank { null }?.replace("http://", "https://")
            }
        }
        return null
    }

    /** Texte de diagnostic : ce que renvoie réellement l'API pour un arrêt donné. */
    fun diagnostic(query: String): String {
        val sb = StringBuilder()
        return try {
            val all = fetchAll(force = true)
            val f = lastFetched!!
            val q = query.trim()
            sb.appendLine("Maintenant : ${java.time.ZonedDateTime.now(PARIS).toLocalTime().withNano(0)} (Paris)")
            sb.appendLine("Endpoint : …${f.endpoint.substringBefore('?').takeLast(55)}")
            sb.appendLine("Pages : ${f.pages} · lignes brutes : ${f.rows.size} · passages lus : ${all.size}")
            sb.appendLine("Champs : ${f.rows.first().keys().asSequence().toList().joinToString(", ")}")
            val raw = f.rows.filter { it.toString().contains(q, true) }
            sb.appendLine()
            sb.appendLine("Lignes brutes contenant « $q » : ${raw.size}")
            raw.take(6).forEach { sb.appendLine(it.toString()); sb.appendLine() }
            val parsed = all.filter { it.station.contains(q, true) }.sortedBy { it.time }
            sb.appendLine("Passages lus pour cet arrêt : ${parsed.size}")
            parsed.take(25).forEach {
                sb.appendLine("${it.line} → ${it.direction} : ${java.time.ZonedDateTime.ofInstant(it.time, PARIS).toLocalTime().withNano(0)}")
            }
            sb.toString()
        } catch (e: Exception) {
            "Erreur : ${e.javaClass.simpleName} ${e.message}"
        }
    }

    fun passagesFor(sel: StopSelection, force: Boolean = false): List<Passage> =
        StopMatch.rowsFor(sel, fetchAll(force)).sortedBy { it.time }

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

    private fun rowsOf(root: JSONObject): List<JSONObject> = when {
        root.has("features") -> root.getJSONArray("features").objects().map { it.optJSONObject("properties") ?: it }
        root.has("results") -> root.getJSONArray("results").objects()
        root.has("records") -> root.getJSONArray("records").objects().map { it.optJSONObject("fields") ?: it }
        else -> emptyList()
    }

    private fun toPassages(rows: List<JSONObject>): List<Passage> = rows.mapNotNull { r ->
        val station = r.firstString(STATION_KEYS) ?: return@mapNotNull null
        val line = r.firstString(LINE_KEYS) ?: return@mapNotNull null
        val direction = r.firstString(DIRECTION_KEYS) ?: return@mapNotNull null
        val time = r.firstString(TIME_KEYS)?.let(::parseTime) ?: return@mapNotNull null
        Passage(station.trim(), line.trim(), direction.trim(), time, r.optString("commune", "").trim().takeIf { it.isNotEmpty() && it != "null" })
    }

    /**
     * Les heures de l'API Ilévia sont des heures locales (Paris) mais écrites avec un « Z » trompeur
     * (ex. "2026-10-05T08:11:55Z" pour un départ à 08:11 heure de Paris, cf. le champ cle_tri
     * "…08:11:55.000+02:00"). Donc :
     *  - suffixe « Z » → on garde l'heure telle quelle et on la lit comme heure de Paris ;
     *  - vrai décalage explicite (+02:00…) → on le respecte ;
     *  - aucun suffixe → heure de Paris.
     */
    internal fun parseTime(s: String): Instant? {
        val t = s.trim().replace(' ', 'T')
        return try {
            if (t.endsWith("Z", ignoreCase = true)) {
                LocalDateTime.parse(t.dropLast(1)).atZone(PARIS).toInstant()
            } else {
                try {
                    OffsetDateTime.parse(t).toInstant()
                } catch (_: Exception) {
                    LocalDateTime.parse(t).atZone(PARIS).toInstant()
                }
            }
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
