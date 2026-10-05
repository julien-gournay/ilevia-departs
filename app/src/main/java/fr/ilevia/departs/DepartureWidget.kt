package fr.ilevia.departs

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Base commune des 4 widgets : toute mise à jour passe par [WidgetUpdater]. */
open class BaseWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // Simple redimensionnement : on réutilise les données en cache (pas de nouvel appel réseau si < 30 s).
        WidgetUpdater.refreshAll(context, force = false)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val store = TripStore(context)
        ids.forEach { store.clearWidget(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) WidgetUpdater.refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        AlertScheduler.ensurePeriodicCheck(context)
    }
}

/** Bandeau : tuiles côte à côte (1×3, 1×5…). */
class DepartureWidget : BaseWidget() {
    companion object {
        fun refreshAll(context: Context) = WidgetUpdater.refreshAll(context)
    }
}

/** Grand chiffre : un seul trajet, minutes en gros (2×2). */
class BigWidget : BaseWidget()

/** Liste de départs : une ligne par trajet (4×2, 4×3…). */
class BoardWidget : BaseWidget()

/** Grille de pastilles : 1 ou 2 colonnes selon la largeur. */
class GridWidget : BaseWidget()
