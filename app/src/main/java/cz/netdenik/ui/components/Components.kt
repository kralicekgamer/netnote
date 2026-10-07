package cz.netdenik.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.netdenik.data.ConnState
import cz.netdenik.ui.charts.stateBackground
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType
import cz.netdenik.ui.theme.stateShort

/** Karta z návrhu: surface, zaoblení 20 dp, padding 16 dp. */
@Composable
fun NdCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Nd.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Nadpis karty, vpravo volitelná poznámka („medián za 15 min“) nebo šipka u klikací karty. */
@Composable
fun CardTitle(title: String, note: String? = null, chevron: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = NdType.CardTitle)
        if (note != null) Text(note, style = NdType.Caption)
        if (chevron) Icon(NdIcons.Chevron, null, Modifier.size(18.dp), tint = Nd.TextVariant)
    }
}

/** Dlaždice metriky: velké číslo neproporcionálním písmem a popisek. */
@Composable
fun MetricTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Nd.Background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = NdType.Tile)
        Text(label, style = NdType.SmallVariant)
    }
}

/** Segmentový přepínač (24 h / 7 dní): segment 44 dp, aktivní surfaceHigh. */
@Composable
fun SegmentedSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nd.SurfaceDim)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Nd.SurfaceHigh else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = NdType.BodyMedium, color = if (active) Nd.Text else Nd.TextVariant)
            }
        }
    }
}

/** Pilulka filtru. Vizuálně 36 dp, dotyková plocha je vyšší díky okolnímu rozestupu. */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) Nd.PrimaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Nd.Primary else Nd.Outline, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = NdType.Small,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Nd.Text else Nd.TextVariant,
        )
    }
}

/** Čtvereček v barvě stavu; Neznámo šrafovaně, vypnutý telefon s obrysem. */
@Composable
fun StateSwatch(state: ConnState, modifier: Modifier = Modifier, size: Dp = 10.dp, behind: Color = Nd.Surface) {
    Box(
        modifier
            .size(size)
            .then(if (state == ConnState.UNKNOWN) Modifier.border(1.dp, Nd.Unknown, RoundedCornerShape(3.dp)) else Modifier)
            .clip(RoundedCornerShape(3.dp))
            .stateBackground(state, behind = behind),
    )
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Legenda grafu: čtverečky stavů s krátkými jmény, zalamuje se. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateLegend(states: List<ConnState>, modifier: Modifier = Modifier, extra: @Composable RowScope.() -> Unit = {}) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in states) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StateSwatch(s)
                Text(stateShort(s), style = NdType.Caption, color = Nd.TextVariant)
            }
        }
        extra()
    }
}

/** Řádek detailu: název vlevo, hodnota vpravo neproporcionálním písmem, pod ním oddělovač. */
@Composable
fun DetailRow(label: String, value: String?, divider: Boolean = true, mono: Boolean = true) {
    DetailRow(label, divider) {
        Text(
            value ?: "–",
            style = if (mono) NdType.MonoSmall else NdType.Small,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun DetailRow(label: String, divider: Boolean = true, value: @Composable () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = NdType.SmallVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
        }
        if (divider) HorizontalDivider(color = Nd.Divider)
    }
}

/**
 * Řádek nastavení: titulek a podtitulek vlevo, vpravo hodnota a/nebo vlastní prvek
 * (přepínač, tlačítko, ikona). Celý řádek je klikací, když má [onClick].
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = NdType.Row,
    monoSubtitle: Boolean = false,
    subtitleColor: Color = Nd.Muted,
    value: String? = null,
    monoValue: Boolean = false,
    chevron: Boolean = false,
    divider: Boolean = true,
    minHeight: Dp = 60.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = titleStyle)
                if (subtitle != null) {
                    Text(subtitle, style = if (monoSubtitle) NdType.MonoCaption else NdType.Caption, color = subtitleColor)
                }
            }
            if (value != null) {
                Text(value, style = if (monoValue) NdType.MonoSmall.copy(fontSize = NdType.Body.fontSize) else NdType.Body, color = Nd.TextVariant)
            }
            trailing?.invoke()
            if (chevron) Icon(NdIcons.Chevron, null, Modifier.size(16.dp), tint = Nd.Faint)
        }
        if (divider) HorizontalDivider(color = Nd.Divider)
    }
}

/** Hlavička záložky: nadpis, podtitulek a volitelné kulaté tlačítko vpravo. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NdType.ScreenTitle)
            Text(subtitle, style = NdType.SmallVariant)
        }
        if (actionIcon != null) RoundIconButton(actionIcon, actionLabel, onAction, background = Nd.Surface)
    }
}

/** Hlavička podstránky: šipka zpět a nadpis. */
@Composable
fun SubpageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoundIconButton(NdIcons.Back, "Zpět", onBack, background = Color.Transparent)
        Text(title, style = if (large) NdType.SubpageTitle.copy(fontSize = NdType.ScreenTitle.fontSize * 20 / 22) else NdType.SubpageTitle)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, label: String?, onClick: () -> Unit, background: Color) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = Nd.Text)
    }
}

/** Šedá pilulka se stavem („Internet ověřen“, „Bez VPN“). */
@Composable
fun StatusChip(text: String, dot: Color? = null) {
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(Nd.Surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Dot(dot, size = 6.dp)
        Text(text, style = NdType.Caption, color = Nd.TextVariant)
    }
}

/** Barevný štítek uvnitř řádku („NAT u operátora“). */
@Composable
fun Tag(text: String, color: Color, container: Color) {
    Box(
        Modifier
            .height(26.dp)
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = NdType.Caption, fontWeight = FontWeight.Medium, color = color)
    }
}

/** Hlavní tlačítko: plná pilulka 48 dp v barvě primary. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(if (enabled) Nd.Primary else Nd.SurfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val content = if (enabled) Nd.Background else Nd.Muted
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(text, style = NdType.Row, fontWeight = FontWeight.SemiBold, color = content)
    }
}

/** Obrysová pilulka; [large] = 48 dp přes celou šířku, jinak 36 dp podle textu. */
@Composable
fun OutlinedPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Nd.Text,
    borderColor: Color = Nd.Outline,
    large: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .then(if (large) Modifier.fillMaxWidth().height(48.dp) else Modifier.height(36.dp).defaultMinSize(minWidth = 48.dp))
            .clip(CircleShape)
            .border(BorderStroke(1.dp, if (enabled) borderColor else Nd.Divider), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val content = if (enabled) color else Nd.Muted
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(text, style = if (large) NdType.Row else NdType.Small, fontWeight = FontWeight.Medium, color = content)
    }
}

/** Textový odkaz s dostatečnou dotykovou plochou. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, chevron: Boolean = false, color: Color = Nd.Primary) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = NdType.BodyMedium, color = color)
        if (chevron) Icon(NdIcons.Chevron, null, Modifier.size(16.dp), tint = color)
    }
}

/** Poznámka pod čarou uvnitř karty. */
@Composable
fun CardFootnote(text: String) {
    Column {
        HorizontalDivider(color = Nd.SurfaceHigh)
        Text(text, Modifier.padding(top = 10.dp), style = NdType.Caption)
    }
}

@Composable
fun ItalicPlaceholder(text: String) {
    Text(text, style = NdType.Row, color = Nd.TextVariant, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
