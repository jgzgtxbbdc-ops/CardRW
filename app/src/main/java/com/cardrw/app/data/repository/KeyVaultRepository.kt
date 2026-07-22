package com.cardrw.app.data.repository

import android.content.Context
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.model.KeyVaultMetaFile
import com.cardrw.app.security.SecretStore
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
 * Coffre-fort : métadonnées JSON (clair) + octets dans [SecretStore] (`vault.<id>`).
 */
@Singleton
class KeyVaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretStore: SecretStore,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val metaFile: File
        get() = File(context.filesDir, META_FILE)

    private val _entries = MutableStateFlow<List<KeyVaultEntryMeta>>(emptyList())
    val entries: StateFlow<List<KeyVaultEntryMeta>> = _entries.asStateFlow()

    suspend fun load() {
        mutex.withLock {
            _entries.value = withContext(Dispatchers.IO) { readMetaSortedUnlocked() }
        }
    }

    fun nextDefaultName(): String =
        KeyVaultNaming.nextDefaultName(_entries.value.map { it.displayName })

    /**
     * Crée une entrée. [key16] doit faire 16 octets.
     * @return id
     */
    suspend fun create(displayName: String, key16: ByteArray): String = mutex.withLock {
        require(key16.size == KEY_SIZE) { "clé AES-128 = 16 octets, got ${key16.size}" }
        val name = displayName.trim()
        require(KeyVaultNaming.isValidDisplayName(name)) { "nom invalide" }
        require(!KeyVaultNaming.looksLikeKeyHex(name)) {
            "le nom ressemble à une clé hex — utilise le champ matériau"
        }
        val existing = withContext(Dispatchers.IO) { readMetaUnlocked() }
        require(
            existing.none {
                KeyVaultNaming.normalizeForCompare(it.displayName) ==
                    KeyVaultNaming.normalizeForCompare(name)
            },
        ) { "nom déjà utilisé" }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        secretStore.put(aliasFor(id), key16.copyOf())
        val entry = KeyVaultEntryMeta(
            id = id,
            displayName = name,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        withContext(Dispatchers.IO) { writeMetaUnlocked(existing + entry) }
        _entries.value = withContext(Dispatchers.IO) { readMetaSortedUnlocked() }
        id
    }

    suspend fun rename(id: String, newDisplayName: String) = mutex.withLock {
        val name = newDisplayName.trim()
        require(KeyVaultNaming.isValidDisplayName(name)) { "nom invalide" }
        require(!KeyVaultNaming.looksLikeKeyHex(name)) {
            "le nom ressemble à une clé hex — utilise le champ matériau"
        }
        val list = withContext(Dispatchers.IO) { readMetaUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        require(idx >= 0) { "entrée introuvable" }
        val conflict = list.any {
            it.id != id &&
                KeyVaultNaming.normalizeForCompare(it.displayName) ==
                KeyVaultNaming.normalizeForCompare(name)
        }
        require(!conflict) { "nom déjà utilisé" }
        val now = System.currentTimeMillis()
        list[idx] = list[idx].copy(displayName = name, updatedAt = now)
        withContext(Dispatchers.IO) { writeMetaUnlocked(list) }
        _entries.value = withContext(Dispatchers.IO) { readMetaSortedUnlocked() }
    }

    suspend fun delete(id: String) = mutex.withLock {
        secretStore.delete(aliasFor(id))
        val remaining = withContext(Dispatchers.IO) { readMetaUnlocked() }.filterNot { it.id == id }
        withContext(Dispatchers.IO) { writeMetaUnlocked(remaining) }
        _entries.value = withContext(Dispatchers.IO) { readMetaSortedUnlocked() }
    }

    /** Charge le matériau et met à jour [lastUsedAt]. */
    suspend fun material(id: String): ByteArray = mutex.withLock {
        val bytes = secretStore.get(aliasFor(id))
            ?: throw IllegalStateException("secret introuvable pour $id")
        require(bytes.size == KEY_SIZE) { "secret corrompu (${bytes.size} o)" }
        val list = withContext(Dispatchers.IO) { readMetaUnlocked() }.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val now = System.currentTimeMillis()
            list[idx] = list[idx].copy(lastUsedAt = now, updatedAt = now)
            withContext(Dispatchers.IO) { writeMetaUnlocked(list) }
            _entries.value = withContext(Dispatchers.IO) { readMetaSortedUnlocked() }
        }
        bytes.copyOf()
    }

    private fun aliasFor(id: String) = "$ALIAS_PREFIX$id"

    private fun readMetaUnlocked(): List<KeyVaultEntryMeta> {
        val file = metaFile
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<KeyVaultMetaFile>(file.readText()).entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readMetaSortedUnlocked(): List<KeyVaultEntryMeta> =
        readMetaUnlocked().sortedWith(
            compareByDescending<KeyVaultEntryMeta> { it.lastUsedAt ?: 0L }
                .thenByDescending { it.updatedAt },
        )

    private fun writeMetaUnlocked(entries: List<KeyVaultEntryMeta>) {
        metaFile.writeText(json.encodeToString(KeyVaultMetaFile(entries)))
    }

    companion object {
        const val META_FILE = "key_vault_meta.json"
        const val ALIAS_PREFIX = "vault."
        const val KEY_SIZE = 16
    }
}
