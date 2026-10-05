package fr.ilevia.departs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

const val ACTION_REFRESH = "fr.ilevia.departs.REFRESH"

/** Un trajet + ses prochains passages (null = impossible de charger). */
class TileData(val trip: Trip, val passages: List<Passage>?) {
    val next: Passage? get() = passages?.firstOrNull()
}

/** Tout ce dont un widget a besoin pour se dessiner. */
class WidgetCtx(
    val context: Context,
    val data: List<TileData>,
    val widthDp: Int,
    val heightDp: Int,
    val showDep: Boolean,
    val showLeave: Boolean,
    val showStation: Boolean,
    val now: Instant,
    val updatedAt: String,
)

interface Renderer {
    fun render(c: WidgetCtx): RemoteViews
}

object WidgetUpdater {
    private val TARGETS: List<Pair<Class<*>, Renderer>> = listOf(
        DepartureWidget::class.java to BandRenderer,
        BigWidget::class.java to BigRenderer,
        BoardWidget::class.java to BoardRenderer,
        GridWidget::class.java to GridRenderer,
    )

    /** Récupère les données une fois, puis redessine tous les widgets posés (tous types confondus). */
    fun refreshAll(context: Context, force: Boolean = true, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val targets = TARGETS
            .map { (cls, renderer) -> Triple(cls, renderer, manager.getAppWidgetIds(ComponentName(app, cls))) }
            .filter { it.third.isNotEmpty() }
        if (targets.isEmpty()) { onDone?.invoke(); return }
        Thread {
          try {
            LineColors.init(app)
            val store = TripStore(app)
            val fetched = try { IleviaApi.fetchAll(force = force); true } catch (e: Exception) { false }
            val now = Instant.now()
            val updated = formatTime(now)
            for ((_, renderer, ids) in targets) {
                for (id in ids) {
                    val opts = manager.getAppWidgetOptions(id)
                    val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                    val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70)
                    val data = store.tripsForWidget(id).map { t ->
                        TileData(t, if (fetched) IleviaApi.passagesFor(t.stop).filter { it.time.isAfter(now.minusSeconds(30)) } else null)
                    }
                    manager.updateAppWidget(id, renderer.render(WidgetCtx(app, data, w, h, store.showDeparture, store.showLeave, store.showStation, now, updated)))
                }
            }
          } finally { onDone?.invoke() }
        }.start()
    }
}

// ───────────────────────── Aides communes ─────────────────────────

private val BADGE_RECT = intArrayOf(
    R.drawable.badge_0, R.drawable.badge_1, R.drawable.badge_2, R.drawable.badge_3,
    R.drawable.badge_4, R.drawable.badge_5, R.drawable.badge_6, R.drawable.badge_7,
)
private val BADGE_ROUND = intArrayOf(
    R.drawable.badge_round_0, R.drawable.badge_round_1, R.drawable.badge_round_2, R.drawable.badge_round_3,
    R.drawable.badge_round_4, R.drawable.badge_round_5, R.drawable.badge_round_6, R.drawable.badge_round_7,
)

private fun colorIndex(line: String): Int = (line.toIntOrNull() ?: abs(line.hashCode())) % BADGE_RECT.size

internal fun pretty(s: String): String =
    s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context, 0,
    Intent(context, DepartureWidget::class.java).setAction(ACTION_REFRESH),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private fun RemoteViews.text(id: Int, t: String?) {
    if (t == null) setViewVisibility(id, View.GONE)
    else { setTextViewText(id, t); setViewVisibility(id, View.VISIBLE) }
}

private fun RemoteViews.show(id: Int, visible: Boolean) =
    setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)

private fun RemoteViews.badge(id: Int, line: String, round: Boolean) {
    setTextViewText(id, line)
    val bg = LineColors.bg(line)
    if (bg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setInt(id, "setBackgroundResource", if (round) R.drawable.badge_round_white else R.drawable.badge_white)
        setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(bg))
        setTextColor(id, LineColors.fg(line) ?: Color.WHITE)
    } else {
        setInt(id, "setBackgroundResource", (if (round) BADGE_ROUND else BADGE_RECT)[colorIndex(line)])
        setTextColor(id, Color.WHITE)
    }
}

