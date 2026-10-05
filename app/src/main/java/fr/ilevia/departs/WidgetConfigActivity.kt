package fr.ilevia.departs

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Écran affiché quand on pose (ou reconfigure) un widget : choix des trajets à afficher.
 * L'ordre des tuiles / lignes suit l'ordre dans lequel on coche les trajets.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val store = TripStore(this)
        setContent {
            MaterialTheme {
                ConfigScreen(store, widgetId) { ids ->
                    store.setTripsForWidget(widgetId, ids)
                    WidgetUpdater.refreshAll(this)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(store: TripStore, widgetId: Int, onDone: (List<String>) -> Unit) {
    val trips = remember { store.all() }
    var selected by remember { mutableStateOf(store.tripIdsForWidget(widgetId)) }

    Scaffold(topBar = { TopAppBar(title = { Text("Trajets de ce widget") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trips.isEmpty()) {
                Text("Aucun trajet enregistré. Ouvrez l'app Ilévia Départs pour en créer, puis reconfigurez le widget.")
            } else {
                Text("Cochez les trajets à afficher, dans l'ordre voulu.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(trips, key = { it.id }) { trip ->
                    val index = selected.indexOf(trip.id)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { selected = if (index >= 0) selected - trip.id else selected + trip.id }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = index >= 0, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(trip.name, style = MaterialTheme.typography.titleSmall)
                            Text(trip.stop.label(), style = MaterialTheme.typography.bodySmall)
                        }
                        if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { onDone(selected) }) { Text("Valider") }
        }
    }
}
