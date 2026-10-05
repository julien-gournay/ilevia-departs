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

    var showDeparture: Boolean
        get() = prefs.getBoolean("show_departure", true)
        set(v) { prefs.edit().putBoolean("show_departure", v).apply() }

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
fun Trip.isActiveAt(now: Instant): Boolean {
    val z = ZonedDateTime.ofInstant(now, PARIS)
    if (!enabled || z.dayOfWeek.value !in days) return false
    val minute = z.hour * 60 + z.minute
    return minute in startMinute..endMinute
}

/** Heure à laquelle il faut quitter son point de départ pour attraper [departure]. */
fun Trip.leaveTime(departure: Instant): Instant =
    departure.minusSeconds((walkMinutes + bufferMinutes) * 60L)
