package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.FileType
import com.cardrw.desfire.model.readKeyCandidates

/**
 * Dry-run de couverture dump (P4) — **sans NFC**.
 *
 * Pour chaque app / fichier du cache moniteur : Free | déjà lu | Never |
 * matériau profil/mémorisé/usine | manque.
 */
object DumpCoveragePlanner {

    enum class Status {
        READ,
        FREE,
        NEVER,
        BINDING,
        FACTORY,
        MISSING,
        UNKNOWN,
        SKIPPED,
    }

    data class Row(
        val aidHex: String,
        val fileNo: Int?,
        val status: Status,
        val detail: String,
    ) {
        val label: String
            get() = if (fileNo == null) {
                if (aidHex == "000000") "PICC · structure" else "$aidHex · directory"
            } else {
                "$aidHex/F$fileNo"
            }
    }

    data class Plan(
        val profileName: String?,
        val factoryFallback: Boolean,
        val rows: List<Row>,
    ) {
        val missingCount: Int get() = rows.count { it.status == Status.MISSING || it.status == Status.UNKNOWN }
        val expectedReadable: Int get() = rows.count {
            it.status == Status.READ ||
                it.status == Status.FREE ||
                it.status == Status.BINDING ||
                it.status == Status.FACTORY
        }
        val neverCount: Int get() = rows.count { it.status == Status.NEVER }

        fun toLines(): List<String> = rows.map { row ->
            val tag = when (row.status) {
                Status.READ -> "lu"
                Status.FREE -> "free"
                Status.NEVER -> "never"
                Status.BINDING -> "binding"
                Status.FACTORY -> "usine"
                Status.MISSING -> "manque matériau"
                Status.UNKNOWN -> "non exploré"
                Status.SKIPPED -> "hors lecture"
            }
            "${row.label} · $tag · ${row.detail}"
        }

        fun summaryLine(): String {
            val profile = profileName?.let { "Profil « $it »" } ?: "Sans profil (labo usine)"
            return "$profile · ${expectedReadable} lisible(s) estimé(s) · " +
                "${missingCount} lacune(s)" +
                if (neverCount > 0) " · $neverCount never" else ""
        }
    }

    fun plan(
        applicationAids: List<String>,
        exploreByAid: Map<String, ApplicationExploreResult>,
        profile: KeyProfile?,
        rememberedByAid: Map<String, Set<Int>> = emptyMap(),
        knownVaultIds: Set<String> = emptySet(),
        includePicc: Boolean = true,
    ): Plan {
        val rows = mutableListOf<Row>()
        val aids = buildList {
            if (includePicc) add("000000")
            addAll(applicationAids.map { it.replace(" ", "").uppercase() }.filter { it != "000000" })
        }.distinct()

        for (aid in aids) {
            val explore = exploreByAid[aid] ?: exploreByAid[aid.uppercase()]
            rows += structureRow(
                aidHex = aid,
                explore = explore,
                profile = profile,
                remembered = rememberedByAid[aid].orEmpty(),
                knownVaultIds = knownVaultIds,
            )
            if (explore != null) {
                for (node in explore.files) {
                    rows += fileRow(
                        aidHex = aid,
                        nodeFileNo = node.fileNo,
                        fileType = node.settings.fileType,
                        dataHex = node.dataHex,
                        dataError = node.dataError,
                        isReadFree = node.settings.accessRights.isReadFree,
                        isReadNever = node.settings.accessRights.isReadNever,
                        readSlots = node.settings.accessRights.readKeyCandidates().map { it.keyNo },
                        profile = profile,
                        remembered = rememberedByAid[aid].orEmpty(),
                        knownVaultIds = knownVaultIds,
                    )
                }
            }
        }
        return Plan(
            profileName = profile?.displayName,
            factoryFallback = KeyMaterialResolver.allowsFactoryFallback(profile),
            rows = rows,
        )
    }

