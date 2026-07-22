package com.cardrw.app.ui.screens.card

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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.data.model.KeyVaultEntryMeta
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
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.util.Hex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    onBack: () -> Unit,
    viewModel: CardViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // NFC : reader mode dans MainActivity + NfcTagBus → CardViewModel (pas ici)

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
        when (ui.phase) {
            CardPhase.Waiting -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    WaitingCard()
                }
            }
            CardPhase.Reading -> {
                Row(
                    modifier = Modifier
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 24.dp),
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
                    ReadyMonitor(
                        ui = ui,
                        identity = identity,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
            CardPhase.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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

/**
 * Moniteur diagnostic (U2) : barre session sticky + scroll infos ;
 * saisie clé dans [AuthBottomSheet], pas dans le document principal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyMonitor(
    ui: CardUiState,
    identity: CardIdentity,
    viewModel: CardViewModel,
    modifier: Modifier = Modifier,
) {
    val authenticated = ui.authSession?.authenticated == true
    val isPicc = ui.selectedAidHex.equals("000000", ignoreCase = true)
    val vaultEntries by viewModel.vaultEntries.collectAsStateWithLifecycle()
    var showAuthSheet by rememberSaveable { mutableStateOf(false) }
    var closeSheetWhenAuthSettles by remember { mutableStateOf(false) }

    LaunchedEffect(showAuthSheet) {
        if (showAuthSheet) viewModel.reloadVault()
    }

    // Ferme la sheet après auth OK (explore auto U1 peut encore tourner : on ferme dès session OK + !busy auth phase)
    LaunchedEffect(ui.busy, ui.authSession?.authenticated, ui.errorMessage, closeSheetWhenAuthSettles) {
        if (!closeSheetWhenAuthSettles) return@LaunchedEffect
        if (ui.busy) return@LaunchedEffect
        closeSheetWhenAuthSettles = false
        if (ui.errorMessage == null && ui.authSession?.authenticated == true) {
            showAuthSheet = false
        }
    }

    // Si l’auth enchaîne explore (busy reste true), fermer dès que la session est authentifiée
    LaunchedEffect(ui.authSession?.authenticated, closeSheetWhenAuthSettles, showAuthSheet) {
        if (showAuthSheet &&
            closeSheetWhenAuthSettles &&
            ui.authSession?.authenticated == true &&
            ui.errorMessage == null
        ) {
            closeSheetWhenAuthSettles = false
            showAuthSheet = false
        }
    }

    Column(modifier = modifier) {
        // --- Zone sticky (hors scroll) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuthSessionBar(
                session = ui.authSession,
                selectedAid = ui.selectedAidHex,
                busy = ui.busy,
                onOpenAuth = { showAuthSheet = true },
            )
            if (ui.errorMessage != null && !showAuthSheet) {
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
        }

        // --- Moniteur scrollable (données seulement) ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileSummaryCard(
                identity = identity,
                realUidHex = ui.realUidHex,
            )

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
    }

    if (showAuthSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!ui.busy) showAuthSheet = false
            },
            sheetState = sheetState,
        ) {
            AuthSheetContent(
                initialKeyNo = ui.keyNo,
                initialKeyHex = ui.keyHex,
                vaultEntries = vaultEntries,
                defaultSaveName = viewModel.nextVaultDefaultName(),
                busy = ui.busy,
                authenticated = authenticated,
                selectedAid = ui.selectedAidHex,
                errorMessage = ui.errorMessage,
                onDismiss = {
                    if (!ui.busy) showAuthSheet = false
                },
                onAuthenticate = { request ->
                    closeSheetWhenAuthSettles = true
                    viewModel.authenticate(
                        keyNo = request.keyNo,
                        keyHex = request.keyHex,
                        vaultEntryId = request.vaultEntryId,
                        saveAsVaultName = request.saveAsVaultName,
                    )
                },
            )
        }
    }
}

/** Paramètres d’auth depuis la sheet (hex ou coffre). */
private data class AuthMaterialRequest(
    val keyNo: Int,
    val keyHex: String? = null,
    val vaultEntryId: String? = null,
    val saveAsVaultName: String? = null,
)

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

/** Barre session sticky : état + porte vers la sheet auth (U2). */
@Composable
private fun AuthSessionBar(
    session: AuthSession?,
    selectedAid: String?,
    busy: Boolean,
    onOpenAuth: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val active = session?.authenticated == true
    val bg = if (active) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val canAuth = selectedAid != null && !busy

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .clickable(enabled = canAuth, onClick = onOpenAuth)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
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
                } else {
                    Text(
                        text = stringResource(R.string.card_session_select_app_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selectedAid != null) {
            if (active) {
                OutlinedButton(
                    onClick = onOpenAuth,
                    enabled = canAuth,
                ) {
                    Text(stringResource(R.string.card_auth_change))
                }
            } else {
                Button(
                    onClick = onOpenAuth,
                    enabled = canAuth,
                ) {
                    Text(stringResource(R.string.card_auth_action))
                }
            }
        }
    }
}

/**
 * Formulaire auth en bottom sheet (U2 + K2).
 * Matériau : coffre nommé **ou** hex (+ option enregistrer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthSheetContent(
    initialKeyNo: Int,
    initialKeyHex: String,
    vaultEntries: List<KeyVaultEntryMeta>,
    defaultSaveName: String,
    busy: Boolean,
    authenticated: Boolean,
    selectedAid: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onAuthenticate: (AuthMaterialRequest) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var draftKeyNo by remember { mutableIntStateOf(initialKeyNo.coerceIn(0, 13)) }
    var draftKeyHex by remember {
        mutableStateOf(initialKeyHex.replace(Regex("[^0-9a-fA-F]"), "").uppercase())
    }
    var useVault by remember { mutableStateOf(vaultEntries.isNotEmpty()) }
    var selectedVaultId by remember {
        mutableStateOf(vaultEntries.firstOrNull()?.id)
    }
    var vaultMenuExpanded by remember { mutableStateOf(false) }
    var saveToVault by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf(defaultSaveName) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vaultEntries) {
        if (vaultEntries.isEmpty()) {
            useVault = false
            selectedVaultId = null
        } else if (selectedVaultId == null || vaultEntries.none { it.id == selectedVaultId }) {
            selectedVaultId = vaultEntries.first().id
        }
    }

    fun applyKeyHex(raw: String) {
        val clean = raw.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
        when {
            clean.length <= 32 -> {
                draftKeyHex = clean
                localError = null
            }
            else ->
                localError = "Colle uniquement la clé AES (32 caractères hex), pas un journal APDU."
        }
    }

    val selectedVaultName = vaultEntries.find { it.id == selectedVaultId }?.displayName
    val canSubmit = !busy && selectedAid != null && when {
        useVault -> selectedVaultId != null
        else -> draftKeyHex.length == 32
    }

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
            text = stringResource(R.string.card_auth_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (selectedAid != null) {
            Text(
                text = stringResource(R.string.card_session_selected_app, prettyAid(selectedAid)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.card_auth_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val displayError = localError ?: errorMessage
        if (displayError != null) {
            Text(
                text = displayError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

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
                    selected = draftKeyNo == n,
                    onClick = { draftKeyNo = n },
                    label = { Text("$n") },
                    enabled = !busy,
                )
            }
        }

        Text(
            text = stringResource(R.string.card_auth_material),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = useVault,
                onClick = { useVault = true },
                enabled = !busy && vaultEntries.isNotEmpty(),
            )
            Text(
                text = stringResource(R.string.card_auth_from_vault),
                modifier = Modifier
                    .clickable(enabled = !busy && vaultEntries.isNotEmpty()) { useVault = true }
                    .padding(end = 12.dp),
            )
            RadioButton(
                selected = !useVault,
                onClick = { useVault = false },
                enabled = !busy,
            )
            Text(
                text = stringResource(R.string.card_auth_from_hex),
                modifier = Modifier.clickable(enabled = !busy) { useVault = false },
            )
        }

        if (useVault && vaultEntries.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = vaultMenuExpanded,
                onExpandedChange = { if (!busy) vaultMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedVaultName.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.vault_name)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vaultMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = !busy)
                        .fillMaxWidth(),
                    enabled = !busy,
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
        } else {
            if (vaultEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.card_auth_vault_empty_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draftKeyHex,
                onValueChange = { applyKeyHex(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.card_key_hex)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.card_key_hex_support,
                            draftKeyHex.length,
                            groupHex(draftKeyHex).ifEmpty { "—" },
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
                    onClick = {
                        draftKeyHex = Hex.encode(AesConstants.FACTORY_KEY)
                        localError = null
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.card_key_factory))
                }
                TextButton(
                    onClick = {
                        val raw = clipboard.getText()?.text.orEmpty()
                        if (raw.isNotBlank()) applyKeyHex(raw)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.card_key_paste))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = saveToVault,
                    onCheckedChange = { saveToVault = it },
                    enabled = !busy,
                )
                Text(stringResource(R.string.card_auth_save_vault))
            }
            if (saveToVault) {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.vault_name)) },
                    singleLine = true,
                    enabled = !busy,
                )
            }
        }

        fun submit() {
            if (useVault) {
                val id = selectedVaultId ?: return
                onAuthenticate(
                    AuthMaterialRequest(keyNo = draftKeyNo, vaultEntryId = id),
                )
            } else {
                if (draftKeyHex.length != 32) return
                onAuthenticate(
                    AuthMaterialRequest(
                        keyNo = draftKeyNo,
                        keyHex = draftKeyHex,
                        saveAsVaultName = saveName.trim().takeIf { saveToVault && it.isNotEmpty() },
                    ),
                )
            }
        }

        if (authenticated) {
            OutlinedButton(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BusyLabel(busy = busy, text = stringResource(R.string.card_auth_again))
            }
        } else {
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BusyLabel(busy = busy, text = stringResource(R.string.card_auth_action))
            }
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
