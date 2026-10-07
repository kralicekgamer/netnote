package cz.netdenik.ui.diag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private val TABS = listOf("Teď", "Log", "Ticky")

/** Původní testovací výpis: syrové hodnoty z databáze, log událostí a spolehlivost ticků. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> NowScreen()
                1 -> LogScreen()
                else -> TicksScreen()
            }
        }
    }
}
