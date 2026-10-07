package cz.netdenik.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.netdenik.ui.theme.Mono
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdType

@Composable
internal fun InfoDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
    confirmColor: Color = Nd.Primary,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nd.Surface,
        title = { Text(title, style = NdType.SubpageTitle) },
        text = { Text(text, style = NdType.Body, color = Nd.TextVariant) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = confirmColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = Nd.TextVariant) } },
    )
}

/** Dialog s jedním nebo dvěma textovými poli. Chyba se ukáže až po pokusu o uložení. */
@Composable
internal fun EditDialog(
    title: String,
    fields: List<Pair<String, String>>,
    hint: String,
    validate: (List<String>) -> String?,
    onDismiss: () -> Unit,
    mono: Boolean = true,
    onSave: (List<String>) -> Unit,
) {
    var values by remember { mutableStateOf(fields.map { it.second }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nd.Surface,
        title = { Text(title, style = NdType.SubpageTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEachIndexed { i, (label, _) ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { new ->
                            values = values.toMutableList().also { it[i] = new }
                            error = null
                        },
                        label = { Text(label) },
                        singleLine = true,
                        textStyle = NdType.Body.copy(fontFamily = if (mono) Mono else NdType.Body.fontFamily),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(error ?: hint, style = NdType.Caption, color = if (error != null) Nd.Error else Nd.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = values.map { it.trim() }
                error = validate(trimmed)
                if (error == null) onSave(trimmed)
            }) { Text("Uložit", color = Nd.Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit", color = Nd.TextVariant) } },
    )
}

/** Jedna volba v [ChoiceDialog]; [note] je šedý druhý řádek („do 576 MB denně“). */
internal class Choice<T>(val value: T, val label: String, val note: String? = null)

/** Výběr jedné z několika hodnot. Klepnutí na řádek rovnou vybere a dialog zavře. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<Choice<T>>,
    selected: T,
    onDismiss: () -> Unit,
    hint: String? = null,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nd.Surface,
        title = { Text(title, style = NdType.SubpageTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(selected = option.value == selected, role = Role.RadioButton) { onPick(option.value) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = option.value == selected, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Nd.Primary, unselectedColor = Nd.Faint),
                        )
                        Column {
                            Text(option.label, style = NdType.Row)
                            if (option.note != null) Text(option.note, style = NdType.Caption)
                        }
                    }
                }
                if (hint != null) Text(hint, Modifier.padding(top = 10.dp), style = NdType.Caption)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zrušit", color = Nd.TextVariant) } },
    )
}
