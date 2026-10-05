package fr.ilevia.departs

import android.content.Context
import org.json.JSONObject

/** Index complet des arrêts par ligne et sens (généré à la compilation depuis le GTFS Ilévia). */
object StopIndex {
    class Dir(val headsigns: List<String>, val stops: IntArray)

    private var names: List<Pair<String, String>> = emptyList()
    private var dirs: Map<String, List<Dir>> = emptyMap()
    private var byLine: Map<String, String> = emptyMap()   // "LIGNE|nom normalisé" → commune
    private var byName: Map<String, String> = emptyMap()   // "nom normalisé" → commune (si unique)
    @Volatile private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val body = context.applicationContext.assets.open("stops_index.json").bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val st = root.getJSONArray("stops")
            names = (0 until st.length()).map { val a = st.getJSONArray(it); a.getString(0) to a.optString(1, "") }
            val ls = root.getJSONObject("lines")
            val d = HashMap<String, List<Dir>>()
            val bl = HashMap<String, String>()
            val bn = HashMap<String, String?>()
            for (line in ls.keys()) {
                val arr = ls.getJSONArray(line)
                d[line.uppercase()] = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val h = o.getJSONArray("h"); val s = o.getJSONArray("s")
                    val stops = IntArray(s.length()) { s.getInt(it) }
                    stops.forEach { idx ->
                        val (n, c) = names[idx]
                        if (c.isNotEmpty()) {
                            bl[line.uppercase() + "|" + norm(n)] = c
                            val k = norm(n)
                            bn[k] = if (bn.containsKey(k) && bn[k] != c) null else c
                        }
                    }
                    Dir((0 until h.length()).map { h.getString(it) }, stops)
                }
            }
            dirs = d; byLine = bl
            byName = bn.filterValues { it != null }.mapValues { it.value!! }
        } catch (_: Exception) { /* pas d'index : on retombe sur les données temps réel */ }
    }

    val lines: Set<String> get() = dirs.keys

    fun headsigns(line: String): List<String> = dirs[line.uppercase()].orEmpty().flatMap { it.headsigns }.distinct()

    /** Arrêts du sens [direction] (toutes branches confondues). Si le sens n'est pas reconnu, tous les arrêts de la ligne. */
    fun stopsFor(line: String, direction: String?): List<Pair<String, String>> {
        val all = dirs[line.uppercase()] ?: return emptyList()
        val nd = direction?.let { norm(it) }
        val matching = if (nd.isNullOrEmpty()) all else all.filter { d ->
            d.headsigns.any { h -> val nh = norm(h); nh.endsWith(nd) || nd.endsWith(nh) || nh.contains(nd) }
        }
        val use = matching.ifEmpty { all }
        val seen = HashSet<Int>()
        return use.flatMap { it.stops.toList() }.filter { seen.add(it) }.map { names[it] }
    }

    fun commune(line: String?, station: String): String? {
        val n = norm(station)
        if (line != null) {
            byLine[line.uppercase() + "|" + n]?.let { return it }
            IleviaApi.feedCommunes[line.uppercase() + "|" + n]?.let { return it }
        }
        return byName[n]
    }
}

/** « Chemin Des Vaches (Roubaix) » : nom d'arrêt avec sa commune quand elle est connue. */
fun stopLabel(station: String, line: String? = null): String {
    val c = StopIndex.commune(line, station)
    return pretty(station) + (c?.takeIf { it.isNotBlank() }?.let { " (${pretty(it)})" } ?: "")
}

/** Lignes proposées : celles du temps réel + celles de l'index complet (même si le flux est vide). */
fun lineChoices(all: List<Passage>): List<String> =
    (all.map { it.line } + StopIndex.lines).map { it.trim() }.distinctBy { it.uppercase() }
        .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

/** Sens proposés pour une ligne : ceux du temps réel, sinon ceux de l'index. */
fun directionChoices(all: List<Passage>, line: String): List<String> {
    val live = all.filter { it.line.equals(line, true) }.map { it.direction }.distinct().sorted()
    return live.ifEmpty { StopIndex.headsigns(line).map { it.uppercase() }.distinct().sorted() }
}

/**
 * Tous les arrêts d'un sens : l'ordre de la ligne (toutes branches) issu de l'index,
 * avec l'orthographe du flux temps réel quand elle existe, puis les arrêts du flux inconnus de l'index.
 */
fun stopChoices(all: List<Passage>, line: String, direction: String): List<String> {
    val live = all.filter { it.line.equals(line, true) && norm(it.direction) == norm(direction) }.map { it.station }.distinct()
    val idxStops = StopIndex.stopsFor(line, direction)
    // Pour chaque arrêt de l'index, on reprend l'orthographe du flux temps réel (« Pt De Neuville » → « TOURCOING PONT DE NEUVILLE »).
    val used = HashSet<String>()
    val ordered = idxStops.map { (name, commune) ->
        val exact = live.firstOrNull { norm(it) == norm(name) }
        val fuzzy = exact ?: live.mapNotNull { l -> StopMatch.score(name, commune, l)?.let { l to it } }.minByOrNull { it.second }?.first
        (fuzzy ?: name).also { used += norm(it) }
    }.distinctBy { norm(it) }
    return ordered + live.filter { norm(it) !in used }.sorted()
}
