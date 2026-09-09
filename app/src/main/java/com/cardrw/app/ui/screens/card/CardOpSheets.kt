package com.cardrw.app.ui.screens.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardrw.app.R
import com.cardrw.app.ui.components.BusyLabel
import com.cardrw.app.viewmodel.CapturePreviewUi
import com.cardrw.app.viewmodel.DumpCoverageUi
import com.cardrw.app.viewmodel.DumpMode
import com.cardrw.app.viewmodel.ProfileSummary
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.util.Hex

@Composable
fun CaptureProfileSheet(
    preview: CapturePreviewUi,
    busy: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onToggleSlot: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_capture_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_capture_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = preview.suggestedName,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.card_capture_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.card_capture_slots_header),
            style = MaterialTheme.typography.labelMedium,
        )
        preview.slots.forEach { slot ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { onToggleSlot(slot.selectionKey) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = slot.selected,
                    onCheckedChange = { onToggleSlot(slot.selectionKey) },
                    enabled = !busy,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = slot.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (preview.vaultCreatesNeeded > 0) {
            Text(
                text = stringResource(
                    R.string.card_capture_vault_creates,
                    preview.vaultCreatesNeeded,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConfirm,
            enabled = !busy &&
                preview.suggestedName.isNotBlank() &&
                preview.slots.any { it.selected },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = busy, text = stringResource(R.string.card_capture_confirm))
        }
        TextButton(
            onClick = onDismiss,
            enabled = !busy,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun ProfilePickerSheet(
    profiles: List<ProfileSummary>,
    activeProfileId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.card_profile_picker_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_profile_picker_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = activeProfileId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.card_profile_none_option)) },
        )
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.card_profile_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            profiles.forEach { p ->
                FilterChip(
                    selected = p.id == activeProfileId,
                    onClick = { onSelect(p.id) },
                    label = {
                        Text(
                            stringResource(
                                R.string.card_profile_picker_item,
                                p.displayName,
                                p.bindingCount,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun WriteFileSheetContent(
    node: FileNode,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onWrite: (hex: String, padToFileSize: Boolean) -> Unit,
) {
    val size = node.settings.sizeBytes
    val current = node.dataHex.orEmpty()
    var writeHex by remember(node.fileNo) {
        mutableStateOf(current.ifEmpty { "00" })
    }
    LaunchedEffect(current) {
        if (!busy && current.isNotEmpty()) {
            writeHex = current
        }
    }
    var replaceAll by rememberSaveable(node.fileNo) { mutableStateOf(true) }
    val nBytes = writeHex.length / 2
    val tooLong = size != null && nBytes > size
    val justWrote = statusLine?.startsWith("WriteData OK") == true && !busy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_write_sheet_title, node.fileNo),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append(node.settings.summaryLabel)
                append(" · W=")
                append(node.settings.accessRights.writeLabel)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.card_write_sheet_current),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = if (current.isNotEmpty()) {
                prettyHex(current)
            } else {
                stringResource(R.string.card_write_sheet_current_empty)
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (justWrote) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (justWrote) {
            Text(
                text = statusLine.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (current.isNotEmpty()) {
            TextButton(onClick = { writeHex = current }, enabled = !busy) {
                Text(stringResource(R.string.card_write_sheet_use_current))
            }
        }
        OutlinedTextField(
            value = writeHex,
            onValueChange = {
                writeHex = it.replace(Regex("[^0-9a-fA-F]"), "").uppercase().take(104)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_write_sheet_hex)) },
            supportingText = {
                val sizeHint = size?.let { " · fichier ${it}o" }.orEmpty()
                Text("$nBytes o$sizeHint")
            },
            isError = tooLong,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (size != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = replaceAll,
                    onCheckedChange = { replaceAll = it },
                    enabled = !busy,
                )
                Text(
                    text = stringResource(R.string.card_write_sheet_replace_all, size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!replaceAll && nBytes < size) {
                Text(
                    text = stringResource(R.string.card_write_sheet_partial_warn, nBytes, size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { onWrite(writeHex, replaceAll && size != null) },
            enabled = !busy && writeHex.length >= 2 && writeHex.length % 2 == 0 && !tooLong,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(
                busy = busy,
                text = if (replaceAll && size != null) {
                    stringResource(R.string.card_write_sheet_action_full)
                } else {
                    stringResource(R.string.card_write_sheet_action_partial, nBytes)
                },
            )
        }
        TextButton(
            onClick = onDismiss,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun UpgradeAesSheetContent(
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onUpgrade: (newAesKeyHex: String) -> Unit,
) {
    var keyHex by rememberSaveable {
        mutableStateOf(Hex.encode(AesConstants.FACTORY_KEY))
    }
    val canSubmit = !busy && keyHex.length == 32
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_upgrade_aes_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_upgrade_aes_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyHex,
            onValueChange = {
                keyHex = it.replace(Regex("[^0-9a-fA-F]"), "").uppercase().take(32)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_upgrade_aes_new_key)) },
            supportingText = { Text("${keyHex.length} / 32") },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(
            onClick = { keyHex = Hex.encode(AesConstants.FACTORY_KEY) },
            enabled = !busy,
        ) {
            Text(stringResource(R.string.card_key_factory))
        }
        if (statusLine != null && busy) {
            BusyLabel(busy = true, text = statusLine)
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onUpgrade(keyHex) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = busy, text = stringResource(R.string.card_upgrade_aes_action))
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun CreateAppSheetContent(
    suggestedAidHex: String,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (aidHex: String) -> Unit,
) {
    var aidHex by remember(suggestedAidHex) { mutableStateOf(suggestedAidHex) }
    LaunchedEffect(suggestedAidHex) {
        if (!busy) aidHex = suggestedAidHex
    }
    val justCreated = statusLine?.startsWith("CreateApplication OK") == true && !busy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_create_app_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_create_app_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = aidHex,
            onValueChange = {
                aidHex = it.replace(Regex("[^0-9a-fA-F]"), "").uppercase().take(6)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_lab_aid_hex)) },
            supportingText = {
                Text(stringResource(R.string.card_create_app_suggest, suggestedAidHex))
            },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (justCreated && statusLine != null) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onCreate(aidHex) },
            enabled = !busy && aidHex.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = busy, text = stringResource(R.string.card_lab_create_app_action))
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun ChangeKeyAesSheetContent(
    selectedAidHex: String?,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onChange: (keyNo: Int, newKeyHex: String) -> Unit,
) {
    var keyNoText by rememberSaveable { mutableStateOf("0") }
    var keyHex by rememberSaveable {
        mutableStateOf(Hex.encode(AesConstants.FACTORY_KEY))
    }
    val justOk = statusLine?.startsWith("ChangeKey AES OK") == true && !busy
    val clean = keyHex.replace(Regex("[^0-9a-fA-F]"), "")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_change_key_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.card_change_key_hint,
                prettyAid(selectedAidHex ?: "—"),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyNoText,
            onValueChange = { keyNoText = it.filter { c -> c.isDigit() }.take(2) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_change_key_slot)) },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = keyHex,
            onValueChange = {
                keyHex = it.replace(Regex("[^0-9a-fA-F]"), "").uppercase().take(32)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_upgrade_aes_new_key)) },
            supportingText = { Text("${clean.length} / 32") },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(
            onClick = { keyHex = Hex.encode(AesConstants.FACTORY_KEY) },
            enabled = !busy,
        ) {
            Text(stringResource(R.string.card_key_factory))
        }
        if (justOk && statusLine != null) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (statusLine != null && busy) {
            BusyLabel(busy = true, text = statusLine)
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                onChange(keyNoText.toIntOrNull()?.coerceIn(0, 13) ?: 0, clean)
            },
            enabled = !busy && clean.length == 32,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = busy, text = stringResource(R.string.card_change_key_action))
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun RestoreDumpSheetContent(
    dumpNames: List<String>,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    previewLines: List<String>,
    previewWarnings: List<String>,
    selectedFileName: String?,
    profileName: String?,
    materialSummary: String?,
    materialLines: List<String>,
    materialBlocking: Boolean,
    onDismiss: () -> Unit,
    onPreview: (fileName: String, formatFirst: Boolean) -> Unit,
    onExecute: (fileName: String, formatFirst: Boolean) -> Unit,
) {
    var selected by remember {
        mutableStateOf(selectedFileName ?: dumpNames.firstOrNull().orEmpty())
    }
    var formatFirst by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_restore_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_restore_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (profileName != null) {
                stringResource(R.string.card_dump_profile, profileName)
            } else {
                stringResource(R.string.card_dump_profile_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (profileName != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (dumpNames.isEmpty()) {
            Text(
                text = stringResource(R.string.dumps_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.card_restore_pick),
                style = MaterialTheme.typography.labelMedium,
            )
            dumpNames.take(12).forEach { name ->
                FilterChip(
                    selected = selected == name,
                    onClick = { selected = name },
                    label = {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    },
                    enabled = !busy,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = formatFirst,
                onCheckedChange = { formatFirst = it },
                enabled = !busy,
            )
            Text(
                text = stringResource(R.string.card_restore_format_first),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (materialSummary != null) {
            Text(
                text = materialSummary,
                style = MaterialTheme.typography.labelMedium,
                color = if (materialBlocking) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (materialLines.isNotEmpty()) {
            materialLines.take(24).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        line.startsWith("✗") -> MaterialTheme.colorScheme.error
                        line.startsWith("⚠") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        if (previewWarnings.isNotEmpty()) {
            previewWarnings.forEach { w ->
                Text(
                    text = "· $w",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (previewLines.isNotEmpty()) {
            Text(
                text = stringResource(R.string.card_restore_plan, previewLines.size),
                style = MaterialTheme.typography.labelMedium,
            )
            previewLines.take(40).forEach { line ->
                Text(
                    text = "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (previewLines.size > 40) {
                Text("… +${previewLines.size - 40}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (statusLine != null && busy) {
            BusyLabel(busy = true, text = statusLine)
        }
        if (statusLine != null && !busy && statusLine.startsWith("Restore")) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = { if (selected.isNotEmpty()) onPreview(selected, formatFirst) },
            enabled = !busy && selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.card_restore_dry_run))
        }
        Button(
            onClick = { if (selected.isNotEmpty()) onExecute(selected, formatFirst) },
            enabled = !busy && selected.isNotEmpty() && !materialBlocking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(
                busy = busy,
                text = if (materialBlocking) {
                    stringResource(R.string.card_restore_blocked)
                } else {
                    stringResource(R.string.card_restore_execute)
                },
            )
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun DumpSheetContent(
    coverage: DumpCoverageUi?,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onExport: (DumpMode) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(DumpMode.COMPLETE) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_dump_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_dump_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        coverage?.profileName?.let { name ->
            Text(
                text = stringResource(R.string.card_dump_profile, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } ?: Text(
            text = stringResource(R.string.card_dump_profile_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = mode == DumpMode.QUICK,
                onClick = { mode = DumpMode.QUICK },
                enabled = !busy,
            )
            Text(
                text = stringResource(R.string.card_dump_mode_quick),
                modifier = Modifier.clickable(enabled = !busy) { mode = DumpMode.QUICK },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = mode == DumpMode.COMPLETE,
                onClick = { mode = DumpMode.COMPLETE },
                enabled = !busy,
            )
            Text(
                text = stringResource(R.string.card_dump_mode_complete),
                modifier = Modifier.clickable(enabled = !busy) { mode = DumpMode.COMPLETE },
            )
        }
        coverage?.let { cov ->
            Text(
                text = cov.summary,
                style = MaterialTheme.typography.labelMedium,
            )
            cov.lines.take(40).forEach { line ->
                Text(
                    text = "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (cov.lines.size > 40) {
                Text("… +${cov.lines.size - 40}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (statusLine != null && busy) {
            BusyLabel(busy = true, text = statusLine)
        }
        if (statusLine != null && !busy && statusLine.startsWith("Dump OK")) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = onPreview,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.card_dump_dry_run))
        }
        Button(
            onClick = { onExport(mode) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(
                busy = busy,
                text = if (mode == DumpMode.COMPLETE) {
                    stringResource(R.string.card_dump_run_complete)
                } else {
                    stringResource(R.string.card_dump_run_quick)
                },
            )
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}

@Composable
fun CreateFileSheetContent(
    suggestedFileNo: Int,
    busy: Boolean,
    statusLine: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (fileNo: Int, size: Int) -> Unit,
) {
    var fileNoText by remember(suggestedFileNo) { mutableStateOf(suggestedFileNo.toString()) }
    var sizeText by rememberSaveable { mutableStateOf("16") }
    LaunchedEffect(suggestedFileNo) {
        if (!busy) fileNoText = suggestedFileNo.toString()
    }
    val justCreated = statusLine?.startsWith("CreateStdDataFile OK") == true && !busy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_create_file_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.card_create_file_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = fileNoText,
            onValueChange = { fileNoText = it.filter { c -> c.isDigit() }.take(2) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_lab_file_no)) },
            supportingText = {
                Text(stringResource(R.string.card_create_file_suggest, suggestedFileNo))
            },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = sizeText,
            onValueChange = { sizeText = it.filter { c -> c.isDigit() }.take(4) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.card_lab_file_size)) },
            singleLine = true,
            enabled = !busy,
        )
        if (justCreated && statusLine != null) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (statusLine != null && busy) {
            BusyLabel(busy = true, text = statusLine)
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                onCreate(fileNoText.toIntOrNull() ?: suggestedFileNo, sizeText.toIntOrNull() ?: 16)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = busy, text = stringResource(R.string.card_create_file_sheet_action))
        }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.card_auth_cancel))
        }
    }
}
