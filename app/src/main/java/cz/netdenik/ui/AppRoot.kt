package cz.netdenik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.netdenik.ui.components.SubpageHeader
import cz.netdenik.ui.diag.DiagnosticsScreen
import cz.netdenik.ui.screens.HistoryScreen
import cz.netdenik.ui.screens.OverviewScreen
import cz.netdenik.ui.screens.PingScreen
import cz.netdenik.ui.screens.SegmentDetailScreen
import cz.netdenik.ui.screens.SettingsScreen
import cz.netdenik.ui.screens.SpeedScreen
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType

private class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Přehled", NdIcons.Overview),
    Tab("Historie", NdIcons.History),
    Tab("Ping", NdIcons.Ping),
    Tab("Rychlost", NdIcons.Speed),
)

private const val SETTINGS = "settings"
private const val DIAGNOSTICS = "diagnostics"
private const val SEGMENT = "segment:"

/**
 * Kostra appky: čtyři záložky ve spodní liště a nad nimi zásobník podstránek
 * (detail úseku, Nastavení, Diagnostika). Navigační knihovna na to není potřeba.
 */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(Period.DAY) }
    // Podstránky jako řetězce, aby šel zásobník uložit při otočení i ukončení procesu.
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val data = rememberPeriodData(period)

    fun push(route: String) {
        stack = stack + route
    }

    fun pop() {
        stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.isNotEmpty() || tab != 0) {
        if (stack.isNotEmpty()) pop() else tab = 0
    }

    val top = stack.lastOrNull()
    // Detail úseku lištu má, Nastavení a Diagnostika ne (podle návrhu).
    val showBar = top == null || top.startsWith(SEGMENT)

    Scaffold(
        containerColor = Nd.Background,
        bottomBar = {
            if (showBar) {
                BottomBar(tab) {
                    tab = it
                    stack = emptyList()
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                top == SETTINGS -> SettingsScreen(onBack = ::pop, onDiagnostics = { push(DIAGNOSTICS) })
                top == DIAGNOSTICS -> Column(Modifier.fillMaxSize()) {
                    SubpageHeader("Diagnostika", ::pop, large = true)
                    DiagnosticsScreen()
                }
                top != null && top.startsWith(SEGMENT) ->
                    SegmentDetailScreen(top.removePrefix(SEGMENT).toLong(), onBack = ::pop)
                tab == 0 -> OverviewScreen(
                    data, period, { period = it },
                    onSettings = { push(SETTINGS) },
                    onHistory = { tab = 1 }, onPing = { tab = 2 }, onSpeed = { tab = 3 },
                )
                tab == 1 -> HistoryScreen(data, period, { period = it }, onSegment = { push(SEGMENT + it) })
                tab == 2 -> PingScreen(data, period) { period = it }
                else -> SpeedScreen(data, period) { period = it }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = Nd.Surface)
        NavigationBar(containerColor = Nd.SurfaceDim, tonalElevation = 0.dp) {
            TABS.forEachIndexed { i, tab ->
                NavigationBarItem(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    icon = { Icon(tab.icon, null, Modifier.size(22.dp)) },
                    label = { Text(tab.label, style = NdType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = androidx.compose.ui.graphics.Color.Unspecified)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Nd.Text,
                        selectedTextColor = Nd.Text,
                        indicatorColor = Nd.SurfaceHigh,
                        unselectedIconColor = Nd.TextVariant,
                        unselectedTextColor = Nd.TextVariant,
                    ),
                )
            }
        }
    }
}
