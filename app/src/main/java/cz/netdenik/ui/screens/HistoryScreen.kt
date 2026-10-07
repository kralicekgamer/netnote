package cz.netdenik.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.netdenik.data.ConnState
import cz.netdenik.data.EventType
import cz.netdenik.data.Prefs
import cz.netdenik.data.StateSegment
import cz.netdenik.logic.HistoryFilter
import cz.netdenik.logic.HistoryItem
import cz.netdenik.logic.HistoryMark
import cz.netdenik.logic.HistoryRow
import cz.netdenik.logic.SegmentInfo
import cz.netdenik.logic.bssidShort
import cz.netdenik.logic.collapseApRuns
import cz.netdenik.logic.historyRows
import cz.netdenik.logic.matches
import cz.netdenik.logic.mobileName
import cz.netdenik.logic.moreApChangesLabel
import cz.netdenik.logic.slices
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.Period
import cz.netdenik.ui.PeriodData
import cz.netdenik.ui.charts.AxisLabels
import cz.netdenik.ui.charts.StateTimeline
import cz.netdenik.ui.charts.stateBackground
import cz.netdenik.ui.components.CardTitle
import cz.netdenik.ui.components.FilterPill
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.ScreenHeader
import cz.netdenik.ui.components.StateLegend
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType

/** Položka seznamu: nadpis dne (jen v týdnu), řádek historie, nebo rozbalovací tlačítko. */
private sealed interface Entry {
    data class Day(val label: String) : Entry
    data class Item(val item: HistoryItem, val first: Boolean, val last: Boolean) : Entry
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(data: PeriodData?, period: Period, onPeriod: (Period) -> Unit, onSegment: (Long) -> Unit) {
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberExport { message = it }
    val ready = data?.takeIf { it.period == period }

    val rows = remember(ready) {
        if (ready == null) emptyList() else {
            val names = Prefs(ctx).apNames()
            historyRows(
                segments = ready.segments,
                previous = ready.previous,
                events = ready.events,
                info = { segmentInfo(ready, it, names) },
                openSegmentId = ready.openSegmentId,
                duration = Fmt::duration,
                apName = { names[it.lowercase()] },
            )
        }
    }
    val entries = remember(rows, filter, expanded, period) {
        val visible = rows.filter { it.matches(filter) }
        val items = collapseApRuns(visible, expanded)
        // Každý den má vlastní kartu. V pohledu 24 h je dnešek bez nadpisu a nadpis dostane
        // až včerejší část, aby po 00:37 nenásledovalo 20:57 bez vysvětlení.
        buildList<Entry> {
            val groups = items.groupBy { Fmt.weekday(it.ts(rows)) }
            var first = true
            for ((day, group) in groups) {
                if (period == Period.WEEK || !first) add(Entry.Day(day))
                group.forEachIndexed { i, item -> add(Entry.Item(item, i == 0, i == group.lastIndex)) }
                first = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader(
                "Historie změn", periodSubtitle(period, data),
                actionIcon = NdIcons.Export, actionLabel = "Exportovat", onAction = export,
            )
        }
        message?.let { text ->
            item { Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), style = NdType.Small, color = Nd.TextVariant) }
        }
        item { PeriodSwitch(period, onPeriod) }
        if (ready == null) return@LazyColumn
        if (!ready.hasData) {
            item {
                Box(Modifier.padding(16.dp)) {
                    MessageCard("Zatím žádné změny", "Jakmile se telefon přepne mezi Wi‑Fi a daty nebo změní přístupový bod, objeví se to tady.")
                }
            }
            return@LazyColumn
        }
        item {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                if (period == Period.DAY) DayTimelineCard(ready) else WeekTimelineCard(ready)
            }
        }
        item {
            FlowRow(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in HistoryFilter.entries) {
                    val label = if (f == HistoryFilter.ALL) "${f.label} · ${rows.count { it.matches(f) }}" else f.label
                    FilterPill(label, f == filter) { filter = f }
                }
            }
        }
        if (entries.isEmpty()) {
            item { Text("V tomhle období nic takového nebylo.", Modifier.padding(16.dp), style = NdType.Small, color = Nd.TextVariant) }
        }
        items(entries, key = { it.key() }) { entry ->
            when (entry) {
                is Entry.Day -> Text(
                    entry.label,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    style = NdType.SmallVariant, fontWeight = FontWeight.Medium,
                )
                is Entry.Item -> Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = if (entry.first) 12.dp else 0.dp)
                        .clip(cardSlice(entry.first, entry.last))
                        .background(Nd.Surface)
                        .padding(top = if (entry.first) 6.dp else 0.dp, bottom = if (entry.last) 6.dp else 0.dp),
                ) {
                    when (val item = entry.item) {
                        is HistoryItem.Row -> HistoryRowView(item.row, divider = !entry.last, onSegment)
                        is HistoryItem.More -> MoreRow(moreApChangesLabel(item.hidden), divider = !entry.last) {
                            expanded = expanded + item.runKey
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(16.dp)) }
    }
}

