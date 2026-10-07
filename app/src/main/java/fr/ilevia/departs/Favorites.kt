package fr.ilevia.departs

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

private val PARIS_ZONE = ZoneId.of("Europe/Paris")
private const val MATCH_TOLERANCE_MIN = 30L

/** Suivi des horaires favoris : retrouve le passage correspondant, calcule le retard, envoie les alertes. */
object FavoriteTracker {

    fun dayOf(now: Instant): String = ZonedDateTime.ofInstant(now, PARIS_ZONE).toLocalDate().toString()
    fun dowOf(now: Instant): Int = ZonedDateTime.ofInstant(now, PARIS_ZONE).dayOfWeek.value

    /** L'horaire favori d'aujourd'hui (Paris). */
    fun refToday(f: Favorite, now: Instant): Instant =
        ZonedDateTime.ofInstant(now, PARIS_ZONE).toLocalDate().atStartOfDay(PARIS_ZONE).plusMinutes(f.minute.toLong()).toInstant()

    /** Passage annoncé le plus proche de l'horaire favori (à ±30 min), ou null. */
    fun match(f: Favorite, passages: List<Passage>, now: Instant): Passage? {
        val ref = refToday(f, now)
        return passages
            .filter { abs(Duration.between(ref, it.time).toMinutes()) <= MATCH_TOLERANCE_MIN }
            .minByOrNull { abs(Duration.between(ref, it.time).seconds) }
    }

    /** Retard en minutes (négatif = en avance) du passage par rapport à l'horaire favori. */
    fun delayMinutes(f: Favorite, p: Passage, now: Instant): Int =
        (Duration.between(refToday(f, now), p.time).seconds / 60.0).roundToInt()

    private fun earliest(a: Instant?, b: Instant): Instant = if (a == null || b.isBefore(a)) b else a

    /** Un passage de suivi : alertes « T-N », alerte retard, et programmation du prochain passage. */
    fun tick(context: Context) {
        val now = Instant.now()
        val store = FavoriteStore(context)
        val favs = store.all().filter { it.enabled && dowOf(now) in it.days }
        if (favs.isEmpty()) return
        IleviaApi.fetchAll(force = true)
        var next: Instant? = null
        val day = dayOf(now)

        for (f in favs) {
            val ref = refToday(f, now)
            val start = ref.minusSeconds(45 * 60L)
            if (now.isBefore(start)) {
                if (Duration.between(now, start).toMinutes() <= 30) next = earliest(next, start)
                continue
            }
            val p = match(f, IleviaApi.passagesFor(f.stop), now)
            val inPeriod = !now.isAfter(ref.plusSeconds(20 * 60L)) || (p != null && p.time.isAfter(now.minusSeconds(60)))
            if (!inPeriod) { Notifier.cancelLive(context, f); continue }
            next = earliest(next, now.plusSeconds(60))
            if (p == null) { Notifier.cancelLive(context, f); continue }

            val secs = Duration.between(now, p.time).seconds
            val minsLeft = (secs / 60).toInt()
            val label = "${f.stop.line} · ${pretty(f.stop.station)}"
            val d = delayMinutes(f, p, now)
            val delayTxt = when {
                d >= 1 -> "retard de $d min (prévu ${fmtMinute(f.minute)})"
                d <= -1 -> "en avance de ${-d} min (prévu ${fmtMinute(f.minute)})"
                else -> "à l'heure"
            }

            // Suivi en direct (notification persistante, mise à jour chaque minute).
            if (secs > -60) Notifier.live(context, f, p, minsLeft, delayTxt) else Notifier.cancelLive(context, f)

            // Alertes « T-N » : on envoie une seule notification même si plusieurs seuils sont dépassés d'un coup.
            if (secs > 0) {
                val sent = store.sentMinutes(f.id, day)
                val due = f.notifyMinutes.filter { it >= minsLeft && it !in sent }
                if (due.isNotEmpty()) {
                    store.markSent(f.id, day, due.toSet())
                    val txt = (if (minsLeft <= 0) "Passe dans moins d'une minute" else "Passe dans $minsLeft min") +
                        " (${formatTime(p.time)}) · $delayTxt"
                    Notifier.favorite(context, f, f.id.hashCode(), "Bus $label", txt)
                }
            }

            // Alerte retard : à la première détection, puis à chaque changement d'au moins 2 min.
            if (f.notifyDelay && abs(d) >= f.delayThreshold) {
                val last = store.lastDelay(f.id, day)
                if (last == null || abs(d - last) >= 2) {
                    store.setLastDelay(f.id, day, d)
                    Notifier.favorite(
                        context, f, f.id.hashCode() + 1000, "Bus $label",
                        "Passage prévu à ${fmtMinute(f.minute)} : ${if (d > 0) "retard de $d min" else "en avance de ${-d} min"}, " +
                            "annoncé à ${formatTime(p.time)}.",
                    )
                }
            }
        }
        next?.let { AlertScheduler.scheduleTick(context, it) }
    }
}
