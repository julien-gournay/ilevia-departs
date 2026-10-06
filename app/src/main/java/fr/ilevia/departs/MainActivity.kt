package fr.ilevia.departs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LineColors.init(this)
        StopIndex.init(this)
        Notifier.ensureChannel(this)
        AlertScheduler.ensurePeriodicCheck(this)
        setContent { IleviaTheme { AppRoot() } }
    }
}

// ───────────────────────── Racine : onglets + écrans plein écran ─────────────────────────

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { TripStore(context) }
    val updater = remember { UpdateController(context) }
    var trips by remember { mutableStateOf(store.all()) }
    val favStore = remember { FavoriteStore(context) }
    var favs by remember { mutableStateOf(favStore.all()) }
    val orderStore = remember { OrderStore(context) }
    var order by remember { mutableStateOf(orderStore.get()) }
    var disruptions by remember { mutableStateOf<List<Disruption>>(emptyList()) }
    var editTrip by remember { mutableStateOf<Trip?>(null) }
    var editFav by remember { mutableStateOf<Favorite?>(null) }
    var newChoice by remember { mutableStateOf(false) }
    var widgetIds by remember { mutableStateOf(store.widgetTripIds()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf("") }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        updater.check()
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { disruptions = withContext(Dispatchers.IO) { Disruptions.fetch() } } catch (_: Exception) {}
            delay(300_000)
        }
    }

    fun reload() {
        trips = store.all()
        favs = favStore.all()
        widgetIds = store.widgetTripIds()
        WidgetUpdater.refreshAll(context)
    }

    if (overlay == "new") {
        BackHandler { overlay = "" }
        NewTripScreen(
            onCancel = { overlay = "" },
            onSave = { trip ->
                store.save(trip)
                reload()
                AlertScheduler.ensurePeriodicCheck(context)
                overlay = ""
            },
        )
    } else if (overlay == "newfav") {
        BackHandler { overlay = "" }
        NewFavoriteScreen(
            trips = trips,
            onCancel = { overlay = "" },
            onSave = { f ->
                favStore.save(f)
                reload()
                AlertScheduler.ensurePeriodicCheck(context)
                Thread { try { FavoriteTracker.tick(context) } catch (_: Exception) {} }.start()
                overlay = ""
            },
        )
    } else if (overlay.startsWith("trip:")) {
        BackHandler { overlay = "" }
        val trip = trips.firstOrNull { it.id == overlay.removePrefix("trip:") }
        if (trip == null) overlay = "" else TripDetailScreen(
            trip, onClose = { overlay = "" },
            onWidget = trip.id in widgetIds,
            onToggleWidget = { store.toggleOnWidget(trip.id); reload() },
            onToggleAlert = { on -> store.save(trip.copy(enabled = on)); reload() },
            onEditAlerts = { editTrip = trip },
            onDelete = { store.delete(trip.id); reload(); overlay = "" },
        )
    } else if (overlay == "diag") {
        BackHandler { overlay = "" }
        DiagnosticScreen(defaultQuery = trips.firstOrNull()?.stop?.station ?: "", onClose = { overlay = "" })
    } else {
        val updateAvailable = updater.state is UpdateState.Available
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Trajets") },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Menu, contentDescription = null) }, label = { Text("Widgets") },
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 },
                        icon = {
                            if (updateAvailable) BadgedBox(badge = { Badge() }) { Icon(Icons.Filled.Settings, contentDescription = null) }
                            else Icon(Icons.Filled.Settings, contentDescription = null)
                        },
                        label = { Text("Réglages") },
                    )
                }
            },
            floatingActionButton = {
                if (tab == 0 && (trips.isNotEmpty() || favs.isNotEmpty())) {
                    ExtendedFloatingActionButton(
                        onClick = { newChoice = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Nouveau") },
                    )
                }
            },
        ) { pad ->
            when (tab) {
                0 -> TripsScreen(
                    trips = trips, favs = favs, disruptions = disruptions,
                    widgetIds = widgetIds, updater = updater, modifier = Modifier.padding(pad),
                    onNew = { newChoice = true },
                    order = order,
                    onMove = { key, delta ->
                        val cur = mergeOrder(order, trips, favs).toMutableList()
                        val i = cur.indexOf(key)
                        val j = i + delta
                        if (i >= 0 && j in cur.indices) {
                            cur[i] = cur[j].also { cur[j] = cur[i] }
                            order = cur; orderStore.set(cur)
                        }
                    },
                    onEditTripAlerts = { editTrip = it },
                    onToggleFav = { f, on -> favStore.save(f.copy(enabled = on)); reload() },
                    onEditFavAlerts = { editFav = it },
                    onDeleteFav = { favStore.delete(it); reload() },
                    onOpen = { overlay = "trip:$it" },
                    onToggleWidget = { store.toggleOnWidget(it); reload() },
                    onToggleAlert = { trip, on -> store.save(trip.copy(enabled = on)); reload() },
                    onDelete = { store.delete(it); reload() },
                )
                1 -> WidgetsScreen(store, Modifier.padding(pad))
                else -> SettingsScreen(updater, Modifier.padding(pad), onDiagnostic = { overlay = "diag" })
            }
        }
    }

    if (newChoice) {
        AlertDialog(
            onDismissRequest = { newChoice = false },
            title = { Text("Que voulez-vous ajouter ?") },
            text = { Text("Un trajet affiche le prochain bus d'une ligne et vous dit quand partir. Un horaire favori suit un passage précis (ex. le 84 de 08:12) : retard, et notification à T-10, T-5 min…") },
            confirmButton = { TextButton(onClick = { newChoice = false; overlay = "newfav" }) { Text("Horaire favori") } },
            dismissButton = { TextButton(onClick = { newChoice = false; overlay = "new" }) { Text("Trajet simple") } },
        )
    }
    editTrip?.let { t ->
        AlertsDialog(
            title = "Alertes · ${t.name}", initial = t.windows(),
            onDismiss = { editTrip = null },
            onSave = { w ->
                val f = w.first()
                store.save(t.copy(days = f.days, startMinute = f.startMinute, endMinute = f.endMinute, extraWindows = w.drop(1)))
                editTrip = null; reload()
            },
        )
    }
    editFav?.let { f ->
        FavoriteAlertsDialog(
            f, onDismiss = { editFav = null },
            onSave = { nf -> favStore.save(nf); editFav = null; reload() },
        )
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 110.dp)

