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
    val commune: String? = null,
)

/** Identifie un « arrêt + ligne + sens » choisi par l'utilisateur. */
data class StopSelection(
    val station: String,
    val line: String,
    val direction: String,
) {
    fun label() = "$line → $direction · $station"

    fun matches(p: Passage) =
        norm(p.station) == norm(station) &&
            p.line.equals(line, true) &&
            norm(p.direction) == norm(direction)
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

/**
 * Horaire de passage favori : « le 84 de 08:12 à Chemin des Vaches », les jours choisis.
 * L'app suit le passage le plus proche de cet horaire, affiche son retard et prévient
 * [notifyMinutes] minutes avant (et en cas de retard d'au moins [delayThreshold] min si [notifyDelay]).
 */
data class Favorite(
    val id: String,
    val name: String,
    val stop: StopSelection,
    val minute: Int,
    val days: Set<Int>,
    val notifyMinutes: Set<Int>,
    val notifyDelay: Boolean,
    val delayThreshold: Int,
    val enabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        put("station", stop.station); put("line", stop.line); put("direction", stop.direction)
        put("minute", minute); put("days", days.joinToString(","))
        put("notify", notifyMinutes.sorted().joinToString(","))
        put("notifyDelay", notifyDelay); put("delayThreshold", delayThreshold); put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Favorite(
            id = o.getString("id"), name = o.getString("name"),
            stop = StopSelection(o.getString("station"), o.getString("line"), o.getString("direction")),
            minute = o.getInt("minute"),
            days = o.getString("days").split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
            notifyMinutes = o.optString("notify", "").split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
            notifyDelay = o.optBoolean("notifyDelay", true),
            delayThreshold = o.optInt("delayThreshold", 3),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

/** Nom normalisé pour comparer des libellés (accents, casse, ponctuation). */
fun norm(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
