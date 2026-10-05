package fr.ilevia.departs

import android.content.Context
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Stockage local (SharedPreferences) des trajets enregistrés + du trajet affiché sur le widget. */
class TripStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trips", Context.MODE_PRIVATE)

    fun all(): List<Trip> {
        val raw = prefs.getString("trips", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Trip.fromJson(arr.getJSONObject(it)) }
    }

    fun save(trip: Trip) {
        val existing = all()
        val isNew = existing.none { it.id == trip.id }
        // Remplace en place pour ne pas changer l'ordre des trajets (et donc des tuiles du widget).
        write(if (isNew) existing + trip else existing.map { if (it.id == trip.id) trip else it })
        if (isNew) setWidgetTripIds(widgetTripIds() + trip.id)
    }

    fun delete(id: String) {
        val ids = widgetTripIds() - id
        write(all().filterNot { it.id == id })
        setWidgetTripIds(ids)
    }

    /** Trajets affichés sur le widget, dans l'ordre d'affichage (1ʳᵉ tuile = 1er de la liste). */
    fun widgetTripIds(): List<String> {
        val valid = all().map { it.id }.toSet()
        val raw = prefs.getString("widget_trips", null)
        val ids = if (raw != null) raw.split(",").filter { it.isNotBlank() }
        else listOfNotNull(prefs.getString("widget_trip", null)) // ancienne version : un seul trajet
        return ids.filter { it in valid }
    }

    fun setWidgetTripIds(ids: List<String>) {
        prefs.edit().putString("widget_trips", ids.joinToString(",")).apply()
    }

    fun toggleOnWidget(id: String) {
        val cur = widgetTripIds()
        setWidgetTripIds(if (id in cur) cur - id else cur + id)
    }

    fun widgetTrips(): List<Trip> {
        val byId = all().associateBy { it.id }
        return widgetTripIds().mapNotNull { byId[it] }
    }

    /** Trajets d'un widget précis (choisis à sa pose) ; à défaut, la liste « Sur le widget » de l'app. */
    fun tripIdsForWidget(id: Int): List<String> {
        val raw = prefs.getString("w_$id", null) ?: return widgetTripIds()
        val valid = all().map { it.id }.toSet()
        return raw.split(",").filter { it.isNotBlank() && it in valid }
    }

    fun setTripsForWidget(id: Int, ids: List<String>) {
        prefs.edit().putString("w_$id", ids.joinToString(",")).apply()
    }

    fun clearWidget(id: Int) {
        prefs.edit().remove("w_$id").apply()
    }

    fun tripsForWidget(id: Int): List<Trip> {
        val byId = all().associateBy { it.id }
        return tripIdsForWidget(id).mapNotNull { byId[it] }
    }

    var showDeparture: Boolean
        get() = prefs.getBoolean("show_departure", true)
        set(v) { prefs.edit().putBoolean("show_departure", v).apply() }

    var showStation: Boolean
        get() = prefs.getBoolean("show_station", true)
        set(v) { prefs.edit().putBoolean("show_station", v).apply() }

    var showLeave: Boolean
        get() = prefs.getBoolean("show_leave", true)
        set(v) { prefs.edit().putBoolean("show_leave", v).apply() }

    private fun write(list: List<Trip>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("trips", arr.toString()).apply()
    }
}

private val PARIS = ZoneId.of("Europe/Paris")

/** Le trajet doit-il être surveillé à cet instant (jour + plage horaire) ? */
fun Trip.isActiveAt(now: Instant): Boolean = enabled && windows().any { it.contains(now) }

fun AlertWindow.contains(now: Instant): Boolean {
    val z = ZonedDateTime.ofInstant(now, PARIS)
    if (z.dayOfWeek.value !in days) return false
    return (z.hour * 60 + z.minute) in startMinute..endMinute
}


/** Heure à laquelle il faut quitter son point de départ pour attraper [departure]. */
fun Trip.leaveTime(departure: Instant): Instant =
    departure.minusSeconds((walkMinutes + bufferMinutes) * 60L)

/** Stockage local des horaires favoris + état des alertes déjà envoyées. */
class FavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Favorite> {
        val arr = JSONArray(prefs.getString("favs", "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull { runCatching { Favorite.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun save(f: Favorite) {
        val cur = all()
        write(if (cur.any { it.id == f.id }) cur.map { if (it.id == f.id) f else it } else cur + f)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("favs", arr.toString()).apply()
    }

    // État du jour : alertes « T-N » déjà envoyées et dernier retard notifié (clé = jour).
    fun sentMinutes(id: String, day: String): Set<Int> {
        val v = prefs.getString("sent_$id", null) ?: return emptySet()
        val (d, list) = v.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return if (d == day) list.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet() else emptySet()
    }

    fun markSent(id: String, day: String, mins: Set<Int>) {
        prefs.edit().putString("sent_$id", day + "|" + (sentMinutes(id, day) + mins).joinToString(",")).apply()
    }

    fun lastDelay(id: String, day: String): Int? {
        val v = prefs.getString("delay_$id", null) ?: return null
        val p = v.split("|")
        return if (p.size == 2 && p[0] == day) p[1].toIntOrNull() else null
    }

    fun setLastDelay(id: String, day: String, d: Int) {
        prefs.edit().putString("delay_$id", "$day|$d").apply()
    }
}

/** Ordre d'affichage des trajets (« t:<id> ») et favoris (« f:<id> ») sur l'écran principal. */
class OrderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("order", Context.MODE_PRIVATE)
    fun get(): List<String> = (prefs.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun set(list: List<String>) { prefs.edit().putString("order", list.joinToString("\n")).apply() }
}

/** Ordre sauvegardé, complété par les éléments nouveaux (favoris d'abord) et débarrassé des supprimés. */
fun mergeOrder(order: List<String>, trips: List<Trip>, favs: List<Favorite>): List<String> {
    val existing = favs.map { "f:" + it.id } + trips.map { "t:" + it.id }
    return order.filter { it in existing } + existing.filter { it !in order }
}
