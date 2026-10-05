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

/** Saisie d'une heure précise : heures ±1, minutes ±1 et ±5 (de 00:00 à 23:59). */
@Composable
fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val h = minute / 60
    val m = minute % 60
    fun set(nh: Int, nm: Int) = onChange((nh.coerceIn(0, 23) * 60 + nm.coerceIn(0, 59)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        TextButton(onClick = { set(h - 1, m) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−") }
        Text("%02d".format(h), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = { set(h + 1, m) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+") }
        Text(":", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { set(h, m - 5) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("−5") }
        TextButton(onClick = { set(h, m - 1) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("−1") }
        Text("%02d".format(m), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = { set(h, m + 1) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+1") }
        TextButton(onClick = { set(h, m + 5) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+5") }
    }
}

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
                Spacer(Modifier.height(4.dp))
                TimeStepper("De", w.startMinute) { s ->
                    onChange(windows.mapIndexed { j, x -> if (j == i) x.copy(startMinute = s, endMinute = maxOf(x.endMinute, s)) else x })
                }
                TimeStepper("À", w.endMinute) { e ->
                    onChange(windows.mapIndexed { j, x -> if (j == i) x.copy(endMinute = e, startMinute = minOf(x.startMinute, e)) else x })
                }
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
                val lines = lineChoices(all)
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
                val dirs = directionChoices(all, line ?: "")
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
                val stops = stopChoices(all, line ?: "", direction ?: "")
                        .filter { query.isBlank() || norm(stopLabel(it, line)).contains(norm(query)) }
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    items(stops) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPicked(StopSelection(s, line!!, direction!!)) }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(stopLabel(s, line), style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(color = scheme.outlineVariant)
                    }
                }
            }
        }
    }
}

