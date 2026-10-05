package fr.ilevia.departs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val targets = TARGETS
            .map { (cls, renderer) -> Triple(cls, renderer, manager.getAppWidgetIds(ComponentName(app, cls))) }
            .filter { it.third.isNotEmpty() }
        if (targets.isEmpty()) return
        Thread {
            val store = TripStore(app)
            val fetched = try { IleviaApi.fetchAll(force = true); true } catch (e: Exception) { false }
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
                    manager.updateAppWidget(id, renderer.render(WidgetCtx(app, data, w, h, store.showDeparture, store.showLeave, now, updated)))
                }
            }
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

private fun pretty(s: String): String =
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
    setInt(id, "setBackgroundResource", (if (round) BADGE_ROUND else BADGE_RECT)[colorIndex(line)])
}

private fun minutesUntil(c: WidgetCtx, p: Passage): Long = Duration.between(c.now, p.time).toMinutes().coerceAtLeast(0)

private fun minsText(m: Long) = if (m <= 0) "Imminent" else "$m min"

private fun leaveText(c: WidgetCtx, trip: Trip, p: Passage): String {
    val leave = trip.leaveTime(p.time)
    return if (Duration.between(c.now, leave).toMinutes() > 0) "Partir ${formatTime(leave)}" else "Partez !"
}

private fun depText(p: Passage) = "Dép. ${formatTime(p.time)}"

// ───────────────────────── Bandeau ─────────────────────────

object BandRenderer : Renderer {
    private val TILE = intArrayOf(R.id.tile_0, R.id.tile_1, R.id.tile_2, R.id.tile_3, R.id.tile_4)
    private val BADGE = intArrayOf(R.id.badge_0, R.id.badge_1, R.id.badge_2, R.id.badge_3, R.id.badge_4)
    private val MINS = intArrayOf(R.id.mins_0, R.id.mins_1, R.id.mins_2, R.id.mins_3, R.id.mins_4)
    private val STATION = intArrayOf(R.id.station_0, R.id.station_1, R.id.station_2, R.id.station_3, R.id.station_4)
    private val DEP = intArrayOf(R.id.dep_0, R.id.dep_1, R.id.dep_2, R.id.dep_3, R.id.dep_4)
    private val LEAVE = intArrayOf(R.id.leave_0, R.id.leave_1, R.id.leave_2, R.id.leave_3, R.id.leave_4)

    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_departure)
        v.setOnClickPendingIntent(R.id.widget_root, refreshIntent(c.context))
        v.show(R.id.widget_empty, c.data.isEmpty())
        val count = ((c.widthDp - 12) / 72).coerceIn(1, 5)
        for (i in 0 until 5) {
            val d = c.data.getOrNull(i)
            if (d == null || i >= count) { v.show(TILE[i], false); continue }
            v.show(TILE[i], true)
            v.badge(BADGE[i], d.trip.stop.line, round = false)
            v.setTextViewText(STATION[i], pretty(d.trip.stop.station))
            val next = d.next
            when {
                d.passages == null -> { v.setTextViewText(MINS[i], "Hors ligne"); v.text(DEP[i], null); v.text(LEAVE[i], null) }
                next == null -> {
                    v.setTextViewText(MINS[i], "—")
                    v.text(DEP[i], if (c.showDep) "Aucun passage" else null)
                    v.text(LEAVE[i], null)
                }
                else -> {
                    v.setTextViewText(MINS[i], minsText(minutesUntil(c, next)))
                    v.text(DEP[i], if (c.showDep) depText(next) else null)
                    v.text(LEAVE[i], if (c.showLeave) leaveText(c, d.trip, next) else null)
                }
            }
        }
        return v
    }
}

// ───────────────────────── Grand chiffre ─────────────────────────