private fun minutesUntil(c: WidgetCtx, p: Passage): Long = Duration.between(c.now, p.time).toMinutes().coerceAtLeast(0)

private fun minsText(m: Long) = if (m <= 0) "Proche" else "$m min"

private fun leaveText(c: WidgetCtx, trip: Trip, p: Passage): String {
    val leave = trip.leaveTime(p.time)
    return if (Duration.between(c.now, leave).toMinutes() > 0) "Partir ${formatTime(leave)}" else "Partez !"
}

/** Taille de texte (sp) calculée selon la taille réelle du widget. */
private fun RemoteViews.sp(id: Int, size: Float) = setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size)

/** Taille d'une vue en dp (Android 12+ seulement ; sans effet avant). */
private fun RemoteViews.box(id: Int, widthDp: Float? = null, heightDp: Float? = null) {
    if (Build.VERSION.SDK_INT >= 31) {
        if (widthDp != null) setViewLayoutWidth(id, widthDp, TypedValue.COMPLEX_UNIT_DIP)
        if (heightDp != null) setViewLayoutHeight(id, heightDp, TypedValue.COMPLEX_UNIT_DIP)
    }
}

private fun depText(p: Passage) = "Dép. ${formatTime(p.time)}"

// ───────────────────────── Bandeau ─────────────────────────
// Adaptatif : nombre de tuiles selon la largeur, taille des chiffres selon la hauteur,
// lignes secondaires (arrêt, départ, « partir ») retirées quand la place manque.

object BandRenderer : Renderer {
    private val TILE = intArrayOf(R.id.tile_0, R.id.tile_1, R.id.tile_2, R.id.tile_3, R.id.tile_4)
    private val BADGE = intArrayOf(R.id.badge_0, R.id.badge_1, R.id.badge_2, R.id.badge_3, R.id.badge_4)
    private val MINS = intArrayOf(R.id.mins_0, R.id.mins_1, R.id.mins_2, R.id.mins_3, R.id.mins_4)
    private val UNIT = intArrayOf(R.id.unit_0, R.id.unit_1, R.id.unit_2, R.id.unit_3, R.id.unit_4)
    private val STATION = intArrayOf(R.id.station_0, R.id.station_1, R.id.station_2, R.id.station_3, R.id.station_4)
    private val DEP = intArrayOf(R.id.dep_0, R.id.dep_1, R.id.dep_2, R.id.dep_3, R.id.dep_4)
    private val LEAVE = intArrayOf(R.id.leave_0, R.id.leave_1, R.id.leave_2, R.id.leave_3, R.id.leave_4)

    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_departure)
        v.setOnClickPendingIntent(R.id.widget_root, refreshIntent(c.context))
        v.show(R.id.widget_empty, c.data.isEmpty())

        val maxByWidth = ((c.widthDp - 12) / 72).coerceIn(1, 5)
        val count = if (c.data.isEmpty()) 1 else minOf(maxByWidth, c.data.size)
        val tileW = (c.widthDp - 12f) / count
        val innerH = (c.heightDp - 12f).coerceAtLeast(30f)

        val lineSz = (innerH * 0.15f).coerceIn(9f, 13f)
        val minsSz = minOf((innerH * 0.36f).coerceIn(15f, 42f), ((tileW - 56f) / 1.2f).coerceAtLeast(12f))
        val rowH = maxOf(minsSz * 1.25f, 20f)
        val maxLines = ((innerH - rowH) / (lineSz * 1.3f)).toInt().coerceIn(0, 3)
        // Lignes voulues (0 = arrêt, 1 = départ, 2 = partir) ; on retire d'abord l'arrêt, puis le départ.
        var wanted = listOfNotNull(if (c.showStation) 0 else null, if (c.showDep) 1 else null, if (c.showLeave) 2 else null)
        while (wanted.size > maxLines) wanted = wanted.drop(1)

        for (i in 0 until 5) {
            val d = c.data.getOrNull(i)
            if (d == null || i >= count) { v.show(TILE[i], false); continue }
            v.show(TILE[i], true)
            v.badge(BADGE[i], d.trip.stop.line, round = false)
            v.sp(BADGE[i], (minsSz * 0.5f).coerceIn(10f, 18f))
            v.sp(UNIT[i], (minsSz * 0.42f).coerceIn(9f, 14f))
            v.sp(STATION[i], lineSz); v.sp(DEP[i], lineSz); v.sp(LEAVE[i], lineSz)

            val next = d.next
            var mins = "—"; var showUnit = false; var depLine: String? = null; var leaveLine: String? = null
            var size = minsSz
            when {
                d.passages == null -> { mins = "Hors ligne"; size = minsSz * 0.5f }
                next == null -> { depLine = "Aucun passage" }
                else -> {
                    val m = minutesUntil(c, next)
                    if (m <= 0) { mins = "Proche"; size = minsSz * 0.55f } else { mins = m.toString(); showUnit = true }
                    depLine = depText(next)
                    leaveLine = leaveText(c, d.trip, next)
                }
            }
            v.setTextViewText(MINS[i], mins); v.sp(MINS[i], size); v.show(UNIT[i], showUnit)
            v.text(STATION[i], if (0 in wanted) pretty(d.trip.stop.station) else null)
            v.text(DEP[i], if (1 in wanted) depLine else null)
            v.text(LEAVE[i], if (2 in wanted) leaveLine else null)
        }
        return v
    }
}

