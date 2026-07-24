package com.cardrw.app.ui.screens.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.viewmodel.ProfilesViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    onOpenProfile: (profileId: String) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<KeyProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
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
                text = stringResource(R.string.profiles_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                Text(stringResource(R.string.profiles_add))
            }

            if (ui.profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(ui.profiles, key = { it.id }) { profile ->
                        ProfileListRow(
                            profile = profile,
                            enabled = !ui.busy,
                            onOpen = { onOpenProfile(profile.id) },
                            onDelete = { deleteTarget = profile },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProfileDialog(
            defaultName = viewModel.nextDefaultName(),
            onDismiss = { showCreate = false },
            onConfirm = { name, notes ->
                viewModel.create(name, notes)
                showCreate = false
            },
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.profiles_delete_title)) },
            text = {
                Text(stringResource(R.string.profiles_delete_message, profile.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(profile.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.profiles_delete_confirm))
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
private fun ProfileListRow(
    profile: KeyProfile,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val whenStr = DateFormat.getDateInstance(DateFormat.SHORT)
        .format(Date(profile.updatedAt))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.profiles_list_meta,
                        profile.bindings.size,
                        whenStr,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profile.allowFactoryFallback) {
                    Text(
                        text = stringResource(R.string.profiles_fallback_on_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.profiles_delete),
                )
            }
        }
    }
}

@Composable
private fun CreateProfileDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, notes: String?) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.profiles_notes)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, notes.ifBlank { null }) },
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
