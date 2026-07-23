package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.repository.KeyVaultNaming
import com.cardrw.app.data.repository.KeyVaultRepository
import com.cardrw.app.security.VaultLockController
import com.cardrw.desfire.util.Hex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultUiState(
    val entries: List<KeyVaultEntryMeta> = emptyList(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    /** K3 : option verrou biométrie / PIN. */
    val lockEnabled: Boolean = false,
    /** false si verrou ON et session expirée. */
    val unlocked: Boolean = true,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val keyVault: KeyVaultRepository,
    private val vaultLock: VaultLockController,
) : ViewModel() {

    private val _ui = MutableStateFlow(VaultUiState())
    val ui: StateFlow<VaultUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            keyVault.load()
            refreshLockState()
            keyVault.entries.collect { list ->
                _ui.update { it.copy(entries = list) }
                refreshLockState()
            }
        }
    }

    fun refreshLockState() {
        _ui.update {
            it.copy(
                lockEnabled = vaultLock.lockEnabled,
                unlocked = vaultLock.isUnlocked(),
            )
        }
    }

    fun setLockEnabled(enabled: Boolean) {
        vaultLock.lockEnabled = enabled
        refreshLockState()
        _ui.update {
            it.copy(
                statusMessage = if (enabled) {
                    "Verrou coffre activé — biométrie / PIN avant usage des clés"
                } else {
                    "Verrou coffre désactivé"
                },
            )
        }
    }

    fun onBiometricUnlocked() {
        vaultLock.markUnlocked()
        refreshLockState()
        _ui.update { it.copy(statusMessage = "Coffre déverrouillé (5 min)", errorMessage = null) }
    }

    fun lockNow() {
        vaultLock.lockNow()
        refreshLockState()
        _ui.update { it.copy(statusMessage = "Coffre verrouillé") }
    }

    fun nextDefaultName(): String = keyVault.nextDefaultName()

    fun create(displayName: String, keyHex: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                val clean = keyHex.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
                require(clean.length == 32) { "attendu 32 hex, got ${clean.length}" }
                val name = displayName.ifBlank { keyVault.nextDefaultName() }
                keyVault.create(name, Hex.decode(clean))
                _ui.update {
                    it.copy(busy = false, statusMessage = "Entrée « $name » créée")
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Création impossible")
                }
            }
        }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                keyVault.rename(id, newName)
                _ui.update { it.copy(busy = false, statusMessage = "Renommé") }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Renommage impossible")
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                keyVault.delete(id)
                _ui.update { it.copy(busy = false, statusMessage = "Entrée supprimée") }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Suppression impossible")
                }
            }
        }
    }

    fun clearMessages() {
        _ui.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun isNameSuspicious(name: String): Boolean = KeyVaultNaming.looksLikeKeyHex(name)
}
