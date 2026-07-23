package com.cardrw.app.ui.screens.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cardrw.app.R
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.AuthBarrier
import com.cardrw.desfire.model.AuthIntent
import com.cardrw.desfire.model.AuthKeyPlanner
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.KeySettingsInfo
import com.cardrw.desfire.model.canWriteWith

/**
 * Arbre moniteur U4 : **PICC super-nœud → applications → fichiers**.
 *
 * - PICC n’est plus une ligne d’app parmi d’autres.
 * - Les fichiers sont imbriqués sous l’app sélectionnée (ou en cache sous les apps visitées).
 * - Trait **plein** = info lisible / déjà lue ; **pointillé** = lacune (droits / free-list / non exploré).
 */
@Composable
fun DesfireCardTree(
    applications: List<Aid>,
    selectedAidHex: String?,
    exploreByAid: Map<String, ApplicationExploreResult>,
    busy: Boolean,
    sessionKey: Int?,
    /** true = session AES (pas DES) prête pour Write / Create / Delete. */
    structureEnabled: Boolean = false,
    /**
     * true = session DES legacy sur PICC : proposer bascule master DES→AES
     * (Create/Write bloqués tant que non AES).
     */
    desToAesEnabled: Boolean = false,
    friendlyName: (String) -> String?,
    onSelectPicc: () -> Unit,
    onDoubleSelectPicc: () -> Unit,
    onSelectApp: (String) -> Unit,
    onDoubleSelectApp: (String) -> Unit,
    onRefresh: () -> Unit,
    onAuthForFile: (FileNode) -> Unit,
    onWriteFile: (FileNode) -> Unit = {},
    onAddApplication: () -> Unit = {},
    onUpgradePiccToAes: () -> Unit = {},
    onFormatPicc: () -> Unit = {},
    onDeleteApplication: (aidHex: String) -> Unit = {},
    onAddFile: () -> Unit = {},
    onDeleteFile: (FileNode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isPiccSelected = selectedAidHex.equals("000000", ignoreCase = true)
    val piccExplore = exploreByAid["000000"]

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.card_tree_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.card_tree_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- Super-nœud PICC ---
        TreeShell(
            complete = piccExplore?.keySettings != null || !isPiccSelected,
            selected = isPiccSelected,
            accent = MaterialTheme.colorScheme.tertiary,
        ) {
            TreeNodeHeader(
                badge = "PICC",
                title = stringResource(R.string.card_picc_label),
                subtitle = "00 00 00",
                selected = isPiccSelected,
                enabled = !busy,
                onClick = onSelectPicc,
                onDoubleClick = onDoubleSelectPicc,
                trailing = if (isPiccSelected) {
                    stringResource(R.string.card_app_selected_badge)
                } else {
                    null
                },
            )

            if (isPiccSelected) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    piccExplore?.keySettings?.let { ks ->
                        KeySettingsMetaNode(ks = ks, domainLabel = stringResource(R.string.card_tree_picc_meta))
                    } ?: run {
                        IncompleteHint(stringResource(R.string.card_tree_picc_pending))
                    }
                    if (desToAesEnabled) {
                        Text(
                            text = stringResource(R.string.card_tree_upgrade_aes_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onUpgradePiccToAes,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.card_tree_upgrade_aes))
                        }
                    }
                    if (structureEnabled) {
                        Button(
                            onClick = onAddApplication,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.card_tree_add_app))
                        }
                        OutlinedButton(
                            onClick = onFormatPicc,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.card_tree_format_picc))
                        }
                    }
                    RefreshRow(busy = busy, onRefresh = onRefresh)
                    piccExplore?.notes?.takeIf { it.isNotEmpty() }?.let { TreeNotes(it) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.card_tree_apps_header, applications.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (applications.isEmpty()) {
                    Text(
                        text = stringResource(R.string.card_no_apps),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    applications.forEachIndexed { index, aid ->
                        val hex = aid.hex
                        val selected = hex.equals(selectedAidHex, ignoreCase = true)
                        val explore = exploreByAid[hex.uppercase()]
                        AppTreeNode(
                            index = index,
                            aidHex = hex,
                            friendly = friendlyName(hex),
                            selected = selected,
                            explore = explore,
                            busy = busy,
                            sessionKey = sessionKey.takeIf { selected },
                            structureEnabled = structureEnabled,
                            onSelect = { onSelectApp(hex) },
                            onDoubleSelect = { onDoubleSelectApp(hex) },
                            onRefresh = onRefresh,
                            onAuthForFile = onAuthForFile,
                            onWriteFile = onWriteFile,
                            onDeleteApp = { onDeleteApplication(hex) },
                            onAddFile = onAddFile,
                            onDeleteFile = onDeleteFile,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTreeNode(
    index: Int,
    aidHex: String,
    friendly: String?,
    selected: Boolean,
    explore: ApplicationExploreResult?,
    busy: Boolean,
    sessionKey: Int?,
    structureEnabled: Boolean,
    onSelect: () -> Unit,
    onDoubleSelect: () -> Unit,
    onRefresh: () -> Unit,
    onAuthForFile: (FileNode) -> Unit,
    onWriteFile: (FileNode) -> Unit,
    onDeleteApp: () -> Unit,
    onAddFile: () -> Unit,
    onDeleteFile: (FileNode) -> Unit,
) {
    val structureComplete = explore != null &&
        (explore.keySettings != null || explore.structureFromCache || explore.files.isNotEmpty() ||
            explore.notes.isNotEmpty())
    val hasUnreadNeedingKey = explore?.files?.any { node ->
        node.dataHex == null &&
            AuthKeyPlanner.plan(
                AuthIntent.ReadFile(node.fileNo, node.settings.accessRights),
                sessionKey,
            ).barrier == AuthBarrier.NEEDS_KEY
    } == true
    // Complet = structure connue et (pas de lacune lecture, ou non sélectionné → cache OK)
    val complete = when {
        explore == null -> false
        selected && hasUnreadNeedingKey -> false
        else -> structureComplete
    }

    TreeShell(
        complete = complete,
        selected = selected,
        accent = MaterialTheme.colorScheme.primary,
    ) {
        val cacheSummary = explore?.let { ex ->
            val n = ex.files.size
            val r = ex.files.count { it.dataHex != null }
            if (n == 0) {
                stringResource(R.string.card_tree_app_cache_empty)
            } else {
                stringResource(R.string.card_tree_app_cache_files, n, r)
            }
        }

        TreeNodeHeader(
            badge = "#$index",
            title = prettyAid(aidHex),
            subtitle = friendly ?: cacheSummary,
            selected = selected,
            enabled = !busy,
            onClick = onSelect,
            onDoubleClick = onDoubleSelect,
            trailing = when {
                selected -> stringResource(R.string.card_app_selected_badge)
                explore != null -> stringResource(R.string.card_tree_cached_badge)
                else -> null
            },
            monospaceTitle = true,
        )

        // Enfants seulement si sélectionnée (P3 : une app à la fois)
        if (selected) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (explore == null) {
                    IncompleteHint(stringResource(R.string.card_tree_app_pending))
                } else {
                    if (explore.structureFromCache) {
                        Text(
                            text = stringResource(R.string.card_explore_from_cache),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    explore.keySettings?.let { ks ->
                        KeySettingsMetaNode(
                            ks = ks,
                            domainLabel = stringResource(R.string.card_tree_app_meta),
                        )
                    }
                    // Volume discret = somme des tailles fichier (GetFileSettings), pas de cmd dédiée
                    AppVolumeHint(files = explore.files)
                    if (explore.files.isEmpty()) {
                        Text(
                            text = stringResource(R.string.card_no_files),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.card_tree_files_header, explore.files.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (node in explore.files) {
                            FileTreeNode(
                                node = node,
                                busy = busy,
                                sessionKey = sessionKey,
                                structureEnabled = structureEnabled,
                                onAuthForRead = { onAuthForFile(node) },
                                onWrite = { onWriteFile(node) },
                                onDelete = { onDeleteFile(node) },
                            )
                        }
                    }
                    if (explore.notes.isNotEmpty()) {
                        TreeNotes(explore.notes)
                    }
                }
                if (structureEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onAddFile,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.card_tree_add_file))
                        }
                        OutlinedButton(
                            onClick = onDeleteApp,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.card_tree_delete_app))
                        }
                    }
                }
                RefreshRow(busy = busy, onRefresh = onRefresh)
            }
        } else if (explore != null && explore.files.isNotEmpty()) {
            // Aperçu replié : compteur seulement (pas de détail fichiers hors sélection)
            Text(
                text = stringResource(
                    R.string.card_tree_collapsed_hint,
                    explore.files.size,
                    explore.files.count { it.dataHex != null },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    busy: Boolean,
    sessionKey: Int?,
    structureEnabled: Boolean,
    onAuthForRead: () -> Unit,
    onWrite: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(node.fileNo) { mutableStateOf(false) }
    val rights = node.settings.accessRights
    val readPlan = remember(node.fileNo, rights, sessionKey) {
        AuthKeyPlanner.plan(AuthIntent.ReadFile(node.fileNo, rights), sessionKey)
    }
    val needsAuthForRead = node.dataHex == null && readPlan.barrier == AuthBarrier.NEEDS_KEY
    val neverRead = readPlan.barrier == AuthBarrier.NEVER
    val dataHex = node.dataHex
    val canWrite = structureEnabled &&
        (rights.isWriteFree || rights.canWriteWith(sessionKey))
    // Plein = contenu lu, Free, Never (état final), ou erreur connue
    val complete = dataHex != null ||
        neverRead ||
        rights.isReadFree ||
        node.dataError != null

    val accessBadge = when {
        dataHex != null -> stringResource(R.string.card_file_badge_read)
        neverRead -> stringResource(R.string.card_file_badge_never)
        rights.isReadFree -> stringResource(R.string.card_file_badge_free)
        needsAuthForRead -> stringResource(R.string.card_file_badge_auth)
        node.dataError != null -> stringResource(R.string.card_file_badge_error)
        else -> stringResource(R.string.card_file_badge_pending)
    }
    val accessColor = when {
        dataHex != null -> MaterialTheme.colorScheme.tertiary
        neverRead -> MaterialTheme.colorScheme.error
        needsAuthForRead -> MaterialTheme.colorScheme.primary
        node.dataError != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TreeShell(
        complete = complete,
        selected = false,
        accent = MaterialTheme.colorScheme.secondary,
        nested = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = node.settings.summaryLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = accessBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accessColor,
                    )
                }
                Text(
                    text = "R=${rights.readLabel} · W=${rights.writeLabel} · " +
                        "RW=${rights.readWriteLabel} · Ch=${rights.changeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // U5 : preview hex visible sans expand (P1 moniteur)
                if (dataHex != null && !expanded) {
                    Text(
                        text = stringResource(
                            R.string.card_file_preview,
                            hexPreview(dataHex, maxBytes = 8),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (!complete && needsAuthForRead) {
                    Text(
                        text = stringResource(R.string.card_tree_file_incomplete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }

        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (needsAuthForRead) {
                    val keysLabel = readPlan.candidates.joinToString(", ") { "n°${it.keyNo}" }
                    TextButton(
                        onClick = onAuthForRead,
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                R.string.card_file_auth_to_read,
                                keysLabel.ifEmpty { "?" },
                            ),
                        )
                    }
                } else if (neverRead && dataHex == null) {
                    Text(
                        text = stringResource(R.string.card_file_read_never),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (canWrite) {
                    TextButton(
                        onClick = onWrite,
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.card_file_write_action))
                    }
                }
                if (structureEnabled) {
                    TextButton(
                        onClick = onDelete,
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.card_file_delete_action))
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                val dataError = node.dataError
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    when {
                        dataHex != null -> {
                            Text(
                                text = stringResource(
                                    R.string.card_file_data_full,
                                    dataHex.length / 2,
                                ),
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

/**
 * Volume discret de l’app **sélectionnée** : somme des [FileSettings.sizeBytes]
 * déjà obtenus via explore (Standard/Backup). Pas de commande DESFire « taille app ».
 */
@Composable
private fun AppVolumeHint(files: List<FileNode>) {
    val sizes = files.mapNotNull { it.settings.sizeBytes }
    val known = sizes.size
    val total = sizes.sum()
    val label = when {
        files.isEmpty() -> stringResource(R.string.card_tree_app_volume_empty)
        known == 0 -> stringResource(
            R.string.card_tree_app_volume_partial,
            "—",
            0,
            files.size,
        )
        known < files.size -> stringResource(
            R.string.card_tree_app_volume_partial,
            formatByteVolume(total),
            known,
            files.size,
        )
        else -> stringResource(R.string.card_tree_app_volume, formatByteVolume(total))
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun formatByteVolume(bytes: Int): String {
    return if (bytes < 1024) {
        stringResource(R.string.card_tree_bytes_unit, bytes)
    } else {
        stringResource(R.string.card_tree_kib_unit, bytes / 1024.0)
    }
}

@Composable
private fun KeySettingsMetaNode(
    ks: KeySettingsInfo,
    domainLabel: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = domainLabel,
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

@Composable
private fun TreeShell(
    complete: Boolean,
    selected: Boolean,
    accent: Color,
    nested: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(if (nested) 8.dp else 10.dp)
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        nested -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        complete -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(
                if (complete) {
                    Modifier.border(width = borderWidth, color = borderColor, shape = shape)
                } else {
                    Modifier.dashedBorder(
                        strokeWidth = borderWidth,
                        color = borderColor,
                        cornerRadius = if (nested) 8.dp else 10.dp,
                    )
                },
            ),
    ) {
        if (selected && !nested) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accent),
            )
        }
        content()
    }
}

@Composable
private fun TreeNodeHeader(
    badge: String,
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    trailing: String?,
    monospaceTitle: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(enabled, title) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { onDoubleClick() },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = badge,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospaceTitle) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RefreshRow(busy: Boolean, onRefresh: () -> Unit) {
    OutlinedButton(
        onClick = onRefresh,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.card_action_refresh))
            }
        } else {
            Text(stringResource(R.string.card_action_refresh))
        }
    }
}

@Composable
private fun IncompleteHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TreeNotes(notes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (note in notes) {
            Text(
                text = "· $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bordure pointillée (nœud lacunaire P3). */
private fun Modifier.dashedBorder(
    strokeWidth: Dp,
    color: Color,
    cornerRadius: Dp,
    dashLength: Float = 10f,
    gapLength: Float = 8f,
): Modifier = drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength), 0f),
    )
    drawRoundRect(
        color = color,
        style = stroke,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
    )
}

/** Hex espacé par octets : AABBCC → AA BB CC */
internal fun prettyAid(hex: String): String {
    val clean = hex.replace(" ", "").uppercase()
    return clean.chunked(2).joinToString(" ")
}

internal fun prettyHex(hex: String): String = prettyAid(hex)

/** Preview moniteur : N premiers octets + ellipse si plus long. */
internal fun hexPreview(hex: String, maxBytes: Int = 8): String {
    val clean = hex.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
    if (clean.isEmpty()) return "—"
    val take = clean.take(maxBytes * 2)
    val pretty = take.chunked(2).joinToString(" ")
    return if (clean.length > maxBytes * 2) "$pretty …" else pretty
}
