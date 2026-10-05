package fr.ilevia.departs

/**
 * Rapprochement tolérant entre les noms d'arrêt du fichier d'horaires (« Pt De Neuville ») et ceux du flux
 * temps réel (« TOURCOING PONT DE NEUVILLE ») : abréviations, préfixe de commune, mots vides.
 */
object StopMatch {
    private val STOP_WORDS = setOf("de", "du", "des", "la", "le", "les", "d", "l", "et", "sur", "en", "aux", "au", "a", "lez")
    private val ABBR = mapOf(
        "pt" to "pont", "st" to "saint", "ste" to "sainte", "ctre" to "centre", "ctr" to "centre", "cial" to "commercial",
        "bd" to "boulevard", "bld" to "boulevard", "av" to "avenue", "pl" to "place", "gal" to "general", "mal" to "marechal",
        "hop" to "hopital", "rte" to "route", "crs" to "cours", "imp" to "impasse", "cim" to "cimetiere", "eglise" to "eglise",
        "ecol" to "ecole", "coll" to "college", "prom" to "promenade", "res" to "residence", "zi" to "zi",
    )

    fun tokens(s: String): Set<String> =
        norm(s).split(' ').filter { it.isNotEmpty() }.map { ABBR[it] ?: it }.filter { it !in STOP_WORDS }.toSet()

    /** Les deux libellés de sens désignent-ils le même terminus ? */
    fun sameDirection(a: String, b: String): Boolean {
        if (norm(a) == norm(b)) return true
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        return ta.containsAll(tb) || tb.containsAll(ta)
    }

    /** Écart entre le nom choisi et un nom du flux (0 = identique ; null = pas le même arrêt). */
    fun score(sel: String, selCommune: String?, cand: String): Int? {
        var a = tokens(sel)
        var b = tokens(cand)
        val c = selCommune?.let { tokens(it) }.orEmpty()
        if (c.isNotEmpty()) {
            if ((a - c).isNotEmpty()) a = a - c
            if ((b - c).isNotEmpty()) b = b - c
        }
        if (a.isEmpty() || b.isEmpty()) return null
        return when {
            a == b -> 0
            b.containsAll(a) -> b.size - a.size
            a.containsAll(b) && b.size >= 2 -> a.size - b.size
            else -> null
        }
    }

    /** Passages du flux correspondant à l'arrêt/ligne/sens choisis (exact d'abord, puis tolérant). */
    fun rowsFor(sel: StopSelection, all: List<Passage>): List<Passage> {
        val lineRows = all.filter { it.line.equals(sel.line, true) }
        val dirRows = lineRows.filter { sameDirection(sel.direction, it.direction) }
        if (dirRows.isEmpty()) return emptyList()
        val exact = dirRows.filter { norm(it.station) == norm(sel.station) }
        if (exact.isNotEmpty()) return exact
        val selCommune = StopIndex.commune(sel.line, sel.station)
        val best = dirRows.map { it.station to it.commune }.distinct()
            .mapNotNull { (name, com) ->
                val s = score(sel.station, selCommune, name) ?: return@mapNotNull null
                val penalty = if (selCommune != null && com != null && norm(com) != norm(selCommune)) 5 else 0
                Triple(name, s + penalty, name)
            }
            .minByOrNull { it.second } ?: return emptyList()
        return dirRows.filter { it.station == best.first }
    }
}
