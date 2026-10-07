package cz.netdenik.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.netdenik.collect.MonitorService
import cz.netdenik.collect.Perms
import cz.netdenik.data.ConnState
import cz.netdenik.data.Prefs
import cz.netdenik.export.CsvExporter
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.Period
import cz.netdenik.ui.PeriodData
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.PrimaryPillButton
import cz.netdenik.ui.components.SegmentedSwitch
import cz.netdenik.ui.components.StateSwatch
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Přepínač 24 h / 7 dní; stejný na všech čtyřech záložkách. */
@Composable
fun PeriodSwitch(period: Period, onPeriod: (Period) -> Unit) {
    SegmentedSwitch(
        options = Period.entries.map { it.label },
        selected = period.ordinal,
        onSelect = { onPeriod(Period.entries[it]) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
    )
}

/** „Út 6. 10. · posledních 24 h“ nebo „30. 9. – 6. 10. · posledních 7 dní“. */
fun periodSubtitle(period: Period, data: PeriodData?): String {
    val now = data?.toMs ?: System.currentTimeMillis()
    return when (period) {
        Period.DAY -> "${Fmt.weekday(now)} · posledních 24 h"
        Period.WEEK -> {
            val from = data?.takeIf { it.period == Period.WEEK }?.fromMs ?: (now - 6 * 86_400_000L)
            "${Fmt.dayMonth(from)} – ${Fmt.dayMonth(now)} · posledních 7 dní"
        }
    }
}

/** Popisky osy dne: začátek s „včera“, tři časy po šesti hodinách a „teď“. */
fun dayAxisLabels(data: PeriodData): List<String> {
    val step = (data.toMs - data.fromMs) / 4
    return listOf(Fmt.timeRelative(data.fromMs, data.toMs)) +
        (1..3).map { Fmt.time(data.fromMs + it * step) } + "teď"
}

/** Dvě stejně široké buňky vedle sebe. */
@Composable
fun TwoColumns(spacing: Int = 12, left: @Composable RowScope.(Modifier) -> Unit, right: @Composable RowScope.(Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.dp)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}

/** Popisek sloupce jedné sítě: čtvereček v její barvě a text („Wi‑Fi · medián“). */
@Composable
fun TransportLabel(state: ConnState, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateSwatch(state)
        Text(text, style = NdType.SmallVariant)
    }
}

/** Dlaždice s popiskem nahoře (detail úseku). */
@Composable
fun StatTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Nd.Background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = NdType.Caption, color = Nd.TextVariant)
        Text(value, style = NdType.number(22))
        Text(caption, style = NdType.Caption)
    }
}

@Composable
fun MessageCard(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    NdCard {
        Text(title, style = NdType.CardTitle)
        Text(text, style = NdType.Small, color = Nd.TextVariant)
        action?.invoke()
    }
}

/** Co ukázat místo grafů, dokud není z čeho počítat. */
@Composable
fun NoDataCard(running: Boolean, onStart: () -> Unit) {
    if (running) {
        MessageCard("Zatím žádná data", "Měření běží. První čísla se objeví do minuty.")
    } else {
        MessageCard(
            "Zatím žádná data",
            "Deník zapisuje, přes co je telefon připojený, a měří odezvu a rychlost. Všechno zůstává jen v telefonu.",
        ) { PrimaryPillButton("Spustit měření", onStart) }
    }
}

/**
 * Spuštění měření včetně žádosti o oprávnění. Bez přesné polohy a stavu telefonu Android
 * nevydá název sítě ani buňku, takže bez nich nemá smysl službu pouštět.
 */
@Composable
fun rememberStartMeasurement(onProblem: (String?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    fun startNow() {
        Prefs(ctx).serviceWanted = true
        onProblem(if (MonitorService.start(ctx)) null else "Měření se nepodařilo spustit. Zkontroluj oprávnění v Nastavení.")
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Perms.canStart(ctx)) startNow()
        else onProblem("Bez přesné polohy a stavu telefonu Android nevydá název sítě ani buňku. Povol je v Nastavení.")
    }
    return { if (Perms.canStart(ctx)) startNow() else launcher.launch(Perms.initialRequest()) }
}

/** Export ZIPu s CSV přes systémový dialog uložení. Vrací akci, která dialog otevře. */
@Composable
fun rememberExport(onResult: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            onResult(
                try {
                    val rows = CsvExporter.exportZip(ctx, uri).values.sum()
                    "Export uložen: ${Fmt.count(rows)} řádků v 8 souborech."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Export se nepovedl (${e.message}). Zkus jiné umístění."
                },
            )
        }
    }
    return { launcher.launch("netdenik-${Fmt.fileStamp(System.currentTimeMillis())}.zip") }
}
