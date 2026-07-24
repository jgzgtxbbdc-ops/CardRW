package com.cardrw.app.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.app.data.repository.KeyProfileRules
import com.cardrw.app.viewmodel.ProfileDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    onBack: () -> Unit,
    viewModel: ProfileDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val profile = ui.profile
    var showAddBinding by remember { mutableStateOf(false) }
    var editBinding by remember { mutableStateOf<KeyBinding?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(profile?.displayName ?: stringResource(R.string.profiles_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (profile != null) {
                        IconButton(
                            onClick = { showRename = true },
                            enabled = !ui.busy,
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.profiles_rename),
                            )
                        }
                        IconButton(
                            onClick = { showDelete = true },
                            enabled = !ui.busy,
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.profiles_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (profile == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.profiles_not_found),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.profiles_detail_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ui.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            ui.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            // Options run
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.profiles_fallback_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.profiles_fallback_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = profile.allowFactoryFallback,
                        onCheckedChange = { viewModel.setAllowFactoryFallback(it) },
                        enabled = !ui.busy,
                    )
                }
            }

            profile.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.profiles_bindings_header, profile.bindings.size),
                style = MaterialTheme.typography.titleMedium,
            )

            if (profile.bindings.isEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_bindings_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Group by scope for readability
                val grouped = profile.bindings
                    .sortedWith(
                        compareBy<KeyBinding> {
                            when (it.scope) {
                                is BindingScope.Picc -> "0"
                                is BindingScope.Application -> "1${it.scope.aidHex}"
                            }
                        }.thenBy { it.keyNo },
                    )
                    .groupBy { KeyProfileRules.formatScope(it.scope) }

                for ((scopeLabel, bindings) in grouped) {
                    Text(
                        text = if (scopeLabel == "PICC") {
                            stringResource(R.string.profiles_scope_picc)
                        } else {
                            stringResource(R.string.profiles_scope_app, scopeLabel)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    for (binding in bindings) {
                        val vaultId = (binding.materialRef as? MaterialRef.VaultEntry)?.vaultId
                        val broken = vaultId != null && vaultId in ui.brokenVaultIds
                        BindingRow(
                            binding = binding,
                            materialLabel = materialLabel(
                                binding = binding,
                                vaultName = vaultId?.let { viewModel.vaultDisplayName(it) },
                                broken = broken,
                            ),
                            broken = broken,
                            enabled = !ui.busy,
                            onEdit = { editBinding = binding },
                            onDelete = {
                                viewModel.removeBinding(
                                    KeyProfileRules.bindingSlotKey(binding),
                                )
                            },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.clearMessages()
                    showAddBinding = true
                },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.profiles_add_binding))
            }

            Text(
                text = stringResource(R.string.profiles_vocab_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAddBinding) {
        BindingEditorDialog(
            title = stringResource(R.string.profiles_add_binding),
            initial = null,
            vaultEntries = ui.vaultEntries,
            onDismiss = { showAddBinding = false },
            onConfirm = { scopePicc, aid, keyNo, vaultId, role, required ->
                viewModel.upsertBinding(scopePicc, aid, keyNo, vaultId, role, required)
                showAddBinding = false
            },
        )
    }

    editBinding?.let { binding ->
        BindingEditorDialog(
            title = stringResource(R.string.profiles_edit_binding),
            initial = binding,
            vaultEntries = ui.vaultEntries,
            onDismiss = { editBinding = null },
            onConfirm = { scopePicc, aid, keyNo, vaultId, role, required ->
                viewModel.upsertBinding(scopePicc, aid, keyNo, vaultId, role, required)
                editBinding = null
            },
        )
    }

    if (showRename && profile != null) {
        RenameProfileDialog(
            currentName = profile.displayName,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                viewModel.rename(newName)
                showRename = false
            },
        )
    }

    if (showDelete && profile != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.profiles_delete_title)) },
            text = {
                Text(stringResource(R.string.profiles_delete_message, profile.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(onDeleted = onBack)
                        showDelete = false
                    },
                ) {
                    Text(stringResource(R.string.profiles_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.card_auth_cancel))
                }
            },
        )
    }
}

@Composable
private fun materialLabel(
    binding: KeyBinding,
    vaultName: String?,
    broken: Boolean,
): String {
    return when (val m = binding.materialRef) {
        is MaterialRef.FactoryZero -> stringResource(R.string.profiles_material_factory)
        is MaterialRef.VaultEntry -> when {
            broken -> stringResource(R.string.profiles_material_missing, vaultName ?: m.vaultId.take(8))
            vaultName != null -> stringResource(R.string.profiles_material_vault, vaultName)
            else -> stringResource(R.string.profiles_material_missing, m.vaultId.take(8))
        }
    }
}

