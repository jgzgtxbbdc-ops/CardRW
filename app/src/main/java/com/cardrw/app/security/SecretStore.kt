package com.cardrw.app.security

/**
 * Stockage local des secrets (clés, dumps « avec secrets »).
 *
 * CDC §6.2 :
 * - v0–v0.5 : interface + impl. mémoire / no-op (peu de secrets persistés)
 * - v1 : AES-GCM + Android Keystore (`EncryptedFile` ou équivalent)
 * - SQLCipher : non par défaut
 * - Exclus de l’auto-backup Android
 */
interface SecretStore {
    /** Écrit un blob opaque chiffré (ou en clair en debug local uniquement). */
    suspend fun put(alias: String, plaintext: ByteArray)

    /** @return null si l’alias n’existe pas */
    suspend fun get(alias: String): ByteArray?

    suspend fun delete(alias: String)

    suspend fun contains(alias: String): Boolean

    suspend fun listAliases(): List<String>

    /** Purge totale des secrets sur l’appareil. */
    suspend fun clearAll()
}

/**
 * Implémentation volatile v0 — ne survit pas au process.
 * Utile pour DI + tests ; **jamais** pour des clés réelles en production.
 */
class InMemorySecretStore : SecretStore {
    private val map = LinkedHashMap<String, ByteArray>()

    override suspend fun put(alias: String, plaintext: ByteArray) {
        map[alias] = plaintext.copyOf()
    }

    override suspend fun get(alias: String): ByteArray? = map[alias]?.copyOf()

    override suspend fun delete(alias: String) {
        map.remove(alias)
    }

    override suspend fun contains(alias: String): Boolean = map.containsKey(alias)

    override suspend fun listAliases(): List<String> = map.keys.toList()

    override suspend fun clearAll() {
        map.clear()
    }
}