// ───────────────────────── Onglet « Trajets » ─────────────────────────

@Composable
fun TripsScreen(
    trips: List<Trip>,
    favs: List<Favorite>,
    disruptions: List<Disruption>,
    widgetIds: List<String>,
    updater: UpdateController,
    modifier: Modifier,
    onNew: () -> Unit,
    order: List<String>,
    onMove: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onEditTripAlerts: (Trip) -> Unit,
    onToggleFav: (Favorite, Boolean) -> Unit,
    onEditFavAlerts: (Favorite) -> Unit,
    onDeleteFav: (String) -> Unit,
    onToggleWidget: (String) -> Unit,
    onToggleAlert: (Trip, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var reorder by remember { mutableStateOf(false) }
    val entries = mergeOrder(order, trips, favs)
    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenHeader("Mes trajets", "Prochains départs en temps réel") }
                if (entries.size > 1) TextButton(onClick = { reorder = !reorder }) { Text(if (reorder) "Terminé" else "Ordre") }
                if (refreshing) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    refreshing = true
                    refreshKey++
                    WidgetUpdater.refreshAll(context) { refreshing = false }
                }) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser") }
            }
        }
        val st = updater.state
        if (st is UpdateState.Available) item { UpdateBanner(updater, st.release) }
        val myLines = (trips.map { it.stop.line } + favs.map { it.stop.line }).toSet()
        val relevant = Disruptions.forLines(disruptions, myLines)
        if (relevant.isNotEmpty()) item { DisruptionsCard(relevant) }
        if (trips.isEmpty() && favs.isEmpty()) {
            item { EmptyState(onNew) }
        } else {
            items(entries, key = { it }) { key ->
                val fav = if (key.startsWith("f:")) favs.firstOrNull { "f:" + it.id == key } else null
                val trip = if (key.startsWith("t:")) trips.firstOrNull { "t:" + it.id == key } else null
                val idx = entries.indexOf(key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (fav != null) {
                            FavoriteCard(
                                f = fav, refreshKey = refreshKey,
                                disruptionCount = Disruptions.forLines(disruptions, setOf(fav.stop.line)).size,
                                onToggle = { onToggleFav(fav, it) },
                                onEditAlerts = { onEditFavAlerts(fav) },
                                onDelete = { onDeleteFav(fav.id) },
                            )
                        } else if (trip != null) {
                            TripCard(
                                trip = trip,
                                disruptionCount = Disruptions.forLines(disruptions, setOf(trip.stop.line)).size,
                                refreshKey = refreshKey,
                                onOpen = { onOpen(trip.id) },
                                onDelete = { onDelete(trip.id) },
                            )
                        }
                    }
                    if (reorder) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(enabled = idx > 0, onClick = { onMove(key, -1) }) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter")
                            }
                            IconButton(enabled = idx < entries.size - 1, onClick = { onMove(key, 1) }) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(updater: UpdateController, release: Release) {
    val scope = rememberCoroutineScope()
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nouvelle version disponible", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Version ${release.build}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Button(onClick = { scope.launch { updater.install(release) } }) { Text("Mettre à jour") }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                LineBadge("84", 56.dp); LineBadge("86", 56.dp); LineBadge("82", 56.dp)
            }
            Text("Aucun trajet pour l'instant", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choisissez une ligne, un sens et un arrêt : l'app vous prévient quand il faut partir, et vos widgets affichent le prochain départ.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Button(onClick = onNew) { Text("Commencer") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripCard(trip: Trip, disruptionCount: Int, refreshKey: Int, onOpen: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var passages by remember { mutableStateOf<List<Passage>?>(null) }
    var error by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    // Rafraîchit les données toutes les 30 s tant que la carte est affichée.
    LaunchedEffect(trip.stop, refreshKey) {
        while (true) {
            try {
                passages = withContext(Dispatchers.IO) { IleviaApi.passagesFor(trip.stop, force = refreshKey > 0) }
                error = false
            } catch (e: Exception) { error = true }
            now = Instant.now()
            delay(30_000)
        }
    }
    val next = passages.orEmpty().firstOrNull { it.time.isAfter(now.minusSeconds(30)) }

    AppCard(Modifier.fillMaxWidth(), onClick = { if (showDelete) showDelete = false else onOpen() }, onLongClick = { showDelete = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(trip.stop.line, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("→ ${pretty(trip.stop.direction)}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stopLabel(trip.stop.station, trip.stop.line), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                when {
                    error && passages == null -> Text("Hors ligne", style = MaterialTheme.typography.titleMedium)
                    passages == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    next == null -> Text("—", style = MaterialTheme.typography.headlineMedium)
                    else -> {
                        val m = Duration.between(now, next.time).toMinutes().coerceAtLeast(0)
                        if (m <= 0) {
                            Text("Proche", style = MaterialTheme.typography.titleLarge, color = scheme.primary)
                        } else {
                            Text("$m", style = MaterialTheme.typography.displaySmall)
                            Text("min", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (passages != null && next == null) {
            Spacer(Modifier.height(10.dp))
            Text(noDataMessage(trip.stop.line), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }

        if (next != null) {
            Spacer(Modifier.height(12.dp))
            val leave = trip.leaveTime(next.time)
            val lm = Duration.between(now, leave).toMinutes()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPill("Départ ${formatTime(next.time)}", scheme.surfaceContainerHigh, scheme.onSurface)
                if (lm > 0) InfoPill("Partir à ${formatTime(leave)}", scheme.primaryContainer, scheme.onPrimaryContainer)
                else InfoPill("Partez maintenant !", scheme.errorContainer, scheme.onErrorContainer)
                InfoPill("Marche ${trip.walkMinutes} min + ${trip.bufferMinutes}", scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
                if (disruptionCount > 0) InfoPill("⚠ $disruptionCount info${if (disruptionCount > 1) "s" else ""} trafic", scheme.errorContainer, scheme.onErrorContainer)
            }
        }

        if (showDelete) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Supprimer")
                }
                TextButton(onClick = { showDelete = false }) { Text("Annuler") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce trajet ?") },
            text = { Text("« ${trip.name} » et son alerte seront supprimés.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; showDelete = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false; showDelete = false }) { Text("Annuler") } },
        )
    }
}

// ───────────────────────── Onglet « Widgets » ─────────────────────────

@Composable
fun WidgetsScreen(store: TripStore, modifier: Modifier) {
    val context = LocalContext.current
    var showStation by remember { mutableStateOf(store.showStation) }
    var showDep by remember { mutableStateOf(store.showDeparture) }
    var showLeave by remember { mutableStateOf(store.showLeave) }
    fun refresh() { WidgetUpdater.refreshAll(context) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Widgets", "Personnalisez ce qu'affichent vos widgets") }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Informations affichées", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Arrêt de départ", "Nom de l'arrêt sous le numéro de ligne", showStation) { showStation = it; store.showStation = it; refresh() }
                SwitchRow("Heure de départ du bus", "Ex. « Dép. 08:12 »", showDep) { showDep = it; store.showDeparture = it; refresh() }
                SwitchRow("Heure où il faut partir", "Ex. « Partir 08:06 »", showLeave) { showLeave = it; store.showLeave = it; refresh() }
            }
        }
        val kinds = listOf(
            Triple("Bandeau", "Format 1×4 ou 1×5 : jusqu'à 5 trajets côte à côte.", 0),
            Triple("Grand chiffre", "Format 2×2 : un trajet, minutes en très grand.", 1),
            Triple("Liste de départs", "Format 4×2 ou plus : une ligne par trajet, avec l'heure de mise à jour.", 2),
            Triple("Grille", "Format 4×3 : pastilles compactes, 1 ou 2 colonnes selon la largeur.", 3),
        )
        items(kinds) { (title, desc, kind) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WidgetPreview(kind, showDep, showLeave, showStation)
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Ajouter un widget", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Appui long sur l'écran d'accueil → Widgets → « Ilévia Départs ». À la pose, choisissez les trajets à afficher. " +
                        "Pour les changer plus tard : appui long sur le widget → Reconfigurer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ───────────────────────── Onglet « Réglages » ─────────────────────────

@Composable
fun SettingsScreen(updater: UpdateController, modifier: Modifier, onDiagnostic: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Réglages", "Mises à jour, alertes et diagnostic") }

        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Mise à jour de l'app", style = MaterialTheme.typography.titleMedium)
                Text("Version installée : ${updater.currentName}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when (val st = updater.state) {
                    UpdateState.Idle -> Text("Aucune vérification effectuée.")
                    UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Recherche en cours…")
                    }
                    UpdateState.UpToDate -> Text("✓ Vous avez la dernière version.")
                    is UpdateState.Available -> {
                        Text("Version ${st.release.build} disponible.", fontWeight = FontWeight.SemiBold)
                        if (st.release.notes.isNotBlank()) Text(st.release.notes.trim().take(300), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scope.launch { updater.install(st.release) } }) { Text("Télécharger et installer") }
                    }
                    is UpdateState.Downloading -> {
                        Text("Téléchargement… ${st.percent} %")
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    UpdateState.NeedPermission -> Text("Autorisez l'installation depuis Ilévia Départs dans l'écran qui s'est ouvert, puis revenez ici et relancez la mise à jour.")
                    UpdateState.PrivateRepo -> Text("Les versions ne sont pas accessibles : le dépôt GitHub est privé. Rendez-le public pour activer la mise à jour dans l'app, ou ouvrez la page des versions et téléchargez l'APK.")
                    is UpdateState.Error -> Text("Impossible de vérifier : ${st.message}", color = scheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { updater.check() } }) { Text("Vérifier") }
                    TextButton(onClick = { openUrl(Updater.RELEASES_PAGE) }) { Text("Page des versions") }
                }
            }
        }

        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Alertes de départ", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pour que la notification « Il est temps de partir » arrive à la minute près, autorisez les alarmes et rappels.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Autoriser les alarmes précises") }
            }
        }

        item {
            AppCard(Modifier.fillMaxWidth(), onClick = onDiagnostic) {
                Text("Diagnostic de l'API", style = MaterialTheme.typography.titleMedium)
                Text("Voir les données brutes reçues pour un arrêt.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }

        item {
            Text(
                "Données : ilévia / Métropole Européenne de Lille (open data).",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ───────────────────────── Création d'un trajet ─────────────────────────

private val DAY_LABELS = listOf("L", "M", "M", "J", "V", "S", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTripScreen(onCancel: () -> Unit, onSave: (Trip) -> Unit) {
    val scheme = MaterialTheme.colorScheme
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
    var windows by remember { mutableStateOf(listOf(AlertWindow(setOf(1, 2, 3, 4, 5), 7 * 60, 9 * 60))) }

    fun load() {
        error = null
        scope.launch {
            try { data = withContext(Dispatchers.IO) { IleviaApi.fetchAll(force = true) } }
            catch (e: Exception) { if (StopIndex.lines.isNotEmpty()) data = emptyList() else error = e.message ?: "Erreur réseau" }
        }
    }
    LaunchedEffect(Unit) { load() }
    fun goTo(s: Int) { step = s; query = "" }
    BackHandler(enabled = step > 0) { goTo(step - 1) }

    val titles = listOf("Choisissez la ligne", "Choisissez le sens", "Choisissez l'arrêt", "Réglez votre trajet")

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nouveau trajet") },
                navigationIcon = {
                    IconButton(onClick = { if (step > 0) goTo(step - 1) else onCancel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("Étape ${step + 1} sur 4", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            Text(titles[step], style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))

            val all = data
            if (all == null) {
                Text(error?.let { "Impossible de charger les lignes Ilévia ($it)." } ?: "Chargement des lignes…", color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (error != null) Button(onClick = { load() }) { Text("Réessayer") } else CircularProgressIndicator()
            } else when (step) {
                0 -> {
                    val lines = lineChoices(all)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(76.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(lines) { l ->
                            Box(Modifier.clickable { line = l; goTo(1) }, contentAlignment = Alignment.Center) { LineBadge(l, 64.dp) }
                        }
                    }
                }
                1 -> {
                    val dirs = directionChoices(all, line ?: "")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(dirs) { d ->
                            AppCard(Modifier.fillMaxWidth(), onClick = { direction = d; goTo(2) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LineBadge(line ?: "", 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("→ ${pretty(d)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                2 -> {
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
                                Modifier.fillMaxWidth().clickable { station = s; if (name.isBlank()) name = "Départ ${pretty(s)}"; goTo(3) }.padding(vertical = 14.dp),
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
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(line ?: "", 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("→ ${pretty(direction ?: "")}", style = MaterialTheme.typography.titleMedium)
                                Text(stopLabel(station ?: "", line), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom du trajet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Temps pour rejoindre l'arrêt", style = MaterialTheme.typography.titleMedium)
                        Text("$walk min", style = MaterialTheme.typography.headlineMedium, color = scheme.primary)
                        Slider(walk.toFloat(), { walk = it.toInt() }, valueRange = 0f..45f, steps = 44)
                        Text("Marge de sécurité", style = MaterialTheme.typography.titleMedium)
                        Text("$buffer min", style = MaterialTheme.typography.headlineMedium, color = scheme.primary)
                        Slider(buffer.toFloat(), { buffer = it.toInt() }, valueRange = 0f..15f, steps = 14)
                    }
                    Text("Quand être prévenu ?", style = MaterialTheme.typography.titleMedium)
                    WindowsEditor(windows) { windows = it }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = name.isNotBlank() && windows.isNotEmpty() && windows.all { it.days.isNotEmpty() },
                        onClick = {
                            onSave(
                                Trip(
                                    id = UUID.randomUUID().toString(), name = name.trim(),
                                    stop = StopSelection(station!!, line!!, direction!!),
                                    walkMinutes = walk, bufferMinutes = buffer,
                                    days = windows.first().days,
                                    startMinute = windows.first().startMinute, endMinute = windows.first().endMinute,
                                    extraWindows = windows.drop(1),
                                ),
                            )
                        },
                    ) { Text("Enregistrer le trajet") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ───────────────────────── Diagnostic ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(defaultQuery: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(defaultQuery) }
    var out by remember { mutableStateOf("Appuyez sur Lancer.") }
    var busy by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic API") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(query, { query = it }, label = { Text("Arrêt à chercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
