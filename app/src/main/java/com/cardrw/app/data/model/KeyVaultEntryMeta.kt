package com.cardrw.app.data.model

import kotlinx.serialization.Serializable

/**
 * Métadonnées coffre (pas de secret) — [docs/UX_COFFRE_CLES.md].
 * Octets AES via SecretStore alias `vault.<id>`.
 */
@Serializable
data class KeyVaultEntryMeta(
    val id: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)

@Serializable
data class KeyVaultMetaFile(
    val entries: List<KeyVaultEntryMeta> = emptyList(),
)
