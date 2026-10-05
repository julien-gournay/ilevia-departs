package fr.ilevia.departs

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "leave_alerts"
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Paris"))

fun formatTime(i: Instant): String = HHMM.format(i)

object AlertScheduler {
    private const val WORK_NAME = "ilevia_check"

    /** Vérification périodique (minimum 15 min imposé par Android) qui programme ensuite des alarmes précises. */
    fun ensurePeriodicCheck(context: Context) {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Programme l'alarme « il faut partir » pour [trip] à l'heure [at]. */
    fun scheduleLeaveAlarm(context: Context, trip: Trip, departure: Instant, at: Instant) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlertReceiver::class.java)
            .putExtra("trip_id", trip.id)
            .putExtra("departure", departure.toEpochMilli())
        val pi = PendingIntent.getBroadcast(
            context, trip.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = at.toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 1_000)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /** Alarme « il faut partir » pour un itinéraire (le plan est recalculé quand elle se déclenche). */
    fun scheduleRouteAlarm(context: Context, route: Route, at: Instant) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlertReceiver::class.java).putExtra("route_id", route.id)
        val pi = PendingIntent.getBroadcast(
            context, route.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = at.toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 1_000)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /**
     * Parcourt les trajets actifs, récupère le prochain départ encore atteignable,
     * programme l'alarme et rafraîchit le widget.
     */
    fun checkAll(context: Context) {
        val now = Instant.now()
        val trips = TripStore(context).all().filter { it.isActiveAt(now) }
        for (trip in trips) {
            val next = IleviaApi.passagesFor(trip.stop, force = true)
                .firstOrNull { trip.leaveTime(it.time).isAfter(now) } ?: continue
            val leaveAt = trip.leaveTime(next.time)
            // On ne programme que les alarmes proches ; le worker repassera ensuite.
            if (Duration.between(now, leaveAt).toMinutes() <= 30) {
                scheduleLeaveAlarm(context, trip, next.time, leaveAt)
            }
        }
        for (route in RouteStore(context).all().filter { it.isActiveAt(now) }) {
            val plan = planRoute(route, now) { IleviaApi.passagesFor(it, force = true) }
            val leaveAt = route.leaveTime(plan) ?: continue
            if (leaveAt.isAfter(now) && Duration.between(now, leaveAt).toMinutes() <= 30) scheduleRouteAlarm(context, route, leaveAt)
        }
        DepartureWidget.refreshAll(context)
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        AlertScheduler.checkAll(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("route_id")?.let { handleRoute(context, it, goAsync()); return }
        val tripId = intent.getStringExtra("trip_id") ?: return
        val departure = Instant.ofEpochMilli(intent.getLongExtra("departure", 0L))
        val trip = TripStore(context).all().firstOrNull { it.id == tripId } ?: return
        if (!trip.isActiveAt(Instant.now())) return
        Notifier.leaveNow(context, trip, departure)
        // Prépare l'alerte suivante sans attendre le prochain passage du worker.
        val pending = goAsync()
        Thread {
            try { AlertScheduler.checkAll(context) } catch (_: Exception) {} finally { pending.finish() }
        }.start()
    }
}

/** Recalcule l'itinéraire au moment de l'alarme : si un retard décale le départ, on reprogramme au lieu de notifier trop tôt. */
private fun handleRoute(context: Context, routeId: String, pending: BroadcastReceiver.PendingResult) {
    val route = RouteStore(context).all().firstOrNull { it.id == routeId }
    if (route == null || !route.isActiveAt(Instant.now())) { pending.finish(); return }
    Thread {
        try {
            val plan = planRoute(route, Instant.now()) { IleviaApi.passagesFor(it, force = true) }
            val leaveAt = route.leaveTime(plan)
            if (leaveAt != null) {
                if (leaveAt.isAfter(Instant.now().plusSeconds(90))) AlertScheduler.scheduleRouteAlarm(context, route, leaveAt)
                else Notifier.leaveRoute(context, route, plan)
            }
            DepartureWidget.refreshAll(context)
        } catch (_: Exception) {
        } finally { pending.finish() }
    }.start()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlertScheduler.ensurePeriodicCheck(context)
    }
}

object Notifier {
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun leaveRoute(context: Context, route: Route, plan: RoutePlan) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = plan.summary()
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Il est temps de partir ! (${route.name})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(route.id.hashCode(), n)
    }

    fun leaveNow(context: Context, trip: Trip, departure: Instant) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Il est temps de partir ! (${trip.name})")
            .setContentText("${trip.stop.line} → ${trip.stop.direction} à ${trip.stop.station} : départ à ${formatTime(departure)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(trip.id.hashCode(), n)
    }
}
