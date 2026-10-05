package fr.ilevia.departs

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        AlertScheduler.ensurePeriodicCheck(this)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { TripStore(context) }
    var trips by remember { mutableStateOf(store.all()) }
    var creating by remember { mutableStateOf(false) }
    var widgetId by remember { mutableStateOf(store.widgetTripId()) }
    var diag by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (diag) {
        BackHandler { diag = false }
        DiagnosticScreen(defaultQuery = trips.firstOrNull()?.stop?.station ?: "", onClose = { diag = false })
        return
    }

    if (creating) {
        BackHandler { creating = false }
        NewTripScreen(
            onCancel = { creating = false },
            onSave = { trip ->
                store.save(trip)
                trips = store.all(); widgetId = store.widgetTripId()
                creating = false
                AlertScheduler.ensurePeriodicCheck(context)
                DepartureWidget.refreshAll(context)
            },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mes trajets Ilévia") }, actions = { TextButton(onClick = { diag = true }) { Text("Diagnostic") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Text("+") } },
    ) { pad ->
        if (trips.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("Aucun trajet enregistré.", style = MaterialTheme.typography.titleMedium)
                Text("Appuyez sur + pour choisir une ligne, un sens et un arrêt, puis posez le widget Ilévia sur votre écran d'accueil.")
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(trips, key = { it.id }) { trip ->
                    TripCard(
                        trip = trip,
                        isWidget = widgetId == trip.id,
                        onWidget = { store.setWidgetTrip(trip.id); widgetId = trip.id; DepartureWidget.refreshAll(context) },
                        onToggle = { store.save(trip.copy(enabled = it)); trips = store.all() },
                        onDelete = { store.delete(trip.id); trips = store.all(); widgetId = store.widgetTripId(); DepartureWidget.refreshAll(context) },
                    )
                }
            }
        }
    }
}

