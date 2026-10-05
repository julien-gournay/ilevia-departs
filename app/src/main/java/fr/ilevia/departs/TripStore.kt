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
        val list = all().filterNot { it.id == trip.id } + trip
        write(list)
        if (widgetTripId() == null) setWidgetTrip(trip.id)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        if (widgetTripId() == id) setWidgetTrip(all().firstOrNull()?.id)
    }

    fun widgetTripId(): String? = prefs.getString("widget_trip", null)

    fun setWidgetTrip(id: String?) {
        prefs.edit().apply { if (id == null) remove("widget_trip") else putString("widget_trip", id) }.apply()
    }

    fun widgetTrip(): Trip? = all().let { l -> l.firstOrNull { it.id == widgetTripId() } ?: l.firstOrNull() }

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
