package com.cardrw.app.ui.screens.card

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardrw.app.R
import com.cardrw.app.ui.screens.journal.JournalPanel
import com.cardrw.app.viewmodel.CardPhase
import com.cardrw.app.viewmodel.CardUiState
import com.cardrw.app.viewmodel.CardViewModel
import com.cardrw.app.viewmodel.UiSettingsViewModel
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.dump.DumpRestorePlanner
import com.cardrw.desfire.model.AuthBarrier
import com.cardrw.desfire.model.AuthIntent
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.model.AuthKeyPlanner
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.UidKind
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    viewModel: CardViewModel = hiltViewModel(),
    settings: UiSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val expert by settings.expertMode.collectAsStateWithLifecycle()
    var showOverflow by remember { mutableStateOf(false) }
    var showJournal by remember { mutableStateOf(false) }
    var restoreNonce by remember { mutableIntStateOf(0) }
    var dumpNonce by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.card_title)) },
                actions = {
                    IconButton(onClick = { settings.setExpertMode(!expert) }) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = stringResource(
                                if (expert) R.string.density_expert else R.string.density_beginner,
                            ),
                            tint = if (expert) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { showJournal = true }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ListAlt,
                            contentDescription = stringResource(R.string.journal_title),
                        )
                    }
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.card_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_export_dump)) },
                            onClick = {
                                showOverflow = false
                                viewModel.previewDumpCoverage()
                                dumpNonce++
                            },
                            enabled = ui.phase == CardPhase.Ready && !ui.busy,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_restore_dump)) },
                            onClick = {
                                showOverflow = false
                                viewModel.clearRestorePreview()
                                restoreNonce++
                            },
                            enabled = ui.phase == CardPhase.Ready && !ui.busy,
                        )
                        if (ui.rememberedSlotCount > 0) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.card_capture_profile,
                                            ui.rememberedSlotCount,
                                        ),
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.openCaptureFromSession()
                                },
                                enabled = !ui.busy,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_reread)) },
                            onClick = {
                                showOverflow = false
                                viewModel.resetToWaiting()
                            },
                            enabled = !ui.busy,
                        )
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
                        .padding(padding),
                ) {
                    WaitingCard()
                }
            }
            CardPhase.Reading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                    Spacer(Modifier.height(16.dp))
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
                        expert = expert,
                        restoreNonce = restoreNonce,
                        dumpNonce = dumpNonce,
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

    if (showJournal) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showJournal = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().height(420.dp)) {
                Text(
                    text = stringResource(R.string.journal_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                JournalPanel(showToolbar = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyMonitor(
    ui: CardUiState,
    identity: CardIdentity,
    viewModel: CardViewModel,
    expert: Boolean,
    restoreNonce: Int,
    dumpNonce: Int,
    modifier: Modifier = Modifier,
) {
    val authenticated = ui.authSession?.authenticated == true
    val vaultEntries by viewModel.vaultEntries.collectAsStateWithLifecycle()
    var showAuthSheet by rememberSaveable { mutableStateOf(false) }
    var showProfilePicker by remember { mutableStateOf(false) }
    var authPlan by remember { mutableStateOf<AuthKeyPlan?>(null) }
    var neverMessage by remember { mutableStateOf<String?>(null) }
    var closeSheetWhenAuthSettles by remember { mutableStateOf(false) }
    var writeFileNo by remember { mutableStateOf<Int?>(null) }
    var showCreateApp by remember { mutableStateOf(false) }
    var showCreateFile by remember { mutableStateOf(false) }
    var showUpgradeAes by remember { mutableStateOf(false) }
    var showChangeKey by remember { mutableStateOf(false) }
    var showRestoreDump by remember { mutableStateOf(false) }
    var showDumpSheet by remember { mutableStateOf(false) }
    var deleteAppAid by remember { mutableStateOf<String?>(null) }
    var deleteFileNo by remember { mutableStateOf<Int?>(null) }
    var showFormatPicc by remember { mutableStateOf(false) }
    var nodeMenu by remember { mutableStateOf<OpenNodeMenu?>(null) }
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

    LaunchedEffect(ui.openAuthSheetNonce, ui.pendingAuthPlan) {
        if (ui.openAuthSheetNonce > 0L) {
            val plan = ui.pendingAuthPlan ?: viewModel.suggestAuthPlan()
            openAuthSheet(
                plan = plan,
                forceGenericIfNone = plan.candidates.isEmpty() && plan.allowAnyKey,
            )
            viewModel.consumePendingAuthPlan()
        }
    }

    LaunchedEffect(ui.pendingWriteFileNo) {
        val no = ui.pendingWriteFileNo ?: return@LaunchedEffect
        writeFileNo = no
        viewModel.consumePendingWriteFile()
    }

    LaunchedEffect(restoreNonce) {
        if (restoreNonce > 0) {
            showRestoreDump = true
        }
    }
    LaunchedEffect(dumpNonce) {
        if (dumpNonce > 0) {
            showDumpSheet = true
        }
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(ui.lastDumpJson, ui.lastDumpFileName) {
        val json = ui.lastDumpJson ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(json))
        Toast.makeText(
            context,
            context.getString(R.string.card_export_dump_copied),
            Toast.LENGTH_SHORT,
        ).show()
    }

    LaunchedEffect(ui.busy, ui.authSession?.authenticated, ui.errorMessage, closeSheetWhenAuthSettles) {
        if (!closeSheetWhenAuthSettles) return@LaunchedEffect
        if (ui.busy) return@LaunchedEffect
        closeSheetWhenAuthSettles = false
        if (ui.errorMessage == null && ui.authSession?.authenticated == true) {
            showAuthSheet = false
        }
    }

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
        if (ui.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
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
                    openAuthSheet(viewModel.suggestAuthPlan(), forceGenericIfNone = true)
                },
            )
            ActiveProfileChip(
                profileName = ui.activeProfileName,
                busy = ui.busy,
                onClick = {
                    viewModel.reloadProfiles()
                    showProfilePicker = true
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

            if (authenticated && identity.uidKind == UidKind.RANDOM && ui.realUidHex == null) {
                if (expert) {
                    Text(
                        text = stringResource(R.string.card_get_card_uid_auto_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { viewModel.fetchRealUid() },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.card_get_card_uid))
                }
            }

            val version = identity.version
            if (version != null && expert) {
                VersionTechnicalSection(version = version)
            }

            DesfireCardTree(
                applications = identity.applications,
                selectedAidHex = ui.selectedAidHex,
                exploreByAid = ui.exploreByAid,
                busy = ui.busy,
                sessionKey = ui.authSession?.takeIf { it.authenticated }?.keyNumber,
                desToAesEnabled = desToAesEnabled,
                expert = expert,
                friendlyName = viewModel::friendlyName,
                onSelectPicc = {
                    viewModel.selectApplication("000000", tryDefaultAuth = true)
                },
                onSelectApp = { hex ->
                    viewModel.selectApplication(hex, tryDefaultAuth = true)
                },
                onRefresh = { viewModel.explore() },
                onAuthForFile = { node ->
                    if (!viewModel.tryAuthFileWithRemembered(node)) {
                        openAuthSheet(viewModel.authPlanForFile(node), forceGenericIfNone = false)
                    }
                },
                onOpenPiccMenu = { nodeMenu = OpenNodeMenu.Picc },
                onOpenAppMenu = { hex -> nodeMenu = OpenNodeMenu.App(hex) },
                onOpenFileMenu = { node -> nodeMenu = OpenNodeMenu.File(node) },
                onUpgradePiccToAes = { showUpgradeAes = true },
            )

            if (identity.rawNotes.isNotEmpty() && expert) {
                NotesSection(notes = identity.rawNotes)
            }

            val lastDump = ui.lastDumpJson
            val lastDumpName = ui.lastDumpFileName
            if (lastDump != null && lastDumpName != null) {
                Text(
                    text = stringResource(R.string.card_export_dump_ready, lastDumpName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(lastDump))
                            Toast.makeText(
                                context,
                                context.getString(R.string.dumps_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = !ui.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dumps_copy))
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, lastDumpName)
                                putExtra(Intent.EXTRA_TEXT, lastDump)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.dumps_share),
                                ),
                            )
                        },
                        enabled = !ui.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dumps_share))
                    }
                }
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
                suggestSaveName = { keyNo, role ->
                    viewModel.suggestVaultSaveName(keyNo, role)
                },
                activeProfileName = ui.activeProfileName,
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
                        bindToActiveProfile = request.bindToActiveProfile,
                    )
                },
            )
        }
    }

    if (showProfilePicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showProfilePicker = false },
            sheetState = sheetState,
        ) {
            ProfilePickerSheet(
                profiles = ui.profileSummaries,
                activeProfileId = ui.activeProfileId,
                onSelect = { id ->
                    viewModel.setActiveProfile(id)
                    showProfilePicker = false
                },
                onDismiss = { showProfilePicker = false },
            )
        }
    }

    ui.capturePreview?.let { capture ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!ui.busy) viewModel.dismissCapture()
            },
            sheetState = sheetState,
        ) {
            CaptureProfileSheet(
                preview = capture,
                busy = ui.busy,
                errorMessage = ui.errorMessage,
                onNameChange = { viewModel.updateCaptureName(it) },
                onToggleSlot = { viewModel.toggleCaptureSlot(it) },
                onConfirm = { viewModel.confirmCapture(setActive = true) },
                onDismiss = { viewModel.dismissCapture() },
            )
        }
    }

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
        val suggestedAid = viewModel.suggestNextAidHex()
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) showCreateApp = false },
            sheetState = sheetState,
        ) {
            CreateAppSheetContent(
                suggestedAidHex = suggestedAid,
                busy = ui.busy,
                statusLine = ui.statusLine,
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
                statusLine = ui.statusLine,
                errorMessage = ui.errorMessage,
                onDismiss = { if (!ui.busy) showCreateFile = false },
                onCreate = { no, size -> viewModel.createStdFileLab(no, size) },
            )
        }
    }

    if (showChangeKey) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!ui.busy) showChangeKey = false },
            sheetState = sheetState,
        ) {
            ChangeKeyAesSheetContent(
                selectedAidHex = ui.selectedAidHex,
                busy = ui.busy,
                statusLine = ui.statusLine,
                errorMessage = ui.errorMessage,
                onDismiss = { if (!ui.busy) showChangeKey = false },
                onChange = { keyNo, hex -> viewModel.changeKeyAesLab(keyNo, hex) },
            )
        }
    }

    if (showDumpSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!ui.busy) {
                    showDumpSheet = false
                    viewModel.clearDumpCoverage()
                }
            },
            sheetState = sheetState,
        ) {
            DumpSheetContent(
                coverage = ui.dumpCoverage,
                busy = ui.busy,
                statusLine = ui.statusLine,
                errorMessage = ui.errorMessage,
                onDismiss = {
                    if (!ui.busy) {
                        showDumpSheet = false
                        viewModel.clearDumpCoverage()
                    }
                },
                onPreview = { viewModel.previewDumpCoverage() },
                onExport = { mode -> viewModel.exportDump(mode) },
            )
        }
    }

    if (showRestoreDump) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val dumpNames = remember { viewModel.listDumpFileNames() }
        ModalBottomSheet(
            onDismissRequest = {
                if (!ui.busy) {
                    showRestoreDump = false
                    viewModel.clearRestorePreview()
                }
            },
            sheetState = sheetState,
        ) {
            RestoreDumpSheetContent(
                dumpNames = dumpNames,
                busy = ui.busy,
                statusLine = ui.statusLine,
                errorMessage = ui.errorMessage,
                previewLines = ui.restorePreviewLines,
                previewWarnings = ui.restorePreviewWarnings,
                selectedFileName = ui.restorePreviewFileName,
                profileName = ui.activeProfileName,
                materialSummary = ui.restoreMaterialSummary,
                materialLines = ui.restoreMaterialLines,
                materialBlocking = ui.restoreMaterialBlocking,
                onDismiss = {
                    if (!ui.busy) {
                        showRestoreDump = false
                        viewModel.clearRestorePreview()
                    }
                },
                onPreview = { name, formatFirst ->
                    viewModel.previewRestoreDump(
                        fileName = name,
                        mode = DumpRestorePlanner.Mode.STRUCTURE_AND_DATA,
                        formatFirst = formatFirst,
                    )
                },
                onExecute = { name, formatFirst ->
                    viewModel.executeRestoreDump(
                        fileName = name,
                        mode = DumpRestorePlanner.Mode.STRUCTURE_AND_DATA,
                        formatFirst = formatFirst,
                    )
                },
            )
        }
    }

    nodeMenu?.let { menu ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val items = nodeMenuItems(
            menu = menu,
            structureEnabled = structureEnabled,
            desToAesEnabled = desToAesEnabled,
            sessionKey = ui.authSession?.takeIf { it.authenticated }?.keyNumber,
            busy = ui.busy,
            onAuthPicc = {
                viewModel.selectApplication("000000", tryDefaultAuth = false)
                openAuthSheet(viewModel.suggestAuthPlan(), forceGenericIfNone = true)
            },
            onAuthApp = { hex ->
                viewModel.selectApplication(hex, tryDefaultAuth = false)
                openAuthSheet(viewModel.suggestAuthPlan(), forceGenericIfNone = true)
            },
            onAuthFile = { node ->
                if (!viewModel.tryAuthFileWithRemembered(node)) {
                    openAuthSheet(viewModel.authPlanForFile(node), forceGenericIfNone = false)
                }
            },
            onWriteFile = { node -> viewModel.requestWriteFile(node) },
            onAddApp = { showCreateApp = true },
            onAddFile = { showCreateFile = true },
            onChangeKey = { showChangeKey = true },
            onFormat = { showFormatPicc = true },
            onUpgradeAes = { showUpgradeAes = true },
            onDeleteApp = { deleteAppAid = it },
            onDeleteFile = { deleteFileNo = it.fileNo },
            onRefresh = { viewModel.explore() },
        )
        ModalBottomSheet(
            onDismissRequest = { nodeMenu = null },
            sheetState = sheetState,
        ) {
            val title = when (menu) {
                OpenNodeMenu.Picc -> stringResource(R.string.card_picc_label)
                is OpenNodeMenu.App -> stringResource(R.string.card_node_app, prettyAid(menu.aidHex))
                is OpenNodeMenu.File -> stringResource(R.string.card_node_file, menu.node.fileNo)
            }
            val subtitle = when (menu) {
                OpenNodeMenu.Picc -> "00 00 00"
                is OpenNodeMenu.App -> menu.aidHex
                is OpenNodeMenu.File -> menu.node.settings.compactLine
            }
            NodeActionSheet(
                title = title,
                subtitle = subtitle,
                items = items,
                onDismiss = { nodeMenu = null },
            )
        }
    }

    if (showFormatPicc) {
        FormatPiccDialog(
            uidDisplay = ui.realUidHex ?: identity.displayUid,
            busy = ui.busy,
            onConfirm = {
                viewModel.formatPiccLab()
                showFormatPicc = false
            },
            onDismiss = { showFormatPicc = false },
        )
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

    LaunchedEffect(desToAesEnabled, showUpgradeAes) {
        if (showUpgradeAes && !desToAesEnabled) {
            showUpgradeAes = false
        }
    }
}

