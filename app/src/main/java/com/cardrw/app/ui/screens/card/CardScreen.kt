package com.cardrw.app.ui.screens.card

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.nfc.NfcReaderController
import com.cardrw.app.viewmodel.CardPhase
import com.cardrw.app.viewmodel.CardUiState
import com.cardrw.app.viewmodel.CardViewModel
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.model.VersionInfo
import com.cardrw.desfire.session.AuthSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    onBack: () -> Unit,
    viewModel: CardViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    DisposableEffect(activity) {
        val controller = NfcReaderController(activity) { tag ->
            if (NfcReaderController.isIsoDep(tag)) {
                viewModel.onTagDiscovered(tag)
            }
        }
        if (controller.isAvailable && controller.isEnabled) {
            controller.start()
        }
        onDispose { controller.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.card_title)) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (ui.phase) {
                CardPhase.Waiting -> WaitingCard()
                CardPhase.Reading -> {
                    Row(
                        modifier = Modifier.padding(vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.card_reading))
                    }
                }
                CardPhase.Ready -> {
                    val identity = ui.identity
                    if (identity != null) {
                        ReadyContent(ui = ui, identity = identity, viewModel = viewModel)
                    }
                }
                CardPhase.Error -> {
                    Text(
                        text = stringResource(R.string.card_error),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(text = ui.errorMessage.orEmpty())
                    OutlinedButton(
                        onClick = { viewModel.resetToWaiting() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.card_reread))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    ui: CardUiState,
    identity: CardIdentity,
    viewModel: CardViewModel,
) {
    val authenticated = ui.authSession?.authenticated == true
    val isPicc = ui.selectedAidHex.equals("000000", ignoreCase = true)

    ProfileSummaryCard(
        identity = identity,
        realUidHex = ui.realUidHex,
    )

    AuthSessionBadge(
        session = ui.authSession,
        selectedAid = ui.selectedAidHex,
    )

    // Erreurs / statut d’opération uniquement (pas de doublon type carte)
    if (ui.errorMessage != null) {
        Text(
            text = ui.errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (ui.statusLine != null) {
        Text(
            text = ui.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // GetCardUID si Random ID + auth et pas encore résolu
    if (authenticated && identity.uidKind == UidKind.RANDOM && ui.realUidHex == null) {
        TextButton(
            onClick = { viewModel.fetchRealUid() },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.card_get_card_uid))
        }
    }

    val version = identity.version
    if (version != null) {
        VersionTechnicalSection(version = version)
    }

    ApplicationsSection(
        applications = identity.applications,
        selectedAidHex = ui.selectedAidHex,
        friendlyName = viewModel::friendlyName,
        enabled = !ui.busy,
        onSelect = viewModel::selectApplication,
        onSelectPicc = { viewModel.selectApplication("000000") },
    )

    if (ui.selectedAidHex != null) {
        AuthPanel(
            keyNo = ui.keyNo,
            keyHex = ui.keyHex,
            busy = ui.busy,
            authenticated = authenticated,
            onKeyNo = viewModel::updateKeyNo,
            onKeyHex = viewModel::updateKeyHex,
            onFactoryKey = viewModel::setFactoryKey,
            onAuth = viewModel::authenticate,
        )

        // U1 : pull auto après select/auth — bouton = Actualiser (pas la porte d’entrée)
        OutlinedButton(
            onClick = { viewModel.explore() },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BusyLabel(busy = ui.busy, text = stringResource(R.string.card_action_refresh))
        }
        Text(
            text = if (isPicc) {
                stringResource(R.string.card_explore_hint_picc)
            } else {
                stringResource(R.string.card_explore_hint_app)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isPicc) {
            Text(
                text = stringResource(R.string.card_explore_hint_twophase),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    ui.explore?.let { explore ->
        ExplorerSection(explore = explore)
    }

    if (identity.rawNotes.isNotEmpty()) {
        NotesSection(notes = identity.rawNotes)
    }

    Spacer(modifier = Modifier.height(4.dp))
    OutlinedButton(
        onClick = { viewModel.resetToWaiting() },
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.card_reread))
    }
}

@Composable
private fun BusyLabel(busy: Boolean, text: String) {
    if (busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text)
        }
    } else {
        Text(text)
    }
}

@Composable
private fun AuthSessionBadge(session: AuthSession?, selectedAid: String?) {
    val shape = RoundedCornerShape(10.dp)
    val active = session?.authenticated == true
    val bg = if (active) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.card_session_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session?.authenticated == true) {
            Text(
                text = session.badgeLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.card_session_detail,
                    prettyAid(session.aidHex),
                    session.keyNumber,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Text(
                text = stringResource(R.string.card_session_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (selectedAid != null) {
                Text(
                    text = stringResource(R.string.card_session_selected_app, prettyAid(selectedAid)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuthPanel(
    keyNo: Int,
    keyHex: String,
    busy: Boolean,
    authenticated: Boolean,
    onKeyNo: (Int) -> Unit,
    onKeyHex: (String) -> Unit,
    onFactoryKey: () -> Unit,
    onAuth: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.card_auth_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.card_auth_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.card_key_no),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (n in 0..13) {
                    FilterChip(
                        selected = keyNo == n,
                        onClick = { onKeyNo(n) },
                        label = { Text("$n") },
                        enabled = !busy,
                    )
                }
            }
            // Valeur compacte (pas d’espaces) pour éviter troncature / curseur bizarre
            OutlinedTextField(
                value = keyHex,
                onValueChange = onKeyHex,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.card_key_hex)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.card_key_hex_support,
                            keyHex.length,
                            groupHex(keyHex).ifEmpty { "—" },
                        ),
                    )
                },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onFactoryKey,
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.card_key_factory))
                }
                TextButton(
                    onClick = {
                        val raw = clipboard.getText()?.text.orEmpty()
                        if (raw.isNotBlank()) onKeyHex(raw)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.card_key_paste))
                }
            }
            if (authenticated) {
                OutlinedButton(
                    onClick = onAuth,
                    enabled = !busy && keyHex.length == 32,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BusyLabel(busy = busy, text = stringResource(R.string.card_auth_again))
                }
            } else {
                Button(
                    onClick = onAuth,
                    enabled = !busy && keyHex.length == 32,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BusyLabel(busy = busy, text = stringResource(R.string.card_auth_action))
                }
            }
        }
    }
}

