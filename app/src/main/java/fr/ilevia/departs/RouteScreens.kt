package fr.ilevia.departs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.time.Instant
import java.util.UUID

private val DAYS_ABBR = listOf("L", "M", "M", "J", "V", "S", "D")

internal fun fmtMinute(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** Résumé court d'une plage : « L M M J V · 07:00–08:00 ». */
internal fun AlertWindow.label(): String {
    val names = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")
    val d = if (days.size == 7) "tous les jours" else if (days == setOf(1, 2, 3, 4, 5)) "lun–ven" else days.sorted().joinToString(" ") { names[it - 1] }
    return "$d · ${fmtMinute(startMinute)}–${fmtMinute(endMinute)}"
}

// ───────────────────────── Plages d'alerte ─────────────────────────

/** Édition d'une liste de plages d'alerte (jours + heures au pas de 5 min). */
@Composable
fun WindowsEditor(windows: List<AlertWindow>, onChange: (List<AlertWindow>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        windows.forEachIndexed { i, w ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Plage ${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (windows.size > 1) {
                        IconButton(onClick = { onChange(windows.filterIndexed { j, _ -> j != i }) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer la plage", tint = scheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DAYS_ABBR.forEachIndexed { d, label ->
                        DayToggle(label, (d + 1) in w.days) {
                            val nd = if ((d + 1) in w.days) w.days - (d + 1) else w.days + (d + 1)
                            onChange(windows.mapIndexed { j, x -> if (j == i) x.copy(days = nd) else x })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("De ${fmtMinute(w.startMinute)} à ${fmtMinute(w.endMinute)}", style = MaterialTheme.typography.bodyLarge, color = scheme.primary)
                Slider(
                    value = w.startMinute.toFloat(),
                    onValueChange = { v ->
                        val s = v.toInt().coerceAtMost(w.endMinute)
                        onChange(windows.mapIndexed { j, x -> if (j == i) x.copy(startMinute = s) else x })
                    },
                    valueRange = 0f..1435f, steps = 286,
                )
                Slider(
                    value = w.endMinute.toFloat(),
                    onValueChange = { v ->
                        val e = v.toInt().coerceAtLeast(w.startMinute)
                        onChange(windows.mapIndexed { j, x -> if (j == i) x.copy(endMinute = e) else x })
                    },
                    valueRange = 0f..1435f, steps = 286,
                )
            }
        }
        OutlinedButton(
            onClick = {
                val last = windows.lastOrNull()
                onChange(windows + (last?.copy() ?: AlertWindow(setOf(1, 2, 3, 4, 5), 17 * 60, 18 * 60)))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Ajouter une plage")
        }
    }
}

/** Fenêtre de gestion des plages d'alerte d'un trajet ou d'un itinéraire. */
@Composable
fun AlertsDialog(title: String, initial: List<AlertWindow>, onDismiss: () -> Unit, onSave: (List<AlertWindow>) -> Unit) {
    var windows by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Vous êtes prévenu uniquement pendant ces plages horaires.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                WindowsEditor(windows) { windows = it }
            }
        },
        confirmButton = {
            TextButton(enabled = windows.isNotEmpty() && windows.all { it.days.isNotEmpty() }, onClick = { onSave(windows) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ───────────────────────── Infos trafic ─────────────────────────

@Composable
fun DisruptionsCard(items: List<Disruption>) {
    val scheme = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = scheme.error)
            Spacer(Modifier.width(8.dp))
            Text("Infos trafic sur vos lignes", style = MaterialTheme.typography.titleMedium)
        }
        items.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            else Spacer(Modifier.height(8.dp))
            var expanded by remember(d.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.Top) {
                LineBadge(d.line, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.type,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (d.type.equals("Perturbation", true)) scheme.error else scheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        d.message, style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ───────────────────────── Choix d'un arrêt (ligne → sens → arrêt) ─────────────────────────

@Composable
fun StopPicker(all: List<Passage>, title: String, onPicked: (StopSelection) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var step by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    BackHandler { if (step > 0) { step -= 1; query = "" } else onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("$title · étape ${step + 1} sur 3", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Text(listOf("Choisissez la ligne", "Choisissez le sens", "Choisissez l'arrêt de montée")[step], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        when (step) {
            0 -> {
                val lines = all.map { it.line }.distinct().sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(lines) { l -> Box(Modifier.clickable { line = l; step = 1 }, contentAlignment = Alignment.Center) { LineBadge(l, 64.dp) } }
                }
            }
            1 -> {
                val dirs = all.filter { it.line == line }.map { it.direction }.distinct().sorted()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(dirs) { d ->
                        AppCard(Modifier.fillMaxWidth(), onClick = { direction = d; step = 2; query = "" }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LineBadge(line ?: "", 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("→ ${pretty(d)}", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            else -> {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Rechercher un arrêt") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val stops = all.filter { it.line == line && it.direction == direction }.map { it.station }.distinct().sorted()
                    .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    items(stops) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPicked(StopSelection(s, line!!, direction!!)) }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(pretty(s), style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(color = scheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Création d'un itinéraire ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRouteScreen(onCancel: () -> Unit, onSave: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var data by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var loadKey by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var legs by remember { mutableStateOf<List<Leg>>(emptyList()) }
    var depart by remember { mutableIntStateOf(7 * 60 + 45) }
    var walk by remember { mutableIntStateOf(5) }
    var transfer by remember { mutableIntStateOf(3) }
    var buffer by remember { mutableIntStateOf(2) }
    var windows by remember { mutableStateOf(listOf(AlertWindow(setOf(1, 2, 3, 4, 5), 7 * 60, 7 * 60 + 45))) }

    LaunchedEffect(loadKey) {
        error = null
        try { data = withContext(Dispatchers.IO) { IleviaApi.fetchAll(force = true) } }
        catch (e: Exception) { error = e.message ?: "Erreur réseau" }
    }
    BackHandler(enabled = !picking) { onCancel() }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nouvel itinéraire") },
                navigationIcon = {
                    IconButton(onClick = { if (picking) picking = false else onCancel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val all = data
            if (all == null) {
                Column(Modifier.padding(16.dp)) {
                    Text(error?.let { "Impossible de charger les lignes Ilévia ($it)." } ?: "Chargement des lignes…", color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    if (error != null) Button(onClick = { loadKey++ }) { Text("Réessayer") } else CircularProgressIndicator()
                }
            } else if (picking) {
                StopPicker(all, "Tronçon ${legs.size + 1}", onPicked = { sel ->
                    legs = legs + Leg(sel, 15)
                    if (name.isBlank() && legs.size == 1) name = "Départ ${pretty(sel.station)}"
                    picking = false
                }, onBack = { picking = false })
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Enchaînez plusieurs lignes (ex. 84 → M2 → 32). L'itinéraire est recalculé en temps réel : si un véhicule est en retard et que la correspondance est ratée, le passage suivant est choisi.",
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    )
                    AppCard(Modifier.fillMaxWidth()) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom de l'itinéraire") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Tronçons", style = MaterialTheme.typography.titleMedium)
                    legs.forEachIndexed { i, leg ->
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LineBadge(leg.stop.line, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${pretty(leg.stop.station)}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("→ ${pretty(leg.stop.direction)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { legs = legs.filterIndexed { j, _ -> j != i } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer le tronçon", tint = scheme.onSurfaceVariant)
                                }
                            }
                            Text("Durée dans le véhicule : ${leg.rideMinutes} min", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                leg.rideMinutes.toFloat(),
                                { v -> legs = legs.mapIndexed { j, l -> if (j == i) l.copy(rideMinutes = v.toInt()) else l } },
                                valueRange = 1f..90f, steps = 88,
                            )
                        }
                    }
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (legs.isEmpty()) "Ajouter le premier tronçon" else "Ajouter un tronçon")
                    }

                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Heure à l'arrêt de départ", style = MaterialTheme.typography.titleMedium)
                        Text(fmtMinute(depart), style = MaterialTheme.typography.headlineMedium, color = scheme.primary)
                        Slider(depart.toFloat(), { depart = it.toInt() }, valueRange = 0f..1435f, steps = 286)
                        Text("Temps pour rejoindre l'arrêt : $walk min", style = MaterialTheme.typography.bodyLarge)
                        Slider(walk.toFloat(), { walk = it.toInt() }, valueRange = 0f..45f, steps = 44)
                        Text("Marge de correspondance : $transfer min", style = MaterialTheme.typography.bodyLarge)
                        Slider(transfer.toFloat(), { transfer = it.toInt() }, valueRange = 0f..15f, steps = 14)
                        Text("Marge de sécurité au départ : $buffer min", style = MaterialTheme.typography.bodyLarge)
                        Slider(buffer.toFloat(), { buffer = it.toInt() }, valueRange = 0f..15f, steps = 14)
                    }

                    Text("Alertes", style = MaterialTheme.typography.titleMedium)
                    WindowsEditor(windows) { windows = it }

                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = name.isNotBlank() && legs.isNotEmpty() && windows.isNotEmpty() && windows.all { it.days.isNotEmpty() },
                        onClick = {
                            onSave(
                                Route(
                                    id = UUID.randomUUID().toString(), name = name.trim(), legs = legs,
                                    walkMinutes = walk, transferMinutes = transfer, bufferMinutes = buffer,
                                    departMinute = depart, windows = windows,
                                ),
                            )
                        },
                    ) { Text("Enregistrer l'itinéraire") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ───────────────────────── Carte d'un itinéraire ─────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RouteCard(
    route: Route,
    refreshKey: Int,
    disruptionCount: Int,
    onToggleAlert: (Boolean) -> Unit,
    onEditAlerts: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var plan by remember { mutableStateOf<RoutePlan?>(null) }
    var error by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(route, refreshKey) {
        while (true) {
            try {
                plan = withContext(Dispatchers.IO) {
                    IleviaApi.fetchAll(force = refreshKey > 0)
                    planRoute(route, Instant.now()) { IleviaApi.passagesFor(it) }
                }
                error = false
            } catch (e: Exception) { error = true }
            now = Instant.now()
            delay(30_000)
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Itinéraire · à l'arrêt à ${fmtMinute(route.departMinute)}",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            val p = plan
            if (p?.arrival != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Arrivée", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(formatTime(p.arrival!!), style = MaterialTheme.typography.headlineSmall, color = scheme.primary, fontWeight = FontWeight.Bold)
                }
            } else if (plan == null && !error) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(10.dp))

        val p = plan
        if (p == null) {
            if (error) Text("Hors ligne", color = scheme.onSurfaceVariant)
        } else {
            p.legs.forEachIndexed { i, lp ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(lp.leg.stop.line, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pretty(lp.leg.stop.station), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ ${pretty(lp.leg.stop.direction)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (lp.departure != null && lp.arrival != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatTime(lp.departure), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("→ ${formatTime(lp.arrival)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Pas de données", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                }
            }
            val leave = route.leaveTime(p)
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (leave != null) {
                    val lm = java.time.Duration.between(now, leave).toMinutes()
                    if (lm > 0) InfoPill("Partir à ${formatTime(leave)}", scheme.primaryContainer, scheme.onPrimaryContainer)
                    else InfoPill("Partez maintenant !", scheme.errorContainer, scheme.onErrorContainer)
                }
                if (!p.complete) InfoPill("Correspondance introuvable", scheme.errorContainer, scheme.onErrorContainer)
                if (disruptionCount > 0) InfoPill("⚠ $disruptionCount info${if (disruptionCount > 1) "s" else ""} trafic", scheme.errorContainer, scheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = scheme.outlineVariant)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = route.enabled, onCheckedChange = onToggleAlert)
            Text(
                "  Alerte · ${route.windows.size} plage${if (route.windows.size > 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clickable(onClick = onEditAlerts),
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = scheme.onSurfaceVariant)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cet itinéraire ?") },
            text = { Text("« ${route.name} » et ses alertes seront supprimés.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
