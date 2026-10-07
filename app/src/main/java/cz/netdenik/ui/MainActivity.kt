package cz.netdenik.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cz.netdenik.collect.MonitorService
import cz.netdenik.data.Prefs
import cz.netdenik.ui.theme.NetDenikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Appka je zatím jen tmavá, takže ikony systémových lišt musí být vždy světlé.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Když měření má běžet a neběží (pád, zabití systémem), otevření appky ho znovu nahodí.
        if (Prefs(this).serviceWanted && MonitorService.instance == null) MonitorService.start(this)
        setContent { NetDenikTheme { AppRoot() } }
    }
}
