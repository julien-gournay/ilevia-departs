package fr.ilevia.departs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val NOTIFY_CHOICES = listOf(1, 2, 3, 5, 10, 15, 20, 30)
private val DAYS_SHORT = listOf("L", "M", "M", "J", "V", "S", "D")

/** Choix des minutes « T-N » + alerte de retard. Partagé entre la création et la fenêtre de modification. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoriteAlertSettings(
    minutes: Set<Int>, onMinutes: (Set<Int>) -> Unit,
    delayOn: Boolean, onDelayOn: (Boolean) -> Unit,
    threshold: Int, onThreshold: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Text("Me prévenir avant le passage du bus", style = MaterialTheme.typography.titleSmall)
    Text("Choisissez une ou plusieurs minutes (T-N).", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NOTIFY_CHOICES.forEach { n ->
            val on = n in minutes
            FilterChip(
                selected = on,
                onClick = { onMinutes(if (on) minutes - n else minutes + n) },
                label = { Text("$n min") },
                leadingIcon = if (on) { { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Prévenir en cas de retard", style = MaterialTheme.typography.titleSmall)
            Text("Notification dès que le bus s'écarte de l'horaire.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Switch(checked = delayOn, onCheckedChange = onDelayOn)
    }
    if (delayOn) {
        Text("À partir de $threshold min d'écart", style = MaterialTheme.typography.bodyMedium)
        Slider(threshold.toFloat(), { onThreshold(it.toInt()) }, valueRange = 1f..15f, steps = 13)
    }
}

@Composable
fun FavoriteAlertsDialog(f: Favorite, onDismiss: () -> Unit, onSave: (Favorite) -> Unit) {
    var minutes by remember { mutableStateOf(f.notifyMinutes) }
    var delayOn by remember { mutableStateOf(f.notifyDelay) }
    var threshold by remember { mutableIntStateOf(f.delayThreshold) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alertes · ${f.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FavoriteAlertSettings(minutes, { minutes = it }, delayOn, { delayOn = it }, threshold, { threshold = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(f.copy(notifyMinutes = minutes, notifyDelay = delayOn, delayThreshold = threshold)) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ───────────────────────── Création d'un favori ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewFavoriteScreen(onCancel: () -> Unit, onSave: (Favorite) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var data by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadKey by remember { mutableIntStateOf(0) }
    var stop by remember { mutableStateOf<StopSelection?>(null) }
    var name by remember { mutableStateOf("") }
    var minute by remember { mutableIntStateOf(8 * 60) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var minutes by remember { mutableStateOf(setOf(10, 5)) }
    var delayOn by remember { mutableStateOf(true) }
    var threshold by remember { mutableIntStateOf(3) }

    LaunchedEffect(loadKey) {
        error = null
        try { data = withContext(Dispatchers.IO) { IleviaApi.fetchAll(force = true) } }
        catch (e: Exception) { error = e.message ?: "Erreur réseau" }
    }
    BackHandler(enabled = stop != null) { stop = null }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nouvel horaire favori") },
                navigationIcon = {
                    IconButton(onClick = { if (stop != null) stop = null else onCancel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val all = data
            val sel = stop
            if (all == null) {
                Column(Modifier.padding(16.dp)) {
                    Text(error?.let { "Impossible de charger les lignes Ilévia ($it)." } ?: "Chargement des lignes…", color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    if (error != null) Button(onClick = { loadKey++ }) { Text("Réessayer") } else CircularProgressIndicator()
                }
            } else if (sel == null) {
                StopPicker(all, "Favori", onPicked = {
                    stop = it
                    name = "${it.line} · ${pretty(it.station)}"
                    // Pré-remplit l'horaire avec le prochain passage annoncé.
                    all.filter { p -> it.matches(p) }.minByOrNull { p -> p.time }?.let { p ->
                        val z = java.time.ZonedDateTime.ofInstant(p.time, java.time.ZoneId.of("Europe/Paris"))
                        minute = z.hour * 60 + z.minute
                    }
                }, onBack = onCancel)
            } else {
                val upcoming = remember(all, sel) { all.filter { sel.matches(it) }.sortedBy { it.time }.take(6) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(sel.line, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("→ ${pretty(sel.direction)}", style = MaterialTheme.typography.titleMedium)
                                Text(pretty(sel.station), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Horaire de passage à suivre", style = MaterialTheme.typography.titleMedium)
                        Text(fmtMinute(minute), style = MaterialTheme.typography.displaySmall, color = scheme.primary)
                        if (upcoming.isNotEmpty()) {
                            Text("Prochains passages annoncés :", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                upcoming.forEach { p ->
                                    val z = java.time.ZonedDateTime.ofInstant(p.time, java.time.ZoneId.of("Europe/Paris"))
                                    val m = z.hour * 60 + z.minute
                                    FilterChip(selected = m == minute, onClick = { minute = m }, label = { Text(fmtMinute(m)) })
                                }
                            }
                        }
                        Text("Heure : ${minute / 60} h", style = MaterialTheme.typography.bodyMedium)
                        Slider((minute / 60).toFloat(), { minute = it.toInt() * 60 + minute % 60 }, valueRange = 0f..23f, steps = 22)
                        Text("Minute : ${minute % 60}", style = MaterialTheme.typography.bodyMedium)
                        Slider((minute % 60).toFloat(), { minute = (minute / 60) * 60 + it.toInt() }, valueRange = 0f..59f, steps = 58)
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Jours", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DAYS_SHORT.forEachIndexed { i, label ->
                                DayToggle(label, (i + 1) in days) { days = if ((i + 1) in days) days - (i + 1) else days + (i + 1) }
                            }
                        }
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        FavoriteAlertSettings(minutes, { minutes = it }, delayOn, { delayOn = it }, threshold, { threshold = it })
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = name.isNotBlank() && days.isNotEmpty(),
                        onClick = {
                            onSave(
                                Favorite(
                                    id = UUID.randomUUID().toString(), name = name.trim(), stop = sel, minute = minute, days = days,
                                    notifyMinutes = minutes, notifyDelay = delayOn, delayThreshold = threshold,
                                ),
                            )
                        },
                    ) { Text("Enregistrer le favori") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ───────────────────────── Carte d'un favori ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoriteCard(
    f: Favorite,
    refreshKey: Int,
    disruptionCount: Int,
    onToggle: (Boolean) -> Unit,
    onEditAlerts: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var passages by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(f.stop, refreshKey) {
        while (true) {
            try {
                passages = withContext(Dispatchers.IO) { IleviaApi.passagesFor(f.stop, force = refreshKey > 0) }
                error = false
            } catch (e: Exception) { error = true }
            now = Instant.now()
            delay(30_000)
        }
    }
    val todayOk = FavoriteTracker.dowOf(now) in f.days
    val match = passages?.let { FavoriteTracker.match(f, it, now) }

    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(f.stop.line, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("→ ${pretty(f.stop.direction)}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pretty(f.stop.station), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Horaire", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(fmtMinute(f.minute), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            error && passages == null -> Text("Hors ligne", color = scheme.onSurfaceVariant)
            passages == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            !todayOk -> Text("Pas prévu aujourd'hui.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            match == null -> Text(
                if (IleviaApi.feedSize > 0 && !IleviaApi.lineInFeed(f.stop.line)) noDataMessage(f.stop.line)
                else "Ce passage n'est pas (encore) annoncé par Ilévia.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            else -> {
                val d = FavoriteTracker.delayMinutes(f, match, now)
                val mins = Duration.between(now, match.time).toMinutes()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Annoncé à", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Text(formatTime(match.time), style = MaterialTheme.typography.displaySmall, color = scheme.primary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (mins <= 0) "Proche" else "dans $mins min",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        )
                        when {
                            d >= 1 -> InfoPill("Retard +$d min", scheme.errorContainer, scheme.onErrorContainer)
                            d <= -1 -> InfoPill("En avance ${-d} min", scheme.tertiaryContainer, scheme.onTertiaryContainer)
                            else -> InfoPill("À l'heure", scheme.primaryContainer, scheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f.notifyMinutes.isNotEmpty()) InfoPill("T-" + f.notifyMinutes.sorted().reversed().joinToString(", ") + " min", scheme.surfaceContainerHigh, scheme.onSurface)
            if (f.notifyDelay) InfoPill("Retard ≥ ${f.delayThreshold} min", scheme.surfaceContainerHigh, scheme.onSurface)
            if (disruptionCount > 0) InfoPill("⚠ $disruptionCount info${if (disruptionCount > 1) "s" else ""} trafic", scheme.errorContainer, scheme.onErrorContainer)
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = scheme.outlineVariant)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = f.enabled, onCheckedChange = onToggle)
            Text("  Alertes", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).clickable(onClick = onEditAlerts))
            TextButton(onClick = onEditAlerts) { Text("Régler") }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = scheme.onSurfaceVariant)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce favori ?") },
            text = { Text("« ${f.name} » et ses alertes seront supprimés.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