@Composable
fun TripCard(trip: Trip, isWidget: Boolean, onWidget: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var passages by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }

    // Rafraîchit les données toutes les 30 s tant que la carte est affichée.
    LaunchedEffect(trip.stop) {
        while (true) {
            try {
                passages = withContext(Dispatchers.IO) { IleviaApi.passagesFor(trip.stop) }
                error = false
            } catch (e: Exception) { error = true }
            now = Instant.now()
            delay(30_000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(trip.name, style = MaterialTheme.typography.titleMedium)
            Text(trip.stop.label(), style = MaterialTheme.typography.bodySmall)
            val next = passages.orEmpty().firstOrNull { it.time.isAfter(now.minusSeconds(30)) }
            when {
                error && passages == null -> Text("Impossible de charger les horaires")
                passages == null -> Text("Chargement…")
                next == null -> Text("Aucun passage prévu")
                else -> {
                    val mins = Duration.between(now, next.time).toMinutes().coerceAtLeast(0)
                    Text(if (mins <= 0) "Prochain départ : imminent" else "Prochain départ : $mins min (${formatTime(next.time)})",
                        style = MaterialTheme.typography.headlineSmall)
                    val leave = trip.leaveTime(next.time)
                    val lm = Duration.between(now, leave).toMinutes()
                    Text(if (lm > 0) "Partir à ${formatTime(leave)} (dans $lm min)" else "Partez maintenant !",
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Text("Trajet à pied ${trip.walkMinutes} min + marge ${trip.bufferMinutes} min", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alerte", Modifier.weight(1f))
                Switch(checked = trip.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isWidget) Text("✓ Affiché sur le widget", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                else OutlinedButton(onClick = onWidget) { Text("Mettre sur le widget") }
                TextButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}

private val DAY_LABELS = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTripScreen(onCancel: () -> Unit, onSave: (Trip) -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf<String?>(null) }
    var station by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var walk by remember { mutableIntStateOf(5) }
    var buffer by remember { mutableIntStateOf(2) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var startHour by remember { mutableIntStateOf(7) }
    var endHour by remember { mutableIntStateOf(9) }

    fun load() {
        error = null
        scope.launch {
            try { data = withContext(Dispatchers.IO) { IleviaApi.fetchAll(force = true) } }
            catch (e: Exception) { error = e.message ?: "Erreur réseau" }
        }
    }
    LaunchedEffect(Unit) { load() }

    BackHandler(enabled = step > 0) { step -= 1 }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(listOf("Choisir la ligne", "Choisir le sens", "Choisir l'arrêt", "Réglages du trajet")[step]) },
            navigationIcon = { TextButton(onClick = { if (step > 0) step -= 1 else onCancel() }) { Text(if (step > 0) "Retour" else "Annuler") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            val all = data
            if (all == null) {
                Text(error?.let { "Impossible de charger les lignes Ilévia ($it)." } ?: "Chargement des lignes…")
                if (error != null) Button(onClick = { load() }) { Text("Réessayer") }
                return@Column
            }
            when (step) {
                0 -> PickList(all.map { it.line }.distinct().sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))) {
                    line = it; step = 1
                }
                1 -> PickList(all.filter { it.line == line }.map { it.direction }.distinct().sorted()) {
                    direction = it; step = 2
                }
                2 -> {
                    OutlinedTextField(query, { query = it }, label = { Text("Rechercher un arrêt") }, modifier = Modifier.fillMaxWidth())
                    PickList(
                        all.filter { it.line == line && it.direction == direction }.map { it.station }.distinct().sorted(),
                        query,
                    ) { station = it; if (name.isBlank()) name = "Départ $it"; step = 3 }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$line → $direction · $station", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(name, { name = it }, label = { Text("Nom du trajet") }, modifier = Modifier.fillMaxWidth())
                    Text("Temps pour aller à l'arrêt : $walk min")
                    Slider(walk.toFloat(), { walk = it.toInt() }, valueRange = 0f..45f)
                    Text("Marge de sécurité : $buffer min")
                    Slider(buffer.toFloat(), { buffer = it.toInt() }, valueRange = 0f..15f)
                    Text("Jours d'alerte")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            FilterChip(selected = (i + 1) in days, onClick = { days = if ((i + 1) in days) days - (i + 1) else days + (i + 1) }, label = { Text(label) })
                        }
                    }
                    Text("Alerte entre ${startHour}h et ${endHour}h")
                    Slider(startHour.toFloat(), { startHour = it.toInt().coerceAtMost(endHour) }, valueRange = 0f..23f)
                    Slider(endHour.toFloat(), { endHour = it.toInt().coerceAtLeast(startHour) }, valueRange = 0f..23f)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank() && days.isNotEmpty(),
                        onClick = {
                            onSave(
                                Trip(
                                    id = UUID.randomUUID().toString(), name = name.trim(),
                                    stop = StopSelection(station!!, line!!, direction!!),
                                    walkMinutes = walk, bufferMinutes = buffer, days = days,
                                    startMinute = startHour * 60, endMinute = endHour * 60 + 59,
                                )
                            )
                        },
                    ) { Text("Enregistrer le trajet") }
                }
            }
        }
    }
}

@Composable
private fun PickList(items: List<String>, filter: String? = null, onPick: (String) -> Unit) {
    val shown = if (filter.isNullOrBlank()) items else items.filter { it.contains(filter, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown) { item ->
            Text(item, Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 14.dp))
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(defaultQuery: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(defaultQuery) }
    var out by remember { mutableStateOf("Appuyez sur Lancer.") }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Diagnostic API") }, navigationIcon = { TextButton(onClick = onClose) { Text("Retour") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(query, { query = it }, label = { Text("Arrêt à chercher") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                busy = true; out = "Chargement…"
                scope.launch {
                    out = withContext(Dispatchers.IO) { IleviaApi.diagnostic(query) }
                    busy = false
                }
            }) { Text("Lancer") }
            Text(out, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