private fun Entry.key(): String = when (this) {
    is Entry.Day -> "day:$label"
    is Entry.Item -> when (val i = item) {
        is HistoryItem.Row -> i.row.key
        is HistoryItem.More -> "more:${i.runKey}"
    }
}

/** Čas položky; rozbalovací tlačítko patří ke dni své série. */
private fun HistoryItem.ts(rows: List<HistoryRow>): Long = when (this) {
    is HistoryItem.Row -> row.ts
    is HistoryItem.More -> rows.first { it.key == runKey }.ts
}

/** Seznam je jedna karta rozdělená na líné položky: zaoblená je jen první a poslední. */
private fun cardSlice(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
    bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
)

/** Síť, přístupový bod a buňky jednoho úseku ze vzorků, které do něj spadají. */
private fun segmentInfo(data: PeriodData, s: StateSegment, apNames: Map<String, String>): SegmentInfo = when (s.state) {
    ConnState.WIFI -> {
        val first = data.wifiIn(s).firstOrNull { it.ssid != null || it.bssid != null }
        SegmentInfo(
            network = first?.ssid,
            accessPoint = first?.bssid?.let { apNames[it.lowercase()] ?: bssidShort(it) },
        )
    }
    ConnState.MOBILE -> {
        val cells = data.cellsIn(s)
        SegmentInfo(
            network = cells.lastOrNull()?.let(::mobileName),
            cellId = cells.lastOrNull { it.cellId != null }?.cellId,
            cellCount = cells.mapNotNull { it.cellId }.distinct().size,
        )
    }
    else -> SegmentInfo()
}

@Composable
private fun DayTimelineCard(data: PeriodData) {
    val apChanges = remember(data) { data.events.filter { it.type == EventType.AP_CHANGE }.map { it.ts } }
    NdCard(spacing = 10.dp) {
        CardTitle("Průběh dne")
        StateTimeline(data.slices, data.fromMs, data.toMs, Modifier.height(34.dp), radius = 8.dp, markers = apChanges)
        AxisLabels(dayAxisLabels(data))
        StateLegend(data.coverage.shares.filter { it.ms > 0 }.map { it.state }.sortedBy { it.ordinal }) {
            if (apChanges.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(2.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(Nd.Text))
                    Text("Změna AP", style = NdType.Caption, color = Nd.TextVariant)
                }
            }
        }
    }
}

/** Týden jako sedm pruhů pod sebou, každý od půlnoci do půlnoci. */
@Composable
private fun WeekTimelineCard(data: PeriodData) {
    NdCard(spacing = 10.dp) {
        CardTitle("Průběh týdne")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (back in 6 downTo 0) {
                val date = data.today.minusDays(back.toLong())
                val from = date.atStartOfDay(data.zone).toInstant().toEpochMilli()
                val to = date.plusDays(1).atStartOfDay(data.zone).toInstant().toEpochMilli()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        Fmt.dayName(date), Modifier.width(22.dp), style = NdType.Caption,
                        color = if (back == 0) Nd.Text else Nd.TextVariant,
                        fontWeight = if (back == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    StateTimeline(slices(data.segments, from, to), from, to, Modifier.weight(1f).height(18.dp), radius = 4.dp)
                }
            }
        }
        AxisLabels(listOf("0", "6", "12", "18", "24"), Modifier.padding(start = 28.dp))
        StateLegend(data.coverage.shares.filter { it.ms > 0 }.map { it.state }.sortedBy { it.ordinal })
    }
}

@Composable
private fun HistoryRowView(row: HistoryRow, divider: Boolean, onSegment: (Long) -> Unit) {
    val target = row.segmentId
    Column(if (row.highlighted) Modifier.background(Nd.RowHighlight) else Modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (target != null) Modifier.clickable { onSegment(target) } else Modifier)
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(Fmt.time(row.ts), Modifier.width(44.dp), style = NdType.MonoSmall)
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { Mark(row) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = NdType.BodyMedium)
                if (row.subtitle != null) {
                    Text(
                        row.subtitle.split(" · ").joinToString("\u00A0· ") { it.replace(' ', '\u00A0') },
                        style = if (row.monoSubtitle) NdType.MonoCaption else NdType.Caption,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (target != null) Icon(NdIcons.Chevron, "Detail úseku", Modifier.size(18.dp), tint = Nd.TextVariant)
        }
        if (divider) HorizontalDivider(color = Nd.Divider)
    }
}

@Composable
private fun Mark(row: HistoryRow) {
    when (row.mark) {
        HistoryMark.STATE -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .stateBackground(row.state),
        )
        HistoryMark.RING -> Box(Modifier.size(10.dp).border(2.dp, Nd.state(row.state), CircleShape))
        HistoryMark.HATCH -> Box(
            Modifier
                .size(10.dp)
                .border(1.dp, Nd.Muted, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
                .stateBackground(ConnState.UNKNOWN, behind = Nd.RowHighlight),
        )
    }
}

@Composable
private fun MoreRow(text: String, divider: Boolean, onClick: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClick = onClick)
                .padding(start = 84.dp, end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, style = NdType.Small, fontWeight = FontWeight.Medium, color = Nd.Primary)
        }
        if (divider) HorizontalDivider(color = Nd.Divider)
    }
}
