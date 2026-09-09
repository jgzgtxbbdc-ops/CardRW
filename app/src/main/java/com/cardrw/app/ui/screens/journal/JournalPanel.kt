package com.cardrw.app.ui.screens.journal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.ui.theme.ApduError
import com.cardrw.app.ui.theme.ApduIn
import com.cardrw.app.ui.theme.ApduOut
import com.cardrw.app.viewmodel.JournalViewModel
import com.cardrw.desfire.log.AnnotationLevel
import com.cardrw.desfire.log.ApduDirection
import com.cardrw.desfire.log.ApduLogEntry

@Composable
fun JournalPanel(
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = hiltViewModel(),
    showToolbar: Boolean = true,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = level == AnnotationLevel.SUMMARY,
                onClick = { viewModel.setLevel(AnnotationLevel.SUMMARY) },
                label = { Text(stringResource(R.string.journal_level_summary)) },
            )
            FilterChip(
                selected = level == AnnotationLevel.DETAILED,
                onClick = { viewModel.setLevel(AnnotationLevel.DETAILED) },
                label = { Text(stringResource(R.string.journal_level_detailed)) },
            )
            FilterChip(
                selected = level == AnnotationLevel.HEX,
                onClick = { viewModel.setLevel(AnnotationLevel.HEX) },
                label = { Text(stringResource(R.string.journal_level_hex)) },
            )
            if (showToolbar) {
                IconButton(
                    onClick = {
                        val text = viewModel.exportText()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CardRW APDU", text))
                        Toast.makeText(context, context.getString(R.string.journal_copied), Toast.LENGTH_SHORT).show()
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.journal_export))
                }
                IconButton(
                    onClick = { viewModel.clear() },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.journal_clear))
                }
            }
        }

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.journal_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.timestampEpochMs.toString() + it.rawHex + it.direction }) { entry ->
                    ApduLine(entry, level)
                }
            }
        }
    }
}

@Composable
private fun ApduLine(entry: ApduLogEntry, level: AnnotationLevel) {
    val color = when {
        entry.status?.isError == true -> ApduError
        entry.direction == ApduDirection.OUT -> ApduOut
        else -> ApduIn
    }
    Text(
        entry.format(level),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}
