package fr.ilevia.departs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

private class DetailData(val mine: List<Passage>, val others: List<Passage>)

/** Détail d'un trajet : tous les passages connus pour la ligne + les autres lignes de l'arrêt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    trip: Trip, onClose: () -> Unit,
    onWidget: Boolean, onToggleWidget: () -> Unit,
    onToggleAlert: (Boolean) -> Unit, onEditAlerts: () -> Unit, onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    var data by remember { mutableStateOf<DetailData?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(Instant.now()) }
    var key by remember { mutableIntStateOf(0) }

    LaunchedEffect(trip.stop, key) {
        while (true) {
            loading = true
            try {
                data = withContext(Dispatchers.IO) {
                    val all = IleviaApi.fetchAll(force = true)
                    val limit = Instant.now().minusSeconds(30)
                    val mineAll = StopMatch.rowsFor(trip.stop, all)
                    val mine = mineAll.filter { it.time.isAfter(limit) }.sortedBy { it.time }
                    val stationName = mineAll.firstOrNull()?.station ?: trip.stop.station
                    val others = all
                        .filter { norm(it.station) == norm(stationName) && it !in mineAll && it.time.isAfter(limit) }
                        .sortedBy { it.time }
                    DetailData(mine, others)
                }
                error = false
            } catch (e: Exception) { error = true }
            now = Instant.now()
            loading = false
            delay(30_000)
        }
    }

    BackHandler(onBack = onClose)
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce trajet ?") },
            text = { Text("« ${trip.name} » et son alerte seront supprimés.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text(trip.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { key++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(trip.stop.line, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column2(trip)
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = trip.enabled, onCheckedChange = onToggleAlert)
                        Text(
                            "  Alerte · ${trip.windows().size} plage${if (trip.windows().size > 1) "s" else ""}",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onEditAlerts) { Text("Régler") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Afficher sur les widgets", modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = onWidget, onClick = onToggleWidget, label = { Text("Widget") },
                            leadingIcon = if (onWidget) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                    HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.error)
                        Text("  Supprimer ce trajet", color = scheme.error)
                    }
                }
            }
            val d = data
            if (d == null) {
                item {
                    Text(
                        if (error) "Impossible de charger les départs. Vérifiez la connexion puis actualisez." else "Chargement…",
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                item { Text("Prochains départs", style = MaterialTheme.typography.titleMedium) }
                if (d.mine.isEmpty()) {
                    item { Text(noDataMessage(trip.stop.line), color = scheme.onSurfaceVariant) }
                } else {
                    item {
                        AppCard(Modifier.fillMaxWidth()) {
                            d.mine.take(10).forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                                DepartureRow(trip, p, now, highlight = i == 0)
                            }
                        }
                    }
                    if (d.mine.size == 1) {
                        item {
                            Text(
                                "Un seul passage est annoncé pour le moment. Les suivants apparaissent ici dès qu'Ilévia les publie (actualisation automatique toutes les 30 s).",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (d.others.isNotEmpty()) {
                    item { Text("Autres lignes à cet arrêt", style = MaterialTheme.typography.titleMedium) }
                    item {
                        AppCard(Modifier.fillMaxWidth()) {
                            d.others.take(12).forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LineBadge(p.line, 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("→ ${pretty(p.direction)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(minLabel(now, p.time), fontWeight = FontWeight.Bold)
                                        Text(formatTime(p.time), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Column2(trip: Trip) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.weight(1f)) {
        Text("→ ${pretty(trip.stop.direction)}", style = MaterialTheme.typography.titleMedium)
        Text(stopLabel(trip.stop.station, trip.stop.line), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Text(
            "Marche ${trip.walkMinutes} min + ${trip.bufferMinutes} min de marge",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DepartureRow(trip: Trip, p: Passage, now: Instant, highlight: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val leave = trip.leaveTime(p.time)
    val lm = Duration.between(now, leave).toMinutes()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(formatTime(p.time), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (lm > 0) "Partir à ${formatTime(leave)}" else if (Duration.between(now, p.time).toMinutes() <= 0) "Trop tard / imminent" else "Partez maintenant",
                style = MaterialTheme.typography.bodySmall,
                color = if (lm > 0) scheme.onSurfaceVariant else scheme.error,
            )
        }
        Text(
            minLabel(now, p.time),
            style = if (highlight) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (highlight) scheme.primary else scheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun minLabel(now: Instant, t: Instant): String {
    val m = Duration.between(now, t).toMinutes()
    return if (m <= 0) "Proche" else "$m min"
}

/** Explique pourquoi aucun passage n'est affiché. */
internal fun noDataMessage(line: String): String =
    if (IleviaApi.feedSize > 0 && !IleviaApi.lineInFeed(line))
        "Aucune donnée reçue pour la ligne $line : le flux temps réel public d'Ilévia ne la publie pas en ce moment (${IleviaApi.feedSize} passages reçus au total). L'app officielle utilise une autre source, non publique."
    else
        "Aucun passage annoncé pour cet arrêt pour le moment."