@Composable
private fun nodeMenuItems(
    menu: OpenNodeMenu,
    structureEnabled: Boolean,
    desToAesEnabled: Boolean,
    sessionKey: Int?,
    busy: Boolean,
    onAuthPicc: () -> Unit,
    onAuthApp: (String) -> Unit,
    onAuthFile: (FileNode) -> Unit,
    onWriteFile: (FileNode) -> Unit,
    onAddApp: () -> Unit,
    onAddFile: () -> Unit,
    onChangeKey: () -> Unit,
    onFormat: () -> Unit,
    onUpgradeAes: () -> Unit,
    onDeleteApp: (String) -> Unit,
    onDeleteFile: (FileNode) -> Unit,
    onRefresh: () -> Unit,
): List<NodeActionItem> {
    val enabled = !busy
    return when (menu) {
        OpenNodeMenu.Picc -> buildList {
            add(NodeActionItem(stringResource(R.string.card_auth_action), enabled = enabled, onClick = onAuthPicc))
            add(NodeActionItem(stringResource(R.string.card_action_refresh), enabled = enabled, onClick = onRefresh))
            if (desToAesEnabled) {
                add(NodeActionItem(stringResource(R.string.card_tree_upgrade_aes), enabled = enabled, onClick = onUpgradeAes))
            }
            if (structureEnabled) {
                add(NodeActionItem(stringResource(R.string.card_tree_add_app), enabled = enabled, onClick = onAddApp))
                add(NodeActionItem(stringResource(R.string.card_tree_change_key), enabled = enabled, onClick = onChangeKey))
                add(
                    NodeActionItem(
                        stringResource(R.string.card_tree_format_picc),
                        destructive = true,
                        enabled = enabled,
                        onClick = onFormat,
                    ),
                )
            }
        }
        is OpenNodeMenu.App -> buildList {
            add(
                NodeActionItem(
                    stringResource(R.string.card_auth_action),
                    enabled = enabled,
                    onClick = { onAuthApp(menu.aidHex) },
                ),
            )
            add(NodeActionItem(stringResource(R.string.card_action_refresh), enabled = enabled, onClick = onRefresh))
            if (structureEnabled) {
                add(NodeActionItem(stringResource(R.string.card_tree_add_file), enabled = enabled, onClick = onAddFile))
                add(NodeActionItem(stringResource(R.string.card_tree_change_key), enabled = enabled, onClick = onChangeKey))
                add(
                    NodeActionItem(
                        stringResource(R.string.card_tree_delete_app),
                        destructive = true,
                        enabled = enabled,
                        onClick = { onDeleteApp(menu.aidHex) },
                    ),
                )
            }
        }
        is OpenNodeMenu.File -> {
            val node = menu.node
            val rights = node.settings.accessRights
            val readPlan = AuthKeyPlanner.plan(AuthIntent.ReadFile(node.fileNo, rights), sessionKey)
            val writePlan = AuthKeyPlanner.plan(AuthIntent.WriteFile(node.fileNo, rights), sessionKey)
            buildList {
                if (node.dataHex == null && readPlan.barrier == AuthBarrier.NEEDS_KEY) {
                    val keys = readPlan.candidates.joinToString(",") { "k${it.keyNo}" }
                    add(
                        NodeActionItem(
                            stringResource(R.string.card_file_auth_to_read, keys.ifEmpty { "?" }),
                            enabled = enabled,
                            onClick = { onAuthFile(node) },
                        ),
                    )
                }
                if (writePlan.barrier != AuthBarrier.NEVER) {
                    add(
                        NodeActionItem(
                            stringResource(R.string.card_file_write_action),
                            enabled = enabled && structureEnabled,
                            onClick = { onWriteFile(node) },
                        ),
                    )
                }
                add(
                    NodeActionItem(
                        stringResource(R.string.card_file_delete_action),
                        destructive = true,
                        enabled = enabled && structureEnabled,
                        onClick = { onDeleteFile(node) },
                    ),
                )
            }
        }
    }
}
