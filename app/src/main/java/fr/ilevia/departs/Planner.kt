package fr.ilevia.departs

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val PARIS_ZONE = ZoneId.of("Europe/Paris")

/** Résultat du calcul pour un tronçon : départ du véhicule et arrivée estimée (null = pas de passage trouvé). */
data class LegPlan(val leg: Leg, val departure: Instant?, val arrival: Instant?)

data class RoutePlan(val legs: List<LegPlan>, val complete: Boolean) {
    val firstDeparture: Instant? get() = legs.firstOrNull()?.departure
    val arrival: Instant? get() = if (complete) legs.lastOrNull()?.arrival else null
}

/** Heure « cible » du jour à l'arrêt de départ (Paris). */
fun Route.targetToday(now: Instant): Instant =
    ZonedDateTime.ofInstant(now, PARIS_ZONE).toLocalDate().atStartOfDay(PARIS_ZONE).plusMinutes(departMinute.toLong()).toInstant()

/** Quand quitter le point de départ pour attraper le premier véhicule. */
fun Route.leaveTime(plan: RoutePlan): Instant? =
    plan.firstDeparture?.minusSeconds((walkMinutes + bufferMinutes) * 60L)

/**
 * Calcule l'enchaînement des tronçons à partir de maintenant (ou de l'heure cible si elle n'est pas encore passée).
 * Pour chaque tronçon on prend le premier passage possible : si une correspondance est ratée à cause d'un retard,
 * le passage suivant est automatiquement choisi.
 */
fun planRoute(route: Route, now: Instant, passages: (StopSelection) -> List<Passage>): RoutePlan {
    val target = route.targetToday(now)
    val earliestNow = now.plusSeconds(route.walkMinutes * 60L)
    var t = if (target.isAfter(earliestNow)) target else earliestNow
    val out = ArrayList<LegPlan>()
    var broken = false
    for (leg in route.legs) {
        if (broken) { out += LegPlan(leg, null, null); continue }
        val dep = passages(leg.stop).filter { !it.time.isBefore(t) }.minByOrNull { it.time }?.time
        if (dep == null) { out += LegPlan(leg, null, null); broken = true; continue }
        val arr = dep.plus(Duration.ofMinutes(leg.rideMinutes.toLong()))
        out += LegPlan(leg, dep, arr)
        t = arr.plusSeconds(route.transferMinutes * 60L)
    }
    return RoutePlan(out, complete = !broken && out.isNotEmpty())
}

/** Résumé court pour les notifications : « 84 08:12 › M2 08:30 › 32 08:48 · arrivée 09:05 ». */
fun RoutePlan.summary(): String {
    val steps = legs.joinToString(" › ") { l -> "${l.leg.stop.line} ${l.departure?.let { formatTime(it) } ?: "?"}" }
    return steps + (arrival?.let { " · arrivée ${formatTime(it)}" } ?: "")
}
