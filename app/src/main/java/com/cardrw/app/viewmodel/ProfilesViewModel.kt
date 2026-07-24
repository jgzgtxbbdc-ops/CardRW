package com.cardrw.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.app.data.repository.KeyProfileRepository
import com.cardrw.app.data.repository.KeyProfileRules
import com.cardrw.app.data.repository.KeyVaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfilesListUiState(
    val profiles: List<KeyProfile> = emptyList(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

data class ProfileDetailUiState(
    val profile: KeyProfile? = null,
    val vaultEntries: List<KeyVaultEntryMeta> = emptyList(),
    /** vaultIds absents du coffre (bindings cassés). */
    val brokenVaultIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profilesRepo: KeyProfileRepository,
    private val keyVault: KeyVaultRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProfilesListUiState())
    val ui: StateFlow<ProfilesListUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            profilesRepo.load()
            keyVault.load()
            profilesRepo.profiles.collect { list ->
                _ui.update { it.copy(profiles = list) }
            }
        }
    }

    fun nextDefaultName(): String = profilesRepo.nextDefaultName()

    fun create(displayName: String, notes: String? = null) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                val name = displayName.ifBlank { profilesRepo.nextDefaultName() }
                profilesRepo.create(name, notes = notes)
                _ui.update {
                    it.copy(busy = false, statusMessage = "Profil « $name » créé")
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Création impossible")
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                profilesRepo.delete(id)
                _ui.update { it.copy(busy = false, statusMessage = "Profil supprimé") }
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
}

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profilesRepo: KeyProfileRepository,
    private val keyVault: KeyVaultRepository,
) : ViewModel() {

    private val profileId: String =
        checkNotNull(savedStateHandle["profileId"]) { "profileId requis" }

    private val _ui = MutableStateFlow(ProfileDetailUiState())
    val ui: StateFlow<ProfileDetailUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            profilesRepo.load()
            keyVault.load()
            combine(profilesRepo.profiles, keyVault.entries) { profiles, vault ->
                val p = profiles.find { it.id == profileId }
                val vaultIds = vault.map { it.id }.toSet()
                val broken = p?.let {
                    KeyProfileRules.findBrokenVaultBindings(it.bindings, vaultIds)
                        .mapNotNull { b ->
                            (b.materialRef as? MaterialRef.VaultEntry)?.vaultId
                        }
                        .toSet()
                }.orEmpty()
                Triple(p, vault, broken)
            }.collect { (p, vault, broken) ->
                _ui.update {
                    it.copy(
                        profile = p,
                        vaultEntries = vault,
                        brokenVaultIds = broken,
                    )
                }
            }
        }
    }

    fun rename(newName: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                profilesRepo.rename(profileId, newName)
                _ui.update { it.copy(busy = false, statusMessage = "Renommé") }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Renommage impossible")
                }
            }
        }
    }

    fun setAllowFactoryFallback(allow: Boolean) {
        viewModelScope.launch {
            try {
                profilesRepo.setAllowFactoryFallback(profileId, allow)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = e.message ?: "Mise à jour impossible")
                }
            }
        }
    }

    fun setNotes(notes: String?) {
        viewModelScope.launch {
            try {
                profilesRepo.updateNotes(profileId, notes)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = e.message ?: "Notes non enregistrées")
                }
            }
        }
    }

    /**
     * Ajoute ou remplace un binding.
     * @param scopePicc true = PICC, false = Application
     * @param aidHex requis si !scopePicc
     * @param materialVaultId null = FactoryZero
     */
    fun upsertBinding(
        scopePicc: Boolean,
        aidHex: String?,
        keyNo: Int,
        materialVaultId: String?,
        roleHint: String?,
        required: Boolean,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                val scope = if (scopePicc) {
                    BindingScope.Picc
                } else {
                    BindingScope.Application(
                        KeyProfileRules.normalizeAidHex(aidHex.orEmpty()),
                    )
                }
                val material = if (materialVaultId.isNullOrBlank()) {
                    MaterialRef.FactoryZero
                } else {
                    // Vérifie que l’entrée existe encore
                    val known = keyVault.entries.value.any { it.id == materialVaultId }
                    require(known) { "entrée coffre introuvable (vaultId manquant)" }
                    MaterialRef.VaultEntry(materialVaultId)
                }
                val binding = KeyBinding(
                    scope = scope,
                    keyNo = keyNo,
                    materialRef = material,
                    roleHint = roleHint,
                    required = required,
                )
                profilesRepo.upsertBinding(profileId, binding)
                _ui.update {
                    it.copy(
                        busy = false,
                        statusMessage = "Binding ${KeyProfileRules.formatBindingLabel(binding)} enregistré",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Binding impossible")
                }
            }
        }
    }

    fun removeBinding(slotKey: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                profilesRepo.removeBindingBySlotKey(profileId, slotKey)
                _ui.update { it.copy(busy = false, statusMessage = "Binding retiré") }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Suppression impossible")
                }
            }
        }
    }

    fun deleteProfile(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusMessage = null) }
            try {
                profilesRepo.delete(profileId)
                _ui.update { it.copy(busy = false) }
                onDeleted()
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = e.message ?: "Suppression impossible")
                }
            }
        }
    }

    fun vaultDisplayName(vaultId: String): String? =
        _ui.value.vaultEntries.find { it.id == vaultId }?.displayName

    fun clearMessages() {
        _ui.update { it.copy(errorMessage = null, statusMessage = null) }
    }
}
