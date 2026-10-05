package fr.ilevia.departs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Widget d'écran d'accueil. Affiche les trajets choisis dans l'app sous forme de tuiles côte à côte :
 * le nombre de tuiles dépend de la largeur du widget (1x3 → 3 trajets, 1x5 → 5 trajets).
 * Toucher le widget le rafraîchit.
 */
class DepartureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        AlertScheduler.ensurePeriodicCheck(context)
    }

    private class TileData(val trip: Trip, val passages: List<Passage>?)

    companion object {
        const val ACTION_REFRESH = "fr.ilevia.departs.REFRESH"
        private const val MAX_TILES = 5
        private const val TILE_WIDTH_DP = 72

        private val TILE = intArrayOf(R.id.tile_0, R.id.tile_1, R.id.tile_2, R.id.tile_3, R.id.tile_4)
        private val BADGE = intArrayOf(R.id.badge_0, R.id.badge_1, R.id.badge_2, R.id.badge_3, R.id.badge_4)
        private val MINS = intArrayOf(R.id.mins_0, R.id.mins_1, R.id.mins_2, R.id.mins_3, R.id.mins_4)
        private val STATION = intArrayOf(R.id.station_0, R.id.station_1, R.id.station_2, R.id.station_3, R.id.station_4)
        private val DEP = intArrayOf(R.id.dep_0, R.id.dep_1, R.id.dep_2, R.id.dep_3, R.id.dep_4)
        private val LEAVE = intArrayOf(R.id.leave_0, R.id.leave_1, R.id.leave_2, R.id.leave_3, R.id.leave_4)
        private val BADGE_BG = intArrayOf(
            R.drawable.badge_0, R.drawable.badge_1, R.drawable.badge_2, R.drawable.badge_3,
            R.drawable.badge_4, R.drawable.badge_5, R.drawable.badge_6, R.drawable.badge_7,
        )

        /** Récupère les données en arrière-plan puis met à jour tous les widgets posés. */
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, DepartureWidget::class.java))
            if (ids.isEmpty()) return
            Thread {
                val data = load(app)
                val store = TripStore(app)
                ids.forEach { id ->
                    manager.updateAppWidget(id, build(app, data, tileCount(manager, id), store.showDeparture, store.showLeave))
                }
            }.start()
        }

        private fun tileCount(manager: AppWidgetManager, id: Int): Int {
            val widthDp = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            return ((widthDp - 12) / TILE_WIDTH_DP).coerceIn(1, MAX_TILES)
        }

        private fun load(context: Context): List<TileData> {
            val trips = TripStore(context).widgetTrips()
            if (trips.isEmpty()) return emptyList()
            val now = Instant.now()
            return try {
                IleviaApi.fetchAll(force = true)
                trips.map { t ->
                    TileData(t, IleviaApi.passagesFor(t.stop).filter { it.time.isAfter(now.minusSeconds(30)) })
                }
            } catch (e: Exception) {
                trips.map { TileData(it, null) }
            }
        }

        private fun build(context: Context, data: List<TileData>, count: Int, showDep: Boolean, showLeave: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_departure)
            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DepartureWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, refresh)
            v.setViewVisibility(R.id.widget_empty, if (data.isEmpty()) View.VISIBLE else View.GONE)

            val now = Instant.now()
            for (i in 0 until MAX_TILES) {
                val d = data.getOrNull(i)
                if (d == null || i >= count) {
                    v.setViewVisibility(TILE[i], View.GONE)
                    continue
                }
                v.setViewVisibility(TILE[i], View.VISIBLE)
                val line = d.trip.stop.line
                v.setTextViewText(BADGE[i], line)
                v.setInt(BADGE[i], "setBackgroundResource", BADGE_BG[badgeColorIndex(line)])
                v.setTextViewText(STATION[i], prettyStation(d.trip.stop.station))

                val next = d.passages?.firstOrNull()
                when {
                    d.passages == null -> {
                        v.setTextViewText(MINS[i], "Hors ligne")
                        hide(v, DEP[i]); hide(v, LEAVE[i])
                    }
                    next == null -> {
                        v.setTextViewText(MINS[i], "—")
                        v.setTextViewText(DEP[i], "Aucun passage")
                        v.setViewVisibility(DEP[i], if (showDep) View.VISIBLE else View.GONE)
                        hide(v, LEAVE[i])
                    }
                    else -> {
                        val mins = Duration.between(now, next.time).toMinutes().coerceAtLeast(0)
                        v.setTextViewText(MINS[i], if (mins <= 0) "Imminent" else "$mins min")
                        if (showDep) {
                            v.setTextViewText(DEP[i], "Dép. ${formatTime(next.time)}")
                            v.setViewVisibility(DEP[i], View.VISIBLE)
                        } else hide(v, DEP[i])
                        if (showLeave) {
                            val leave = d.trip.leaveTime(next.time)
                            val lm = Duration.between(now, leave).toMinutes()
                            v.setTextViewText(LEAVE[i], if (lm > 0) "Partir ${formatTime(leave)}" else "Partez !")
                            v.setViewVisibility(LEAVE[i], View.VISIBLE)
                        } else hide(v, LEAVE[i])
                    }
                }
            }
            return v
        }

        private fun hide(v: RemoteViews, id: Int) = v.setViewVisibility(id, View.GONE)

        private fun badgeColorIndex(line: String): Int =
            (line.toIntOrNull() ?: abs(line.hashCode())) % BADGE_BG.size

        private fun prettyStation(s: String): String =
            s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
