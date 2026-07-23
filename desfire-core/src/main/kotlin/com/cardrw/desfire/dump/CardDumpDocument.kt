package com.cardrw.desfire.dump

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Dump carte v2 (CDC §8) — structure + données **lisibles**, **sans secrets**.
 * Les clés ne sont jamais sérialisées ici (option « include secrets » = tranche ultérieure).
 */
@Serializable
data class CardDumpDocument(
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("integrity_sha256") val integritySha256: String? = null,
    /** Avertissement honnête sur la couverture du dump. */
    val note: String,
    val card: DumpCardSection,
    val structure: DumpStructureSection,
    val data: DumpDataSection,
    /** Toujours vide en v1 labo (pas d’export de matériaux de clés). */
    val secrets: DumpSecretsSection = DumpSecretsSection(),
) {
    companion object {
        const val FORMAT_VERSION: Int = 2
    }
}

@Serializable
data class DumpCardSection(
    @SerialName("type_label") val typeLabel: String,
    @SerialName("uid_tag") val uidTag: String?,
    @SerialName("uid_kind") val uidKind: String,
    @SerialName("uid_real") val uidReal: String? = null,
    @SerialName("free_memory_bytes") val freeMemoryBytes: Int? = null,
    @SerialName("software_version") val softwareVersion: String? = null,
    @SerialName("production") val production: String? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class DumpStructureSection(
    val applications: List<DumpApplication>,
    @SerialName("unread_files") val unreadFiles: List<String> = emptyList(),
)

@Serializable
data class DumpApplication(
    val aid: String,
    @SerialName("friendly_name") val friendlyName: String? = null,
    @SerialName("key_settings") val keySettings: DumpKeySettings? = null,
    val files: List<DumpFileMeta> = emptyList(),
    val notes: List<String> = emptyList(),
    @SerialName("explored") val explored: Boolean = false,
)

@Serializable
data class DumpKeySettings(
    @SerialName("settings_raw") val settingsRaw: Int,
    @SerialName("max_keys") val maxKeys: Int,
    @SerialName("free_directory_list") val freeDirectoryList: Boolean? = null,
)

@Serializable
data class DumpFileMeta(
    @SerialName("file_no") val fileNo: Int,
    val type: String,
    @SerialName("comm_mode") val commMode: String,
    @SerialName("size_bytes") val sizeBytes: Int? = null,
    /** Libellé Proxmark-like (affichage). */
    @SerialName("access_rights") val accessRights: String,
    /**
     * MDAR logique 16 bits (Read/Write/RW/Change) — pour restore.
     * Absent des dumps anciens → Free `0xEEEE` côté planner.
     */
    @SerialName("access_rights_raw") val accessRightsRaw: Int? = null,
    /** Wire comm mode 0x00/01/03 — pour restore. */
    @SerialName("comm_mode_wire") val commModeWire: Int? = null,
    @SerialName("data_status") val dataStatus: String,
)

@Serializable
data class DumpDataSection(
    /** Clé = « AID/fileNo » ex. F00102/0 → hex compact uppercase. */
    val files: Map<String, String> = emptyMap(),
)

@Serializable
data class DumpSecretsSection(
    /** Toujours false en export moniteur v1. */
    @SerialName("keys_included") val keysIncluded: Boolean = false,
    val keys: Map<String, String> = emptyMap(),
)
