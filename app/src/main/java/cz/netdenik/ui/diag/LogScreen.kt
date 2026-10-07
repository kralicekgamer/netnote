package cz.netdenik.ui.diag

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.Event
import cz.netdenik.data.EventType

private const val LOG_LIMIT = 1000

private enum class LogFilter(val label: String, val types: Set<EventType>?) {
    ALL("Vše", null),
    STATE("Stav", setOf(EventType.STATE_CHANGE)),
    WIFI("Wi‑Fi", setOf(EventType.WIFI_NETWORK_CHANGE, EventType.AP_CHANGE)),
    MOBILE(
        "Mobilní",
        setOf(
            EventType.CELL_CHANGE, EventType.TECH_CHANGE, EventType.DATA_SIM_CHANGE,
            EventType.OPERATOR_CHANGE, EventType.ROAMING_CHANGE,
        ),
    ),
    IP("IP", setOf(EventType.IP_CONFIG_CHANGE, EventType.PUBLIC_IP_CHANGE)),
    SERVICE(
        "Služba",
        setOf(
            EventType.SERVICE_START, EventType.SERVICE_STOP, EventType.GAP, EventType.BOOT,
            EventType.SHUTDOWN, EventType.LOCATION_OFF, EventType.LOCATION_ON, EventType.ERROR,
        ),
    ),
}

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val events by remember { dao.recentEvents(LOG_LIMIT) }.collectAsStateWithLifecycle(emptyList())
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val shown = remember(events, filter) {
        filter.types?.let { types -> events.filter { it.type in types } } ?: events
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { f ->
                FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (events.isEmpty()) "Zatím žádné události. Sběr spustíš na záložce Teď."
                else "Žádná událost tohoto typu mezi posledními $LOG_LIMIT.",
                Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { EventRow(it) }
            }
        }
    }
}

@Composable
private fun EventRow(e: Event) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                Fmt.dateTime(e.ts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Fmt.event(e.type), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        val from = Fmt.stateOrRaw(e.fromValue)
        val to = Fmt.stateOrRaw(e.toValue)
        if (from != null || to != null) {
            Text("${from ?: "–"} → ${to ?: "–"}", style = MaterialTheme.typography.bodyMedium)
        }
        e.detail?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
