package cz.netdenik.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cz.netdenik.R
import cz.netdenik.data.ConnState

/** Barvy z design/DESIGN.md (tmavý režim). Světlý režim návrh zatím nemá. */
object Nd {
    val Background = Color(0xFF1E1E2E)
    val Surface = Color(0xFF27273A)
    val SurfaceDim = Color(0xFF181825)
    val SurfaceHigh = Color(0xFF34344A)
    val Divider = Color(0xFF313147)
    val Outline = Color(0xFF45475A)
    val Text = Color(0xFFCDD6F4)
    val TextVariant = Color(0xFFA6ADC8)
    val Muted = Color(0xFF9399B2)
    val Faint = Color(0xFF7F849C)
    val Primary = Color(0xFF89B4FA)
    val PrimaryContainer = Color(0xFF2F3A55)
    val Success = Color(0xFFA6E3A1)
    val Error = Color(0xFFF38BA8)
    val Warning = Color(0xFFF9E2AF)
    val WarningContainer = Color(0xFF35322F)
    val RowHighlight = Color(0xFF2B2B40)

    val Wifi = Primary
    val Mobile = Color(0xFFFAB387)
    val MobileContainer = Color(0xFF4A3A35)
    val Searching = Color(0xFFF9E2AF)
    val Offline = Color(0xFF585B70)
    val PhoneOff = Color(0xFF45475A)
    val PhoneOffOutline = Color(0xFF6C7086)
    val Unknown = Color(0xFF7F849C)
    val Other = Color(0xFFB4BEFE)

    /** Plná barva stavu. Neznámo se kreslí šrafou, tahle barva je jen barva jejích čar. */
    fun state(s: ConnState): Color = when (s) {
        ConnState.WIFI -> Wifi
        ConnState.MOBILE -> Mobile
        ConnState.SEARCHING -> Searching
        ConnState.OFFLINE -> Offline
        ConnState.PHONE_OFF -> PhoneOff
        ConnState.UNKNOWN -> Unknown
        ConnState.OTHER -> Other
    }
}

/** Jména stavů tak, jak je píše návrh. */
fun stateName(s: ConnState): String = when (s) {
    ConnState.WIFI -> "Wi‑Fi"
    ConnState.MOBILE -> "Mobilní data"
    ConnState.SEARCHING -> "Hledání signálu"
    ConnState.OFFLINE -> "Offline"
    ConnState.PHONE_OFF -> "Telefon vypnutý"
    ConnState.UNKNOWN -> "Neznámo"
    ConnState.OTHER -> "Jiné připojení"
}

/** Krátká jména do legend grafů. */
fun stateShort(s: ConnState): String = when (s) {
    ConnState.WIFI -> "Wi‑Fi"
    ConnState.MOBILE -> "Data"
    ConnState.SEARCHING -> "Hledání"
    ConnState.OFFLINE -> "Offline"
    ConnState.PHONE_OFF -> "Vypnutý"
    ConnState.UNKNOWN -> "Neznámo"
    ConnState.OTHER -> "Jiné"
}

// Obě písma jsou variabilní; Compose si řez nastaví přes osu wght podle zadané váhy.
val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal),
    Font(R.font.geist, FontWeight.Medium),
    Font(R.font.geist, FontWeight.SemiBold),
)

/** Všechna čísla, časy, IP, BSSID a ID buněk. */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
)

/** Velikosti z návrhu. */
object NdType {
    val ScreenTitle = TextStyle(fontFamily = Geist, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, color = Nd.Text)
    val SubpageTitle = TextStyle(fontFamily = Geist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Nd.Text)
    val CardTitle = TextStyle(fontFamily = Geist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Nd.Text)
    val SectionLabel = TextStyle(fontFamily = Geist, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Nd.Primary)
    val Row = TextStyle(fontFamily = Geist, fontSize = 15.sp, color = Nd.Text)
    val Body = TextStyle(fontFamily = Geist, fontSize = 14.sp, color = Nd.Text)
    val BodyMedium = TextStyle(fontFamily = Geist, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Nd.Text)
    val Small = TextStyle(fontFamily = Geist, fontSize = 13.sp, color = Nd.Text)
    val SmallVariant = TextStyle(fontFamily = Geist, fontSize = 13.sp, color = Nd.TextVariant)
    val Caption = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, color = Nd.Muted)
    val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Nd.Text)
    val MonoCaption = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Nd.Muted)
    val Axis = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = Nd.Muted)
    val Tile = TextStyle(fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Nd.Text)

    fun number(size: Int) = TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = FontWeight.Medium, color = Nd.Text)
}

private val Colors = darkColorScheme(
    primary = Nd.Primary,
    onPrimary = Nd.Background,
    primaryContainer = Nd.PrimaryContainer,
    onPrimaryContainer = Nd.Text,
    secondaryContainer = Nd.SurfaceHigh,
    onSecondaryContainer = Nd.Text,
    background = Nd.Background,
    onBackground = Nd.Text,
    surface = Nd.Background,
    onSurface = Nd.Text,
    surfaceVariant = Nd.SurfaceHigh,
    onSurfaceVariant = Nd.TextVariant,
    surfaceContainerLowest = Nd.SurfaceDim,
    surfaceContainerLow = Nd.Surface,
    surfaceContainer = Nd.Surface,
    surfaceContainerHigh = Nd.Surface,
    surfaceContainerHighest = Nd.SurfaceHigh,
    outline = Nd.Outline,
    outlineVariant = Nd.Divider,
    error = Nd.Error,
)

@Composable
fun NetDenikTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Geist),
            bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
            bodySmall = base.bodySmall.copy(fontFamily = Geist),
            titleLarge = base.titleLarge.copy(fontFamily = Geist),
            titleMedium = base.titleMedium.copy(fontFamily = Geist),
            titleSmall = base.titleSmall.copy(fontFamily = Geist),
            labelLarge = base.labelLarge.copy(fontFamily = Geist),
            labelMedium = base.labelMedium.copy(fontFamily = Geist),
            labelSmall = base.labelSmall.copy(fontFamily = Geist),
        ),
        content = content,
    )
}