    private fun structureRow(
        aidHex: String,
        explore: ApplicationExploreResult?,
        profile: KeyProfile?,
        remembered: Set<Int>,
        knownVaultIds: Set<String>,
    ): Row {
        val known = explore != null &&
            (explore.keySettings != null || explore.files.isNotEmpty() || explore.structureFromCache)
        if (known) {
            val freeList = explore?.keySettings?.bits?.freeDirectoryListWithoutMaster
            val detail = when (freeList) {
                true -> "free-list ON"
                false -> "free-list OFF (maître 0)"
                null -> "structure en cache"
            }
            return Row(aidHex, null, Status.READ, detail)
        }
        val material = slotMaterial(aidHex, 0, profile, remembered, knownVaultIds)
        return when (material) {
            is SlotMaterial.Ready -> Row(
                aidHex,
                null,
                if (material.kind == SlotMaterial.Kind.FACTORY) Status.FACTORY else Status.BINDING,
                "directory via k0 (${material.label})",
            )
            is SlotMaterial.Missing -> Row(
                aidHex,
                null,
                Status.UNKNOWN,
                "directory : ${material.reason}",
            )
        }
    }

    private fun fileRow(
        aidHex: String,
        nodeFileNo: Int,
        fileType: FileType,
        dataHex: String?,
        dataError: String?,
        isReadFree: Boolean,
        isReadNever: Boolean,
        readSlots: List<Int>,
        profile: KeyProfile?,
        remembered: Set<Int>,
        knownVaultIds: Set<String>,
    ): Row {
        if (dataHex != null) {
            return Row(aidHex, nodeFileNo, Status.READ, "${dataHex.length / 2} o")
        }
        if (fileType != FileType.STANDARD) {
            return Row(aidHex, nodeFileNo, Status.SKIPPED, fileType.label)
        }
        if (isReadNever && readSlots.isEmpty()) {
            return Row(aidHex, nodeFileNo, Status.NEVER, "Read Never")
        }
        if (isReadFree) {
            return Row(aidHex, nodeFileNo, Status.FREE, dataError ?: "lisible sans clé")
        }
        if (readSlots.isEmpty()) {
            return Row(aidHex, nodeFileNo, Status.NEVER, "aucun slot lecture")
        }
        for (slot in readSlots) {
            when (val m = slotMaterial(aidHex, slot, profile, remembered, knownVaultIds)) {
                is SlotMaterial.Ready -> {
                    val status = if (m.kind == SlotMaterial.Kind.FACTORY) Status.FACTORY else Status.BINDING
                    return Row(aidHex, nodeFileNo, status, "k$slot · ${m.label}")
                }
                is SlotMaterial.Missing -> { /* try next candidate */ }
            }
        }
        val tried = readSlots.joinToString(",") { "k$it" }
        return Row(aidHex, nodeFileNo, Status.MISSING, "besoin $tried")
    }

    private sealed class SlotMaterial {
        enum class Kind { REMEMBERED, PROFILE, FACTORY }

        data class Ready(val kind: Kind, val label: String) : SlotMaterial()
        data class Missing(val reason: String) : SlotMaterial()
    }

    private fun slotMaterial(
        aidHex: String,
        keyNo: Int,
        profile: KeyProfile?,
        remembered: Set<Int>,
        knownVaultIds: Set<String>,
    ): SlotMaterial {
        if (keyNo in remembered) {
            return SlotMaterial.Ready(SlotMaterial.Kind.REMEMBERED, "mémorisée")
        }
        val scope: BindingScope = KeyMaterialResolver.scopeForAid(aidHex)
        if (profile != null) {
            val binding = KeyMaterialResolver.findBinding(profile, scope, keyNo)
            if (binding != null) {
                return when (val ref = binding.materialRef) {
                    is MaterialRef.FactoryZero ->
                        SlotMaterial.Ready(SlotMaterial.Kind.FACTORY, "usine (profil)")
                    is MaterialRef.VaultEntry -> {
                        if (ref.vaultId in knownVaultIds) {
                            SlotMaterial.Ready(SlotMaterial.Kind.PROFILE, "coffre")
                        } else {
                            SlotMaterial.Missing("entrée coffre manquante")
                        }
                    }
                }
            }
            if (profile.allowFactoryFallback) {
                return SlotMaterial.Ready(SlotMaterial.Kind.FACTORY, "usine (fallback)")
            }
            return SlotMaterial.Missing("pas de binding k$keyNo")
        }
        return SlotMaterial.Ready(SlotMaterial.Kind.FACTORY, "usine (labo)")
    }
}
