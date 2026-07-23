package com.cardrw.app.ui.screens.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.security.VaultBiometric
import com.cardrw.app.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<KeyVaultEntryMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<KeyVaultEntryMeta?>(null) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.vault_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // K3 — verrou optionnel
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.vault_lock_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.vault_lock_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ui.lockEnabled,
                            onCheckedChange = { viewModel.setLockEnabled(it) },
                            enabled = !ui.busy,
                        )
                    }
                    if (ui.lockEnabled) {
                        Text(
                            text = if (ui.unlocked) {
                                stringResource(R.string.vault_lock_status_open)
                            } else {
                                stringResource(R.string.vault_lock_status_closed)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.unlocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (!ui.unlocked) {
                                Button(
                                    onClick = {
                                        if (activity == null) {
                                            viewModel.clearMessages()
                                            return@Button
                                        }
                                        VaultBiometric.authenticate(
                                            activity = activity,
                                            title = context.getString(R.string.vault_unlock_title),
                                            subtitle = context.getString(R.string.vault_unlock_subtitle),
                                            onSuccess = { viewModel.onBiometricUnlocked() },
                                            onError = { msg ->
                                                // status via error field
                                            },
                                        )
                                    },
                                    enabled = !ui.busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Outlined.LockOpen, contentDescription = null)
                                    Text(
                                        text = "  " + stringResource(R.string.vault_unlock_action),
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.lockNow() },
                                    enabled = !ui.busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null)
                                    Text(
                                        text = "  " + stringResource(R.string.vault_lock_now),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ui.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            ui.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = {
                    viewModel.clearMessages()
                    showCreate = true
                },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.vault_add))
            }

            if (ui.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.vault_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(ui.entries, key = { it.id }) { entry ->
                        VaultEntryRow(
                            entry = entry,
                            enabled = !ui.busy,
                            onRename = { renameTarget = entry },
                            onDelete = { deleteTarget = entry },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateVaultDialog(
            defaultName = viewModel.nextDefaultName(),
            onDismiss = { showCreate = false },
            onConfirm = { name, hex ->
                viewModel.create(name, hex)
                showCreate = false
            },
        )
    }
    renameTarget?.let { entry ->
        RenameVaultDialog(
            currentName = entry.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(entry.id, newName)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = {
                Text(stringResource(R.string.vault_delete_message, entry.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.vault_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.card_auth_cancel))
                }
            },
        )
    }
}

@Composable
private fun VaultEntryRow(
    entry: KeyVaultEntryMeta,
    enabled: Boolean,
    onRename: () -> Unit,
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
                Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.vault_entry_masked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = onRename, enabled = enabled) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.vault_rename))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.vault_delete))
            }
        }
    }
}

@Composable
private fun CreateVaultDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, keyHex: String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var hex by remember { mutableStateOf("") }
    val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.vault_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text(stringResource(R.string.card_key_hex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text("${clean.length} / 32") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, clean) },
                enabled = clean.length == 32 && name.isNotBlank(),
            ) {
                Text(stringResource(R.string.vault_save))
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
private fun RenameVaultDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.vault_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.vault_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.card_auth_cancel))
            }
        },
    )
}
