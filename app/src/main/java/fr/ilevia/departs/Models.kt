package fr.ilevia.departs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Un passage prévu à un arrêt, pour une ligne et un sens donnés. */
data class Passage(
    val station: String,
    val line: String,
    val direction: String,
    val time: Instant,
)

/** Identifie un « arrêt + ligne + sens » choisi par l'utilisateur. */
data class StopSelection(
    val station: String,
    val line: String,
    val direction: String,
) {
    fun label() = "$line → $direction · $station"

    fun matches(p: Passage) =
        p.station.equals(station, true) &&
            p.line.equals(line, true) &&
            p.direction.equals(direction, true)
}

/**
 * Trajet enregistré : l'arrêt à prendre + le temps nécessaire pour y aller.
 * L'alerte « il faut partir » est déclenchée [walkMinutes] + [bufferMinutes] avant le départ.
 * Elle n'est active que les jours [days] (1 = lundi … 7 = dimanche) entre [startMinute] et [endMinute]
 * (minutes depuis minuit, heure de Paris).
 */
data class Trip(
    val id: String,
    val name: String,
    val stop: StopSelection,
    val walkMinutes: Int,
    val bufferMinutes: Int,
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    /** Plages d'alerte supplémentaires (en plus de days/startMinute/endMinute). */
    val extraWindows: List<AlertWindow> = emptyList(),
) {
    fun windows(): List<AlertWindow> = listOf(AlertWindow(days, startMinute, endMinute)) + extraWindows

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        put("station", stop.station); put("line", stop.line); put("direction", stop.direction)
        put("walk", walkMinutes); put("buffer", bufferMinutes)
        put("days", days.joinToString(",")); put("start", startMinute); put("end", endMinute)
        put("enabled", enabled)
        put("extra", JSONArray().also { a -> extraWindows.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = Trip(
            id = o.getString("id"),
            name = o.getString("name"),
            stop = StopSelection(o.getString("station"), o.getString("line"), o.getString("direction")),
            walkMinutes = o.getInt("walk"),
            bufferMinutes = o.getInt("buffer"),
            days = o.getString("days").split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
            startMinute = o.getInt("start"),
            endMinute = o.getInt("end"),
            enabled = o.optBoolean("enabled", true),
            extraWindows = o.optJSONArray("extra")?.let { a -> (0 until a.length()).map { AlertWindow.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/** Plage d'alerte : certains jours (1 = lundi … 7 = dimanche), entre deux heures (minutes depuis minuit, Paris). */
data class AlertWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int) {
    fun toJson() = JSONObject().apply {
        put("days", days.joinToString(",")); put("start", startMinute); put("end", endMinute)
    }

    companion object {
        fun fromJson(o: JSONObject) = AlertWindow(
            days = o.getString("days").split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
            startMinute = o.getInt("start"),
            endMinute = o.getInt("end"),
        )
    }
}

/** Un tronçon d'itinéraire : monter sur [stop] et rester environ [rideMinutes] minutes dans le véhicule. */
data class Leg(val stop: StopSelection, val rideMinutes: Int)

/**
 * Itinéraire à plusieurs tronçons (ex. 84 → M2 → 32), à faire à partir de [departMinute] (heure à l'arrêt de départ).
 * Le plan est recalculé en temps réel : si un véhicule a du retard et que la correspondance est ratée,
 * le prochain passage possible est choisi.
 */
data class Route(
    val id: String,
    val name: String,
    val legs: List<Leg>,
    val walkMinutes: Int,
    val transferMinutes: Int,
    val bufferMinutes: Int,
    val departMinute: Int,
    val windows: List<AlertWindow>,
    val enabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        put("legs", JSONArray().also { a ->
            legs.forEach { l ->
                a.put(JSONObject().apply {
                    put("station", l.stop.station); put("line", l.stop.line); put("direction", l.stop.direction); put("ride", l.rideMinutes)
                })
            }
        })
        put("walk", walkMinutes); put("transfer", transferMinutes); put("buffer", bufferMinutes); put("depart", departMinute)
        put("windows", JSONArray().also { a -> windows.forEach { a.put(it.toJson()) } })
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Route {
            val la = o.getJSONArray("legs")
            val wa = o.getJSONArray("windows")
            return Route(
                id = o.getString("id"), name = o.getString("name"),
                legs = (0 until la.length()).map {
                    val l = la.getJSONObject(it)
                    Leg(StopSelection(l.getString("station"), l.getString("line"), l.getString("direction")), l.getInt("ride"))
                },
                walkMinutes = o.getInt("walk"), transferMinutes = o.getInt("transfer"),
                bufferMinutes = o.getInt("buffer"), departMinute = o.getInt("depart"),
                windows = (0 until wa.length()).map { AlertWindow.fromJson(wa.getJSONObject(it)) },
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}