// ───────────────────────── Grand chiffre ─────────────────────────
// Adaptatif : le chiffre grandit avec le widget ; le panneau d'infos n'apparaît que s'il y a la place,
// et perd ses lignes une à une (d'abord l'heure de départ, puis « partir »).

object BigRenderer : Renderer {
    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_big)
        v.setOnClickPendingIntent(R.id.big_root, refreshIntent(c.context))
        val d = c.data.firstOrNull()
        v.show(R.id.big_empty, d == null)
        v.show(R.id.big_content, d != null)
        if (d == null) return v

        val w = c.widthDp.toFloat()
        val h = c.heightDp.toFloat()
        val numByW = (w - 24f) / 1.75f
        var numSz = minOf(numByW, (h - 48f) * 0.62f).coerceIn(26f, 110f)

        // Combien de lignes de 15 dp tient le panneau sous le chiffre ?
        var panelLines = ((h - 20f - 24f - numSz * 1.15f - 14f) / 15f).toInt()
        if (panelLines < 1) {
            panelLines = 0
            numSz = minOf(numByW, (h - 44f) / 1.15f).coerceIn(26f, 130f)
        }
        val keepLeave = c.showLeave && panelLines >= 2
        val keepDep = c.showDep && panelLines >= (if (keepLeave) 3 else 2)

        v.badge(R.id.big_badge, d.trip.stop.line, round = false)
        v.text(R.id.big_station, if (c.showStation && w >= 110f) stopLabel(d.trip.stop.station, d.trip.stop.line) else null)
        v.setTextViewText(R.id.big_dir, "→ " + pretty(d.trip.stop.direction))
        v.sp(R.id.big_num, numSz)
        v.sp(R.id.big_unit, (numSz * 0.27f).coerceIn(11f, 30f))

        val next = d.next
        when {
            d.passages == null -> {
                v.setTextViewText(R.id.big_num, "!"); v.setTextViewText(R.id.big_unit, "hors ligne")
                v.text(R.id.big_dep, null); v.text(R.id.big_leave, null)
            }
            next == null -> {
                v.setTextViewText(R.id.big_num, "—"); v.setTextViewText(R.id.big_unit, "")
                v.text(R.id.big_dep, if (keepDep) "Aucun passage" else null); v.text(R.id.big_leave, null)
            }
            else -> {
                v.setTextViewText(R.id.big_num, minutesUntil(c, next).toString())
                v.setTextViewText(R.id.big_unit, "MIN")
                v.text(R.id.big_dep, if (keepDep) depText(next) else null)
                v.text(R.id.big_leave, if (keepLeave) leaveText(c, d.trip, next) else null)
            }
        }
        v.show(R.id.big_panel, panelLines >= 1)
        return v
    }
}

