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