@Composable
private fun ExplorerSection(explore: ApplicationExploreResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.card_explorer_title, prettyAid(explore.aidHex)),
            style = MaterialTheme.typography.titleSmall,
        )
        explore.keySettings?.let { ks ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.card_key_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.card_key_settings_detail,
                            ks.settingsRaw.toString(16).uppercase().padStart(2, '0'),
                            ks.maxKeys,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    val bits = ks.bits
                    Text(
                        text = buildString {
                            append("master change: ${bits.allowMasterKeyChange} · ")
                            append("free list: ${bits.freeDirectoryListWithoutMaster} · ")
                            append("config: ${bits.configurationChangeable}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!bits.freeDirectoryListWithoutMaster) {
                        Text(
                            text = stringResource(R.string.card_key_settings_freelist_off),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (explore.structureFromCache) {
            Text(
                text = stringResource(R.string.card_explore_from_cache),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (explore.files.isEmpty()) {
            Text(
                text = stringResource(R.string.card_no_files),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (node in explore.files) {
                FileNodeCard(node)
            }
        }
        if (explore.notes.isNotEmpty()) {
            NotesSection(notes = explore.notes)
        }
    }
}

@Composable
private fun FileNodeCard(node: FileNode) {
    var expanded by rememberSaveable(node.fileNo) { mutableStateOf(false) }
    val s = node.settings
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.summaryLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "R=${s.accessRights.readLabel} · W=${s.accessRights.writeLabel} · " +
                            "RW=${s.accessRights.readWriteLabel} · Ch=${s.accessRights.changeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                val dataHex = node.dataHex
                val dataError = node.dataError
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    when {
                        dataHex != null -> {
                            Text(
                                text = stringResource(R.string.card_file_data),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = prettyHex(dataHex),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        dataError != null -> {
                            Text(
                                text = dataError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            Text(
                                text = stringResource(R.string.card_file_no_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSummaryCard(
    identity: CardIdentity,
    realUidHex: String?,
) {
    val version = identity.version
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.card_identity_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val typeLine = if (version != null) {
                stringResource(
                    R.string.card_profile_type_sw,
                    identity.typeLabel,
                    version.softwareVersionLabel,
                )
            } else {
                identity.typeLabel
            }
            Text(
                text = typeLine,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            val uidText = buildString {
                append(identity.displayUid)
                if (identity.uidKind == UidKind.RANDOM) {
                    append(" · ")
                    append("Random ID")
                }
            }
            ProfileField(
                label = stringResource(R.string.card_uid),
                value = uidText,
            )
            if (realUidHex != null) {
                ProfileField(
                    label = stringResource(R.string.card_uid_real),
                    value = realUidHex,
                )
            }
            ProfileField(
                label = stringResource(R.string.card_memory),
                value = identity.memoryLabel,
            )
            if (version != null) {
                ProfileField(
                    label = stringResource(R.string.card_production),
                    value = version.productionLabel,
                )
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun VersionTechnicalSection(version: VersionInfo) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rows = version.technicalDetailRows()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.card_raw_fields_hide)
                    } else {
                        stringResource(R.string.card_raw_fields_show)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.card_raw_fields),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (row in rows) {
                        TechnicalRow(label = row.first, value = row.second)
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(148.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ApplicationsSection(
    applications: List<Aid>,
    selectedAidHex: String?,
    friendlyName: (String) -> String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onSelectPicc: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.card_apps, applications.size),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.card_apps_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ApplicationRow(
            index = -1,
            prettyHex = "00 00 00",
            friendly = stringResource(R.string.card_picc_label),
            selected = selectedAidHex.equals("000000", ignoreCase = true),
            enabled = enabled,
            onClick = onSelectPicc,
        )
        if (applications.isEmpty()) {
            Text(
                text = stringResource(R.string.card_no_apps),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (index in applications.indices) {
                    val aid = applications[index]
                    val hex = aid.hex
                    ApplicationRow(
                        index = index,
                        prettyHex = prettyAid(hex),
                        friendly = friendlyName(hex),
                        selected = hex.equals(selectedAidHex, ignoreCase = true),
                        enabled = enabled,
                        onClick = { onSelect(hex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationRow(
    index: Int,
    prettyHex: String,
    friendly: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    }
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (selected) 5.dp else 0.dp)
                .background(accent),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (index < 0) "P" else "#$index",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = prettyHex,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (friendly != null) {
                    Text(
                        text = friendly,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Text(
                    text = stringResource(R.string.card_app_selected_badge),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Hex espacé par octets : AABBCC → AA BB CC */
private fun prettyAid(hex: String): String {
    val clean = hex.replace(" ", "").uppercase()
    return clean.chunked(2).joinToString(" ")
}

private fun prettyHex(hex: String): String {
    val clean = hex.replace(" ", "").uppercase()
    return clean.chunked(2).joinToString(" ")
}

/** Groupes de 4 hex pour saisie lisible (affichage seulement). */
private fun groupHex(compact: String): String {
    val clean = compact.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
    return clean.chunked(4).joinToString(" ")
}

@Composable
private fun NotesSection(notes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.card_notes),
            style = MaterialTheme.typography.labelLarge,
        )
        for (note in notes) {
            Text(
                text = "· $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Nfc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.card_waiting),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.card_place_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.card_keep_on_antenna),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