// ───────────────────────── Liste de départs ─────────────────────────
// Adaptatif : l'en-tête disparaît si le widget est bas, les lignes se partagent la hauteur,
// et les textes grossissent avec la hauteur de chaque ligne.

object BoardRenderer : Renderer {
    private val ROW = intArrayOf(R.id.brow_0, R.id.brow_1, R.id.brow_2, R.id.brow_3)
    private val BADGE = intArrayOf(R.id.bbadge_0, R.id.bbadge_1, R.id.bbadge_2, R.id.bbadge_3)
    private val DIR = intArrayOf(R.id.bdir_0, R.id.bdir_1, R.id.bdir_2, R.id.bdir_3)
    private val SUB = intArrayOf(R.id.bsub_0, R.id.bsub_1, R.id.bsub_2, R.id.bsub_3)
    private val MINS = intArrayOf(R.id.bmins_0, R.id.bmins_1, R.id.bmins_2, R.id.bmins_3)
    private val LEAVE = intArrayOf(R.id.bleave_0, R.id.bleave_1, R.id.bleave_2, R.id.bleave_3)

    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_board)
        v.setOnClickPendingIntent(R.id.board_root, refreshIntent(c.context))
        v.setTextViewText(R.id.board_updated, "⟳ ${c.updatedAt}")
        v.show(R.id.board_empty, c.data.isEmpty())

        val headerShown = c.heightDp >= 76
        v.show(R.id.board_header, headerShown)
        val innerH = c.heightDp - 12f - (if (headerShown) 18f else 0f)
        val rowsMax = (innerH / 38f).toInt().coerceIn(1, 4)
        val rows = if (c.data.isEmpty()) 1 else minOf(rowsMax, c.data.size)
        val rowH = innerH / rows
        val compact = c.widthDp < 200

        val dirSz = (rowH * 0.34f).coerceIn(12f, 18f)
        val subSz = (rowH * 0.24f).coerceIn(9f, 13f)
        val minsSz = minOf((rowH * 0.52f).coerceIn(15f, 30f), (c.widthDp * 0.11f).coerceAtLeast(14f))
        val badgeSz = (rowH * 0.78f).coerceIn(26f, 40f)

        for (i in 0 until 4) {
            val d = c.data.getOrNull(i)
            if (d == null || i >= rows) { v.show(ROW[i], false); continue }
            v.show(ROW[i], true)
            v.badge(BADGE[i], d.trip.stop.line, round = true)
            v.box(BADGE[i], badgeSz, badgeSz)
            v.sp(BADGE[i], badgeSz * 0.43f)
            v.setTextViewText(DIR[i], pretty(d.trip.stop.direction)); v.sp(DIR[i], dirSz)
            v.sp(SUB[i], subSz); v.sp(MINS[i], minsSz); v.sp(LEAVE[i], subSz)

            val next = d.next
            val station = if (c.showStation) stopLabel(d.trip.stop.station, d.trip.stop.line) else null
            when {
                d.passages == null -> { v.text(SUB[i], station); v.setTextViewText(MINS[i], "Hors ligne"); v.text(LEAVE[i], null) }
                next == null -> { v.text(SUB[i], station); v.setTextViewText(MINS[i], "—"); v.text(LEAVE[i], null) }
                else -> {
                    val m = minutesUntil(c, next)
                    val sub = listOfNotNull(station, if (c.showDep) depText(next) else null).joinToString(" · ")
                    v.text(SUB[i], sub.ifEmpty { null })
                    v.setTextViewText(MINS[i], if (m <= 0) "Proche" else if (compact) "$m′" else "$m min")
                    v.text(LEAVE[i], if (c.showLeave) leaveText(c, d.trip, next) else null)
                }
            }
        }
        return v
    }
}

// ───────────────────────── Grille de pastilles ─────────────────────────
// Adaptatif : 1 ou 2 colonnes selon la largeur, 1 à 3 rangées selon la hauteur (jamais plus que de trajets),
// textes proportionnels à la hauteur des pastilles, ligne « arrêt » retirée si elle est trop serrée.

