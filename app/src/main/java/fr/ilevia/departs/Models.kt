package fr.ilevia.departs

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
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        put("station", stop.station); put("line", stop.line); put("direction", stop.direction)
        put("walk", walkMinutes); put("buffer", bufferMinutes)
        put("days", days.joinToString(",")); put("start", startMinute); put("end", endMinute)
        put("enabled", enabled)
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
        )
    }
}
