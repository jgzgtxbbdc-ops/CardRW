package com.cardrw.app.ui.screens.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.AlertDialog
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
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.model.AuthBarrier
import com.cardrw.desfire.model.AuthIntent
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.model.AuthKeyPlanner
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.model.VersionInfo
import com.cardrw.desfire.session.AuthSession
import com.cardrw.desfire.util.Hex
import kotlinx.coroutines.delay

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
    val vaultEntries by viewModel.vaultEntries.collectAsStateWithLifecycle()
    var showAuthSheet by rememberSaveable { mutableStateOf(false) }
    var authPlan by remember { mutableStateOf<AuthKeyPlan?>(null) }
    var neverMessage by remember { mutableStateOf<String?>(null) }
    var closeSheetWhenAuthSettles by remember { mutableStateOf(false) }
    /** FileNo en cours d’édition (contenu toujours relu depuis ui.explore). */
    var writeFileNo by remember { mutableStateOf<Int?>(null) }
    var showCreateApp by remember { mutableStateOf(false) }
    var showCreateFile by remember { mutableStateOf(false) }
    var showUpgradeAes by remember { mutableStateOf(false) }
    var deleteAppAid by remember { mutableStateOf<String?>(null) }
    var deleteFileNo by remember { mutableStateOf<Int?>(null) }
    // U5 : conserver / restaurer la position de scroll après auth / explore
    val monitorScroll = rememberScrollState()
    var savedScrollPx by rememberSaveable { mutableIntStateOf(0) }
    val structureEnabled = authenticated &&
        ui.authSession?.smLevel != SecureMessagingLevel.DES_LEGACY &&
        ui.authSession?.smLevel != SecureMessagingLevel.NONE
    val desToAesEnabled = authenticated &&
        ui.authSession?.smLevel == SecureMessagingLevel.DES_LEGACY &&
        ui.selectedAidHex.equals("000000", ignoreCase = true)
    val liveWriteNode = writeFileNo?.let { no ->
        ui.explore?.files?.find { it.fileNo == no }
    }

    LaunchedEffect(ui.busy) {
        if (ui.busy) {
            savedScrollPx = monitorScroll.value
        }
    }
    LaunchedEffect(ui.busy, ui.authSuccessFlash, ui.statusLine) {
        if (!ui.busy && savedScrollPx > 0) {
            delay(48)
            val target = savedScrollPx.coerceAtMost(monitorScroll.maxValue)
            if (kotlin.math.abs(monitorScroll.value - target) > 8) {
                monitorScroll.scrollTo(target)
            }
        }
    }

    fun openAuthSheet(plan: AuthKeyPlan, forceGenericIfNone: Boolean = false) {
        neverMessage = null
        when (plan.barrier) {
            AuthBarrier.NEVER -> {
                neverMessage = plan.detailMessage ?: plan.titleHint
            }
            AuthBarrier.NONE -> {
                if (forceGenericIfNone) {
                    val sessionKey = ui.authSession?.takeIf { it.authenticated }?.keyNumber
                    authPlan = AuthKeyPlanner.plan(AuthIntent.Generic, sessionKey)
                    showAuthSheet = true
                }
            }
            AuthBarrier.NEEDS_KEY -> {
                authPlan = plan
                showAuthSheet = true
            }
        }
    }

    LaunchedEffect(showAuthSheet) {
        if (showAuthSheet) viewModel.reloadVault()
    }

    // Échec auto-auth (double-tap) → sheet standard, sans message d’erreur
    LaunchedEffect(ui.openAuthSheetNonce) {
        if (ui.openAuthSheetNonce > 0L) {
            openAuthSheet(viewModel.suggestAuthPlan(), forceGenericIfNone = true)
        }
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
                onOpenAuth = {
                    // Barre session : plan contextuel, sinon générique (changer de clé)
                    openAuthSheet(viewModel.suggestAuthPlan(), forceGenericIfNone = true)
                },
            )
            AuthSuccessFlash(
                visible = ui.authSuccessFlash,
                message = ui.authSuccessMessage
                    ?: stringResource(R.string.card_auth_ok),
            )
            if (neverMessage != null && !showAuthSheet) {
                Text(
                    text = neverMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                .verticalScroll(monitorScroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileSummaryCard(
                identity = identity,
                realUidHex = ui.realUidHex,
            )

            // U5 : GetCardUID auto après auth ; bouton secours si Random encore sans UID réel
            if (authenticated && identity.uidKind == UidKind.RANDOM && ui.realUidHex == null) {
                Text(
                    text = stringResource(R.string.card_get_card_uid_auto_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            // U4 — arbre PICC → apps → fichiers (+ créer / écrire / supprimer)
            DesfireCardTree(
                applications = identity.applications,
                selectedAidHex = ui.selectedAidHex,
                exploreByAid = ui.exploreByAid,
                busy = ui.busy,
                sessionKey = ui.authSession?.takeIf { it.authenticated }?.keyNumber,
                structureEnabled = structureEnabled,
                desToAesEnabled = desToAesEnabled,
                friendlyName = viewModel::friendlyName,
                onSelectPicc = {
                    viewModel.selectApplication("000000", tryDefaultAuth = false)
                },
                onDoubleSelectPicc = {
                    viewModel.selectApplication("000000", tryDefaultAuth = true)
                },
                onSelectApp = { hex ->
                    viewModel.selectApplication(hex, tryDefaultAuth = false)
                },
                onDoubleSelectApp = { hex ->
                    viewModel.selectApplication(hex, tryDefaultAuth = true)
                },
                onRefresh = { viewModel.explore() },
                onAuthForFile = { node ->
                    if (!viewModel.tryAuthFileWithRemembered(node)) {
                        openAuthSheet(viewModel.authPlanForFile(node), forceGenericIfNone = false)
                    }
                },
                onWriteFile = { node -> writeFileNo = node.fileNo },
                onAddApplication = { showCreateApp = true },
                onUpgradePiccToAes = { showUpgradeAes = true },
                onDeleteApplication = { aid -> deleteAppAid = aid },
                onAddFile = { showCreateFile = true },
                onDeleteFile = { node -> deleteFileNo = node.fileNo },
            )

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
        val plan = authPlan ?: AuthKeyPlanner.plan(
            AuthIntent.Generic,
            ui.authSession?.takeIf { it.authenticated }?.keyNumber,
        )
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!ui.busy) {
                    showAuthSheet = false
                    authPlan = null
                }
            },
            sheetState = sheetState,
        ) {
            AuthSheetContent(
                authPlan = plan,
                initialKeyHex = ui.keyHex,
                vaultEntries = vaultEntries,
                defaultSaveName = viewModel.nextVaultDefaultName(),
                busy = ui.busy,
                authenticated = authenticated,
                selectedAid = ui.selectedAidHex,
                errorMessage = ui.errorMessage,
                onDismiss = {
                    if (!ui.busy) {
                        showAuthSheet = false
                        authPlan = null
                    }
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

    // Auth KO + option coffre cochée → proposer d’enregistrer quand même (mauvais slot ?)
    ui.pendingVaultSave?.let { offer ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingVaultSave() },
            title = { Text(stringResource(R.string.card_vault_save_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.card_vault_save_failed_message,
                        offer.displayName,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmPendingVaultSave() }) {
                    Text(stringResource(R.string.card_vault_save_failed_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingVaultSave() }) {
                    Text(stringResource(R.string.card_vault_save_failed_dismiss))
                }
            },
        )
    }

    // Write — contenu toujours relu depuis explore (liveWriteNode)
    if (writeFileNo != null) {
        val node = liveWriteNode
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) writeFileNo = null },
            sheetState = sheetState,
        ) {
            if (node != null) {
                WriteFileSheetContent(
                    node = node,
                    busy = ui.busy,
                    statusLine = ui.statusLine,
                    errorMessage = ui.errorMessage,
                    onDismiss = { if (!ui.busy) writeFileNo = null },
                    onWrite = { hex, pad ->
                        viewModel.writeFileData(node.fileNo, hex, pad)
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.card_write_sheet_missing),
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }

    if (showCreateApp) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) showCreateApp = false },
            sheetState = sheetState,
        ) {
            CreateAppSheetContent(
                busy = ui.busy,
                errorMessage = ui.errorMessage,
                onDismiss = { if (!ui.busy) showCreateApp = false },
                onCreate = { aid -> viewModel.createApplicationLab(aid) },
            )
        }
    }

    if (showUpgradeAes) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) showUpgradeAes = false },
            sheetState = sheetState,
        ) {
            UpgradeAesSheetContent(
                busy = ui.busy,
                statusLine = ui.statusLine,
                errorMessage = ui.errorMessage,
                onDismiss = { if (!ui.busy) showUpgradeAes = false },
                onUpgrade = { hex -> viewModel.changeKeyDesToAesLab(hex) },
            )
        }
    }

    if (showCreateFile) {
        val existing = ui.explore?.files?.map { it.fileNo }?.toSet().orEmpty()
        val nextNo = (0..31).firstOrNull { it !in existing } ?: 0
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) showCreateFile = false },
            sheetState = sheetState,
        ) {
            CreateFileSheetContent(
                suggestedFileNo = nextNo,
                busy = ui.busy,
                errorMessage = ui.errorMessage,
                onDismiss = { if (!ui.busy) showCreateFile = false },
                onCreate = { no, size -> viewModel.createStdFileLab(no, size) },
            )
        }
    }

    deleteAppAid?.let { aid ->
        AlertDialog(
            onDismissRequest = { if (!ui.busy) deleteAppAid = null },
            title = { Text(stringResource(R.string.card_delete_app_title)) },
            text = {
                Text(stringResource(R.string.card_delete_app_message, prettyAid(aid)))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteApplicationLab(aid)
                        deleteAppAid = null
                    },
                    enabled = !ui.busy,
                ) {
                    Text(stringResource(R.string.card_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAppAid = null }, enabled = !ui.busy) {
                    Text(stringResource(R.string.card_auth_cancel))
                }
            },
        )
    }

    deleteFileNo?.let { no ->
        AlertDialog(
            onDismissRequest = { if (!ui.busy) deleteFileNo = null },
            title = { Text(stringResource(R.string.card_delete_file_title)) },
            text = { Text(stringResource(R.string.card_delete_file_message, no)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFileLab(no)
                        deleteFileNo = null
                    },
                    enabled = !ui.busy,
                ) {
                    Text(stringResource(R.string.card_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFileNo = null }, enabled = !ui.busy) {
                    Text(stringResource(R.string.card_auth_cancel))
                }
            },
        )
    }

    // Fermer sheets create après succès (statusLine stable)
    LaunchedEffect(ui.busy, ui.statusLine, ui.errorMessage) {
        if (!ui.busy && ui.errorMessage == null) {
            when {
                ui.statusLine?.startsWith("CreateApplication OK") == true -> showCreateApp = false
                ui.statusLine?.startsWith("CreateStdDataFile OK") == true -> showCreateFile = false
            }
        }
    }

    // Upgrade AES : fermer dès que la session n’est plus DES (succès ou re-auth AES).
    // Ne pas s’appuyer sur statusLine — runExplore l’écrase immédiatement après la bascule.
    LaunchedEffect(desToAesEnabled, showUpgradeAes) {
        if (showUpgradeAes && !desToAesEnabled) {
            showUpgradeAes = false
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
                val isPiccSession = session.aidHex.equals("000000", ignoreCase = true)
                Text(
                    text = if (isPiccSession) {
                        stringResource(R.string.card_session_detail_picc, session.keyNumber)
                    } else {
                        stringResource(
                            R.string.card_session_detail,
                            prettyAid(session.aidHex),
                            session.keyNumber,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = stringResource(R.string.card_session_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (selectedAid != null) {
                    val isPicc = selectedAid.equals("000000", ignoreCase = true)
                    Text(
                        text = if (isPicc) {
                            stringResource(R.string.card_session_selected_picc)
                        } else {
                            stringResource(R.string.card_session_selected_app, prettyAid(selectedAid))
                        },
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
 * Formulaire auth en bottom sheet (U2 + K2 + U3).
 * Slot carte restreint aux [AuthKeyPlan.candidates] quand connus.
 * Matériau : coffre nommé **ou** hex (+ option enregistrer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthSheetContent(
    authPlan: AuthKeyPlan,
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
    val preferred = (authPlan.preferKeyNo ?: 0).coerceIn(0, 13)
    var draftKeyNo by remember(authPlan) { mutableIntStateOf(preferred) }
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
    var showAllKeys by remember(authPlan) {
        mutableStateOf(authPlan.candidates.isEmpty())
    }

    val chips: List<Int> = when {
        showAllKeys -> (0..13).toList()
        authPlan.candidates.isNotEmpty() -> authPlan.candidates.map { it.keyNo }.distinct()
        else -> (0..13).toList()
    }

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
            text = authPlan.titleHint,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (selectedAid != null) {
            val isPicc = selectedAid.equals("000000", ignoreCase = true)
            Text(
                text = if (isPicc) {
                    stringResource(R.string.card_session_selected_picc)
                } else {
                    stringResource(R.string.card_session_selected_app, prettyAid(selectedAid))
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        authPlan.detailMessage?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
            for (n in chips) {
                val role = authPlan.candidates.find { it.keyNo == n }?.roleLabel
                FilterChip(
                    selected = draftKeyNo == n,
                    onClick = { draftKeyNo = n },
                    label = {
                        Text(if (role != null && chips.size <= 4) "$n · $role" else "$n")
                    },
                    enabled = !busy,
                )
            }
        }
        if (authPlan.candidates.isNotEmpty()) {
            TextButton(
                onClick = { showAllKeys = !showAllKeys },
                enabled = !busy,
            ) {
                Text(
                    if (showAllKeys) {
                        stringResource(R.string.card_auth_keys_candidates_only)
                    } else {
                        stringResource(R.string.card_auth_keys_show_all)
                    },
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

/** Sheet d’écriture — [node] doit être le nœud **live** (recomposition après explore). */
@Composable
private fun WriteFileSheetContent(
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
    // Met à jour le champ éditable quand le contenu carte change (après Write OK)
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
private fun UpgradeAesSheetContent(
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
            supportingText = {
                Text("${keyHex.length} / 32")
            },
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
private fun CreateAppSheetContent(
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (aidHex: String) -> Unit,
) {
    var aidHex by rememberSaveable { mutableStateOf("F00102") }
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
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
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
private fun CreateFileSheetContent(
    suggestedFileNo: Int,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (fileNo: Int, size: Int) -> Unit,
) {
    var fileNoText by rememberSaveable { mutableStateOf(suggestedFileNo.toString()) }
    var sizeText by rememberSaveable { mutableStateOf("16") }
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
private fun AuthSuccessFlash(visible: Boolean, message: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
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
