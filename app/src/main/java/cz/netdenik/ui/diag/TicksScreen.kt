package cz.netdenik.ui.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.Tick

/** Jak spolehlivě telefon pouští minutové ticky. Slouží k ladění chování na pozadí. */
@Composable
fun TicksScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    var ticks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        val to = System.currentTimeMillis()
        ticks = dao.ticksBetween(to - 24 * 3_600_000L, to)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedButton(onClick = { reload++ }) { Text("Přepočítat") }
        Section("Ticky za posledních 24 h") {
            val planned = ticks.filter { it.trigger == "ALARM" || it.trigger == "WATCHDOG" }
            KeyValue("Ticků celkem", ticks.size)
            KeyValue("Průměrné zpoždění", planned.takeIf { it.isNotEmpty() }?.let { p -> "${p.sumOf { it.latenessMs } / p.size} ms" })
            KeyValue("Největší zpoždění", planned.maxOfOrNull { it.latenessMs }?.let { Fmt.duration(it) + " ($it ms)" })
            KeyValue("Opožděných o víc než 10 s", planned.count { it.latenessMs > 10_000 })
            KeyValue("Rozestup zhasnuto, bez výjimky", sleepSpacing(ticks, exempt = false))
            KeyValue("Rozestup zhasnuto, s výjimkou", sleepSpacing(ticks, exempt = true))
            Text(
                "Rozestup = průměrná doba mezi ticky se zhasnutým displejem na baterii. Telefon tu běžně povolí jen kolem 5 minut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValue("Ticků na nabíječce", ticks.count { it.plugged })
            KeyValue("Ticků v Doze", ticks.count { it.deviceIdle })
            KeyValue("Ticků bez přesného budíku", ticks.count { !it.exactAlarm })
        }
    }
}

/** Průměrný rozestup ticků, které přišly se zhasnutým displejem a na baterii. */
private fun sleepSpacing(ticks: List<Tick>, exempt: Boolean): String? {
    val gaps = ticks.zipWithNext()
        .filter { (_, t) -> !t.screenOn && !t.plugged && t.batteryOptIgnored == exempt && t.trigger != "START" }
        .map { (prev, t) -> t.ts - prev.ts }
    if (gaps.isEmpty()) return null
    return "${Fmt.duration(gaps.average().toLong())} (${gaps.size} ticků, nejdelší ${Fmt.duration(gaps.max())})"
}