object GridRenderer : Renderer {
    private val ROW = intArrayOf(R.id.grow_0, R.id.grow_1, R.id.grow_2)
    private val PILL = intArrayOf(R.id.gpill_0, R.id.gpill_1, R.id.gpill_2, R.id.gpill_3, R.id.gpill_4, R.id.gpill_5)
    private val BADGE = intArrayOf(R.id.gbadge_0, R.id.gbadge_1, R.id.gbadge_2, R.id.gbadge_3, R.id.gbadge_4, R.id.gbadge_5)
    private val MINS = intArrayOf(R.id.gmins_0, R.id.gmins_1, R.id.gmins_2, R.id.gmins_3, R.id.gmins_4, R.id.gmins_5)
    private val DEP = intArrayOf(R.id.gdep_0, R.id.gdep_1, R.id.gdep_2, R.id.gdep_3, R.id.gdep_4, R.id.gdep_5)
    private val TITLE = intArrayOf(R.id.gtitle_0, R.id.gtitle_1, R.id.gtitle_2, R.id.gtitle_3, R.id.gtitle_4, R.id.gtitle_5)
    private val STATION = intArrayOf(R.id.gstation_0, R.id.gstation_1, R.id.gstation_2, R.id.gstation_3, R.id.gstation_4, R.id.gstation_5)

    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_grid)
        v.setOnClickPendingIntent(R.id.grid_root, refreshIntent(c.context))
        v.show(R.id.grid_empty, c.data.isEmpty())

        val cols = if (c.widthDp >= 190) 2 else 1
        val rowsMax = ((c.heightDp - 12) / 48).coerceIn(1, 3)
        val rowsNeeded = if (c.data.isEmpty()) 1 else (c.data.size + cols - 1) / cols
        val rows = minOf(rowsMax, rowsNeeded)
        val pillH = (c.heightDp - 12f) / rows - 4f
        val pillW = (c.widthDp - 12f) / cols - 8f

        val minsSz = minOf((pillH * 0.34f).coerceIn(14f, 30f), ((pillW - 76f) / 3.3f).coerceAtLeast(12f))
        val titleSz = (pillH * 0.2f).coerceIn(10f, 15f)
        val stationSz = (titleSz * 0.82f).coerceAtLeast(9f)
        val depSz = (pillH * 0.17f).coerceIn(9f, 13f)
        val badgeSz = (pillH * 0.42f).coerceIn(20f, 34f)
        val stationShown = c.showStation && pillH >= 54f

        for (r in 0 until 3) {
            var rowVisible = false
            for (col in 0 until 2) {
                val slot = r * 2 + col
                val k = r * cols + col
                val d = if (col < cols && r < rows) c.data.getOrNull(k) else null
                if (d == null) { v.show(PILL[slot], false); continue }
                rowVisible = true
                v.show(PILL[slot], true)
                v.badge(BADGE[slot], d.trip.stop.line, round = true)
                v.box(BADGE[slot], badgeSz, badgeSz)
                v.sp(BADGE[slot], badgeSz * 0.46f)
                v.sp(MINS[slot], minsSz); v.sp(DEP[slot], depSz); v.sp(TITLE[slot], titleSz); v.sp(STATION[slot], stationSz)
                v.setTextViewText(TITLE[slot], pretty(d.trip.stop.direction))
                v.text(STATION[slot], if (stationShown) stopLabel(d.trip.stop.station, d.trip.stop.line) else null)
                val next = d.next
                when {
                    d.passages == null -> { v.setTextViewText(MINS[slot], "Hors ligne"); v.text(DEP[slot], null) }
                    next == null -> { v.setTextViewText(MINS[slot], "—"); v.text(DEP[slot], null) }
                    else -> {
                        v.setTextViewText(MINS[slot], minsText(minutesUntil(c, next)))
                        v.text(DEP[slot], if (c.showDep) formatTime(next.time) else null)
                    }
                }
            }
            v.show(ROW[r], rowVisible)
        }
        return v
    }
}
