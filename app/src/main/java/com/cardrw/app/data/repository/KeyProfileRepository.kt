package com.cardrw.app.data.repository

import android.content.Context
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.KeyProfilesFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profils de clés : métadonnées JSON (clair), références [vaultId] uniquement.
 * Même esprit que [KeyVaultRepository] meta — jamais de copie des 16 octets.
 * Spec : [docs/UX_PROFIL_CLES.md] §4.3, P1.
 *
 * Profil **actif** (P2) : id en SharedPreferences — moniteur / dump le consultent
 * via [activeProfile] sans re-sélection à chaque pose.
 */
@Singleton
class KeyProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val metaFile: File
        get() = File(context.filesDir, META_FILE)
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<KeyProfile>>(emptyList())
    val profiles: StateFlow<List<KeyProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(prefs.getString(KEY_ACTIVE_ID, null))
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /** Profil actif courant (null = moniteur labo sans profil). */
    fun activeProfile(): KeyProfile? {
        val id = _activeProfileId.value ?: return null
        return _profiles.value.find { it.id == id }
    }

    fun setActiveProfileId(id: String?) {
        if (id != null && _profiles.value.none { it.id == id }) {
            // id inconnu : clear plutôt que pointer dans le vide
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
            _activeProfileId.value = null
            return
        }
        if (id == null) {
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        }
        _activeProfileId.value = id
    }

    suspend fun load() {
        mutex.withLock {
            _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
            // Si profil actif supprimé hors session → clear
            val active = _activeProfileId.value
            if (active != null && _profiles.value.none { it.id == active }) {
                prefs.edit().remove(KEY_ACTIVE_ID).apply()
                _activeProfileId.value = null
            }
        }
    }

    fun nextDefaultName(): String =
        KeyProfileRules.nextDefaultName(_profiles.value.map { it.displayName })

    fun getById(id: String): KeyProfile? = _profiles.value.find { it.id == id }

    /**
     * Crée un profil vide (ou avec bindings initiaux).
     * @return id
     */
    suspend fun create(
        displayName: String,
        notes: String? = null,
        allowFactoryFallback: Boolean = true,
        preferEv2: Boolean? = null,
        bindings: List<KeyBinding> = emptyList(),
    ): String = mutex.withLock {
        val name = displayName.trim()
        require(KeyProfileRules.isValidDisplayName(name)) { "nom invalide" }
        val list = withContext(Dispatchers.IO) { readUnlocked() }
        require(
            list.none {
                KeyProfileRules.normalizeForCompare(it.displayName) ==
                    KeyProfileRules.normalizeForCompare(name)
            },
        ) { "nom déjà utilisé" }
        val normalizedBindings = KeyProfileRules.validateAndNormalizeBindings(bindings)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val profile = KeyProfile(
            id = id,
            displayName = name,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
            allowFactoryFallback = allowFactoryFallback,
            preferEv2 = preferEv2,
            bindings = normalizedBindings,
        )
        withContext(Dispatchers.IO) { writeUnlocked(list + profile) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
        id
    }

    suspend fun rename(id: String, newDisplayName: String) = mutex.withLock {
        val name = newDisplayName.trim()
        require(KeyProfileRules.isValidDisplayName(name)) { "nom invalide" }
        val list = withContext(Dispatchers.IO) { readUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        require(idx >= 0) { "profil introuvable" }
        val conflict = list.any {
            it.id != id &&
                KeyProfileRules.normalizeForCompare(it.displayName) ==
                KeyProfileRules.normalizeForCompare(name)
        }
        require(!conflict) { "nom déjà utilisé" }
        val now = System.currentTimeMillis()
        list[idx] = list[idx].copy(displayName = name, updatedAt = now)
        withContext(Dispatchers.IO) { writeUnlocked(list) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
    }

    suspend fun updateNotes(id: String, notes: String?) = mutex.withLock {
        mutateProfileUnlocked(id) { p ->
            p.copy(notes = notes?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    suspend fun setAllowFactoryFallback(id: String, allow: Boolean) = mutex.withLock {
        mutateProfileUnlocked(id) { p -> p.copy(allowFactoryFallback = allow) }
    }

    suspend fun setPreferEv2(id: String, preferEv2: Boolean?) = mutex.withLock {
        mutateProfileUnlocked(id) { p -> p.copy(preferEv2 = preferEv2) }
    }

    /**
     * Remplace la liste complète des bindings (unicité validée).
     */
    suspend fun setBindings(id: String, bindings: List<KeyBinding>) = mutex.withLock {
        val normalized = KeyProfileRules.validateAndNormalizeBindings(bindings)
        mutateProfileUnlocked(id) { p -> p.copy(bindings = normalized) }
    }

    /**
     * Ajoute ou remplace le binding pour le même (scope, keyNo).
     */
    suspend fun upsertBinding(profileId: String, binding: KeyBinding) = mutex.withLock {
        val list = withContext(Dispatchers.IO) { readUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == profileId }
        require(idx >= 0) { "profil introuvable" }
        val profile = list[idx]
        val normalizedOne = KeyProfileRules.validateAndNormalizeBindings(listOf(binding)).single()
        val slotKey = KeyProfileRules.bindingSlotKey(normalizedOne)
        val without = profile.bindings.filter {
            KeyProfileRules.bindingSlotKey(it) != slotKey
        }
        val newBindings = KeyProfileRules.validateAndNormalizeBindings(without + normalizedOne)
        val now = System.currentTimeMillis()
        list[idx] = profile.copy(bindings = newBindings, updatedAt = now)
        withContext(Dispatchers.IO) { writeUnlocked(list) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
    }

    /** Supprime le binding identifié par sa clé d’unicité ([KeyProfileRules.bindingSlotKey]). */
    suspend fun removeBindingBySlotKey(profileId: String, slotKey: String) = mutex.withLock {
        val list = withContext(Dispatchers.IO) { readUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == profileId }
        require(idx >= 0) { "profil introuvable" }
        val profile = list[idx]
        val remaining = profile.bindings.filterNot {
            KeyProfileRules.bindingSlotKey(it) == slotKey
        }
        require(remaining.size < profile.bindings.size) { "binding introuvable" }
        val now = System.currentTimeMillis()
        list[idx] = profile.copy(bindings = remaining, updatedAt = now)
        withContext(Dispatchers.IO) { writeUnlocked(list) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
    }

    suspend fun delete(id: String) = mutex.withLock {
        val remaining = withContext(Dispatchers.IO) { readUnlocked() }.filterNot { it.id == id }
        withContext(Dispatchers.IO) { writeUnlocked(remaining) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
        if (_activeProfileId.value == id) {
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
            _activeProfileId.value = null
        }
    }

    /** Touch lastUsed du profil actif (auth via binding réussie). */
    suspend fun touchActiveIfAny() {
        val id = _activeProfileId.value ?: return
        touchLastUsed(id)
    }

    suspend fun touchLastUsed(id: String) = mutex.withLock {
        mutateProfileUnlocked(id) { p ->
            val now = System.currentTimeMillis()
            p.copy(lastUsedAt = now, updatedAt = now)
        }
    }

    /**
     * Bindings du profil dont le vaultId n’existe plus dans [knownVaultIds].
     */
    fun brokenVaultBindings(profileId: String, knownVaultIds: Set<String>): List<KeyBinding> {
        val p = getById(profileId) ?: return emptyList()
        return KeyProfileRules.findBrokenVaultBindings(p.bindings, knownVaultIds)
    }

    private suspend fun mutateProfileUnlocked(
        id: String,
        transform: (KeyProfile) -> KeyProfile,
    ) {
        val list = withContext(Dispatchers.IO) { readUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        require(idx >= 0) { "profil introuvable" }
        val now = System.currentTimeMillis()
        list[idx] = transform(list[idx]).copy(updatedAt = now)
        withContext(Dispatchers.IO) { writeUnlocked(list) }
        _profiles.value = withContext(Dispatchers.IO) { readSortedUnlocked() }
    }

    private fun readUnlocked(): List<KeyProfile> {
        val file = metaFile
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<KeyProfilesFile>(file.readText()).profiles
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readSortedUnlocked(): List<KeyProfile> =
        readUnlocked().sortedWith(
            compareByDescending<KeyProfile> { it.lastUsedAt ?: 0L }
                .thenByDescending { it.updatedAt },
        )

    private fun writeUnlocked(profiles: List<KeyProfile>) {
        metaFile.writeText(json.encodeToString(KeyProfilesFile(profiles)))
    }

    companion object {
        const val META_FILE = "key_profiles_meta.json"
        const val PREFS_NAME = "key_profiles_prefs"
        const val KEY_ACTIVE_ID = "active_profile_id"
    }
}
