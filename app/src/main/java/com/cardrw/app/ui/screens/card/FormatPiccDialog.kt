package com.cardrw.app.ui.screens.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cardrw.app.R

@Composable
fun FormatPiccDialog(
    uidDisplay: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val expected = formatConfirmToken(uidDisplay)
    var typed by remember { mutableStateOf("") }
    val match = typed.equals(expected, ignoreCase = true)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.card_format_picc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.card_format_picc_message))
                Text(
                    text = stringResource(R.string.card_format_picc_uid, uidDisplay),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it.filter { c -> c.isLetterOrDigit() }.take(expected.length).uppercase()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.card_format_picc_confirm_field, expected)) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy && match,
            ) {
                Text(stringResource(R.string.card_format_picc_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.card_auth_cancel))
            }
        },
    )
}

internal fun formatConfirmToken(uidDisplay: String): String {
    val hex = uidDisplay.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
    return if (hex.length >= 4) hex.takeLast(4) else "FORMAT"
}