@Composable
private fun BindingRow(
    binding: KeyBinding,
    materialLabel: String,
    broken: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val role = binding.roleHint?.let { " · $it" }.orEmpty()
                val req = if (binding.required) {
                    " · " + stringResource(R.string.profiles_required_short)
                } else {
                    ""
                }
                Text(
                    text = "k${binding.keyNo}$role$req",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (broken) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    Text(
                        text = materialLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (broken) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.profiles_slot_label, binding.keyNo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.profiles_edit_binding),
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.profiles_delete_binding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BindingEditorDialog(
    title: String,
    initial: KeyBinding?,
    vaultEntries: List<KeyVaultEntryMeta>,
    onDismiss: () -> Unit,
    onConfirm: (
        scopePicc: Boolean,
        aidHex: String?,
        keyNo: Int,
        materialVaultId: String?,
        roleHint: String?,
        required: Boolean,
    ) -> Unit,
) {
    var scopePicc by remember {
        mutableStateOf(initial?.scope is BindingScope.Picc || initial == null)
    }
    var aidHex by remember {
        mutableStateOf(
            (initial?.scope as? BindingScope.Application)?.aidHex.orEmpty(),
        )
    }
    var keyNoText by remember {
        mutableStateOf((initial?.keyNo ?: 0).toString())
    }
    var useFactory by remember {
        mutableStateOf(
            initial == null || initial.materialRef is MaterialRef.FactoryZero,
        )
    }
    var selectedVaultId by remember {
        mutableStateOf(
            (initial?.materialRef as? MaterialRef.VaultEntry)?.vaultId,
        )
    }
    var roleHint by remember { mutableStateOf(initial?.roleHint.orEmpty()) }
    var required by remember { mutableStateOf(initial?.required ?: false) }
    var vaultMenuExpanded by remember { mutableStateOf(false) }
    var scopeMenuExpanded by remember { mutableStateOf(false) }

    val keyNo = keyNoText.toIntOrNull()
    val keyNoOk = keyNo != null && KeyProfileRules.isValidKeyNo(keyNo)
    val aidOk = scopePicc || runCatching {
        KeyProfileRules.normalizeAidHex(aidHex)
    }.isSuccess
    val materialOk = useFactory || (!selectedVaultId.isNullOrBlank() &&
        vaultEntries.any { it.id == selectedVaultId })
    val canSave = keyNoOk && aidOk && materialOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.profiles_binding_editor_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Scope
                ExposedDropdownMenuBox(
                    expanded = scopeMenuExpanded,
                    onExpandedChange = { scopeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = if (scopePicc) {
                            stringResource(R.string.profiles_scope_picc)
                        } else {
                            stringResource(R.string.profiles_scope_app_short)
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.profiles_scope_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = scopeMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_scope_picc)) },
                            onClick = {
                                scopePicc = true
                                scopeMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_scope_app_short)) },
                            onClick = {
                                scopePicc = false
                                scopeMenuExpanded = false
                            },
                        )
                    }
                }

                if (!scopePicc) {
                    OutlinedTextField(
                        value = aidHex,
                        onValueChange = { aidHex = it },
                        label = { Text(stringResource(R.string.profiles_aid_hex)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        supportingText = {
                            Text(stringResource(R.string.profiles_aid_hint))
                        },
                    )
                }

                OutlinedTextField(
                    value = keyNoText,
                    onValueChange = { keyNoText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.profiles_key_no)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(stringResource(R.string.profiles_key_no_hint))
                    },
                )

                OutlinedTextField(
                    value = roleHint,
                    onValueChange = { roleHint = it },
                    label = { Text(stringResource(R.string.profiles_role_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.profiles_role_hint_support))
                    },
                )

                // Material
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useFactory,
                        onCheckedChange = {
                            useFactory = it
                            if (it) selectedVaultId = null
                        },
                    )
                    Text(stringResource(R.string.profiles_material_factory))
                }

                if (!useFactory) {
                    if (vaultEntries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.profiles_vault_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val selectedName = vaultEntries
                            .find { it.id == selectedVaultId }
                            ?.displayName
                            ?: stringResource(R.string.profiles_pick_vault)
                        ExposedDropdownMenuBox(
                            expanded = vaultMenuExpanded,
                            onExpandedChange = { vaultMenuExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.profiles_material_vault_label)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = vaultMenuExpanded,
                                    )
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = vaultMenuExpanded,
                                onDismissRequest = { vaultMenuExpanded = false },
                            ) {
                                vaultEntries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry.displayName) },
                                        onClick = {
                                            selectedVaultId = entry.id
                                            vaultMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = required,
                        onCheckedChange = { required = it },
                    )
                    Text(stringResource(R.string.profiles_required))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        scopePicc,
                        if (scopePicc) null else aidHex,
                        keyNo ?: 0,
                        if (useFactory) null else selectedVaultId,
                        roleHint.ifBlank { null },
                        required,
                    )
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.profiles_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.card_auth_cancel))
            }
        },
    )
}

@Composable
private fun RenameProfileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profiles_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.profiles_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.card_auth_cancel))
            }
        },
    )
}
