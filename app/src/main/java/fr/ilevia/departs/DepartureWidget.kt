package fr.ilevia.departs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Duration
import java.time.Instant

/** Widget d'écran d'accueil : prochain départ du trajet choisi. Toucher le widget le rafraîchit. */
class DepartureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        AlertScheduler.ensurePeriodicCheck(context)
    }

    companion object {
        const val ACTION_REFRESH = "fr.ilevia.departs.REFRESH"

        /** Récupère les données en arrière-plan puis met à jour tous les widgets posés. */
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, DepartureWidget::class.java))
            if (ids.isEmpty()) return
            Thread {
                val views = build(app)
                ids.forEach { manager.updateAppWidget(it, views) }
            }.start()
        }

        private fun build(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_departure)
            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DepartureWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, refresh)

            val trip = TripStore(context).widgetTrip()
            if (trip == null) {
                v.setTextViewText(R.id.widget_title, "Aucun trajet")
                v.setTextViewText(R.id.widget_next, "—")
                v.setTextViewText(R.id.widget_leave, "Ouvrez l'app pour en créer un")
                v.setTextViewText(R.id.widget_later, "")
                return v
            }

            v.setTextViewText(R.id.widget_title, "${trip.stop.line} → ${trip.stop.direction} · ${trip.stop.station}")
            try {
                val now = Instant.now()
                val upcoming = IleviaApi.passagesFor(trip.stop, force = true).filter { it.time.isAfter(now.minusSeconds(30)) }
                val next = upcoming.firstOrNull()
                if (next == null) {
                    v.setTextViewText(R.id.widget_next, "Pas de passage")
                    v.setTextViewText(R.id.widget_leave, "")
                    v.setTextViewText(R.id.widget_later, "")
                } else {
                    val mins = Duration.between(now, next.time).toMinutes().coerceAtLeast(0)
                    v.setTextViewText(R.id.widget_next, if (mins <= 0) "Imminent" else "$mins min")
                    val leave = trip.leaveTime(next.time)
                    val leaveMins = Duration.between(now, leave).toMinutes()
                    v.setTextViewText(
                        R.id.widget_leave,
                        if (leaveMins > 0) "Partir à ${formatTime(leave)} (dans $leaveMins min)" else "Partez maintenant !",
                    )
                    v.setTextViewText(
                        R.id.widget_later,
                        "Départ ${formatTime(next.time)}" +
                            upcoming.drop(1).take(2).joinToString("") { " · ${formatTime(it.time)}" },
                    )
                }
            } catch (e: Exception) {
                v.setTextViewText(R.id.widget_next, "Hors ligne")
                v.setTextViewText(R.id.widget_leave, "Touchez pour réessayer")
                v.setTextViewText(R.id.widget_later, "")
            }
            return v
        }
    }
}
