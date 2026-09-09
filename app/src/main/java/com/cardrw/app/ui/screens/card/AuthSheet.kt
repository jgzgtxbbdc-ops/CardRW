package com.cardrw.app.ui.screens.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cardrw.app.R
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.ui.components.BusyLabel
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.util.Hex

/** Paramètres d’auth depuis la sheet (hex ou coffre). */
data class AuthMaterialRequest(
    val keyNo: Int,
    val keyHex: String? = null,
    val vaultEntryId: String? = null,
    val saveAsVaultName: String? = null,
    /** P2 : upsert binding sur le profil actif après auth OK. */
    val bindToActiveProfile: Boolean = false,
)

/**
 * Formulaire auth en bottom sheet (U2 + K2 + U3).
 * Slot carte restreint aux [AuthKeyPlan.candidates] quand connus.
 * Matériau : coffre nommé **ou** hex (+ option enregistrer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheetContent(
    authPlan: AuthKeyPlan,
    initialKeyHex: String,
    vaultEntries: List<KeyVaultEntryMeta>,
    suggestSaveName: (keyNo: Int, roleHint: String?) -> String,
    activeProfileName: String?,
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
    var saveName by remember {
        val role = authPlan.candidates.find { it.keyNo == preferred }?.roleLabel
        mutableStateOf(suggestSaveName(preferred, role))
    }
    var saveNameTouched by remember { mutableStateOf(false) }
    var bindToProfile by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showAllKeys by remember(authPlan) {
        mutableStateOf(authPlan.candidates.isEmpty())
    }
    LaunchedEffect(draftKeyNo, selectedAid, authPlan) {
        if (!saveNameTouched) {
            val role = authPlan.candidates.find { it.keyNo == draftKeyNo }?.roleLabel
            saveName = suggestSaveName(draftKeyNo, role)
        }
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
                    onValueChange = {
                        saveName = it
                        saveNameTouched = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.vault_name)) },
                    supportingText = {
                        Text(stringResource(R.string.vault_name_suggest_hint))
                    },
                    singleLine = true,
                    enabled = !busy,
                )
            }
        }

        if (activeProfileName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = bindToProfile,
                    onCheckedChange = { bindToProfile = it },
                    enabled = !busy,
                )
                Text(
                    text = stringResource(R.string.card_auth_bind_profile, activeProfileName),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        fun submit() {
            if (useVault) {
                val id = selectedVaultId ?: return
                onAuthenticate(
                    AuthMaterialRequest(
                        keyNo = draftKeyNo,
                        vaultEntryId = id,
                        bindToActiveProfile = bindToProfile && activeProfileName != null,
                    ),
                )
            } else {
                if (draftKeyHex.length != 32) return
                onAuthenticate(
                    AuthMaterialRequest(
                        keyNo = draftKeyNo,
                        keyHex = draftKeyHex,
                        saveAsVaultName = saveName.trim().takeIf { saveToVault && it.isNotEmpty() },
                        bindToActiveProfile = bindToProfile && activeProfileName != null,
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

private fun groupHex(compact: String): String {
    val clean = compact.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
    return clean.chunked(4).joinToString(" ")
}