object BigRenderer : Renderer {
    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_big)
        v.setOnClickPendingIntent(R.id.big_root, refreshIntent(c.context))
        val d = c.data.firstOrNull()
        v.show(R.id.big_empty, d == null)
        v.show(R.id.big_content, d != null)
        if (d == null) return v

        v.badge(R.id.big_badge, d.trip.stop.line, round = false)
        v.setTextViewText(R.id.big_station, pretty(d.trip.stop.station))
        v.setTextViewText(R.id.big_dir, "→ " + pretty(d.trip.stop.direction))
        // Sur un widget peu haut, on garde seulement le grand chiffre.
        val roomy = c.heightDp >= 100
        val next = d.next
        when {
            d.passages == null -> {
                v.setTextViewText(R.id.big_num, "!"); v.setTextViewText(R.id.big_unit, "hors ligne")
                v.text(R.id.big_dep, null); v.text(R.id.big_leave, null)
            }
            next == null -> {
                v.setTextViewText(R.id.big_num, "—"); v.setTextViewText(R.id.big_unit, "")
                v.text(R.id.big_dep, if (c.showDep) "Aucun passage" else null); v.text(R.id.big_leave, null)
            }
            else -> {
                val m = minutesUntil(c, next)
                v.setTextViewText(R.id.big_num, m.toString())
                v.setTextViewText(R.id.big_unit, "MIN")
                v.text(R.id.big_dep, if (c.showDep) depText(next) else null)
                v.text(R.id.big_leave, if (c.showLeave) leaveText(c, d.trip, next) else null)
            }
        }
        v.show(R.id.big_panel, roomy)
        return v
    }
}

// ───────────────────────── Liste de départs ─────────────────────────

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
        val rows = ((c.heightDp - 12 - 18) / 38).coerceIn(1, 4)
        for (i in 0 until 4) {
            val d = c.data.getOrNull(i)
            if (d == null || i >= rows) { v.show(ROW[i], false); continue }
            v.show(ROW[i], true)
            v.badge(BADGE[i], d.trip.stop.line, round = true)
            v.setTextViewText(DIR[i], pretty(d.trip.stop.direction))
            val next = d.next
            val station = pretty(d.trip.stop.station)
            when {
                d.passages == null -> { v.setTextViewText(SUB[i], station); v.setTextViewText(MINS[i], "Hors ligne"); v.text(LEAVE[i], null) }
                next == null -> { v.setTextViewText(SUB[i], station); v.setTextViewText(MINS[i], "—"); v.text(LEAVE[i], null) }
                else -> {
                    v.setTextViewText(SUB[i], if (c.showDep) "$station · ${depText(next)}" else station)
                    v.setTextViewText(MINS[i], minsText(minutesUntil(c, next)))
                    v.text(LEAVE[i], if (c.showLeave) leaveText(c, d.trip, next) else null)
                }
            }
        }
        return v
    }
}

// ───────────────────────── Grille de pastilles ─────────────────────────

object GridRenderer : Renderer {
    private val ROW = intArrayOf(R.id.grow_0, R.id.grow_1, R.id.grow_2)
    private val PILL = intArrayOf(R.id.gpill_0, R.id.gpill_1, R.id.gpill_2, R.id.gpill_3, R.id.gpill_4, R.id.gpill_5)
    private val BADGE = intArrayOf(R.id.gbadge_0, R.id.gbadge_1, R.id.gbadge_2, R.id.gbadge_3, R.id.gbadge_4, R.id.gbadge_5)
    private val TITLE = intArrayOf(R.id.gtitle_0, R.id.gtitle_1, R.id.gtitle_2, R.id.gtitle_3, R.id.gtitle_4, R.id.gtitle_5)
    private val SUB = intArrayOf(R.id.gsub_0, R.id.gsub_1, R.id.gsub_2, R.id.gsub_3, R.id.gsub_4, R.id.gsub_5)

    override fun render(c: WidgetCtx): RemoteViews {
        val v = RemoteViews(c.context.packageName, R.layout.widget_grid)
        v.setOnClickPendingIntent(R.id.grid_root, refreshIntent(c.context))
        v.show(R.id.grid_empty, c.data.isEmpty())
        val rows = ((c.heightDp - 12) / 44).coerceIn(1, 3)
        val cols = if (c.widthDp >= 200) 2 else 1
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
                v.setTextViewText(TITLE[slot], pretty(d.trip.stop.direction))
                val next = d.next
                v.setTextViewText(
                    SUB[slot],
                    when {
                        d.passages == null -> "Hors ligne"
                        next == null -> "Aucun passage"
                        else -> minsText(minutesUntil(c, next)) + if (c.showDep) " · ${formatTime(next.time)}" else ""
                    },
                )
            }
            v.show(ROW[r], rowVisible)
        }
        return v
    }
}
