package com.cardrw.desfire.dump

import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.util.Hex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Construit un [CardDumpDocument] depuis l’état moniteur (identité + explores).
 * **Aucun secret** (matériau de clé) n’est inclus.
 */
object CardDumpBuilder {

    private val jsonPretty = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val isoInstant: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    fun build(
        identity: CardIdentity,
        exploreByAid: Map<String, ApplicationExploreResult>,
        realUidHex: String? = null,
        appVersion: String = "unknown",
        friendlyName: (String) -> String? = { null },
        createdAt: Instant = Instant.now(),
    ): CardDumpDocument {
        val apps = identity.applications.map { aid ->
            val hex = aid.hex.uppercase()
            val explore = exploreByAid[hex] ?: exploreByAid[aid.hex]
            toDumpApp(hex, explore, friendlyName(hex))
        }
        // Explores d’apps absentes de la liste (cache) — rare
        val listed = apps.map { it.aid }.toSet()
        val extras = exploreByAid.entries
            .filter { it.key.uppercase() !in listed && it.key != "000000" }
            .map { (k, ex) -> toDumpApp(k.uppercase(), ex, friendlyName(k)) }

        val allApps = apps + extras
        val unread = mutableListOf<String>()
        val dataFiles = linkedMapOf<String, String>()
        for (app in allApps) {
            val explore = exploreByAid[app.aid] ?: continue
            for (node in explore.files) {
                val key = "${app.aid}/${node.fileNo}"
                when {
                    node.dataHex != null -> dataFiles[key] = node.dataHex.uppercase()
                    node.dataError != null -> unread += "$key (erreur: ${node.dataError})"
                    else -> unread += "$key (non lu)"
                }
            }
        }

        val note = buildString {
            append("Dump moniteur CardRW — uniquement ce qui était lisible avec les clés disponibles. ")
            append("Aucun secret (matériau de clé) n’est inclus. ")
            if (unread.isEmpty()) {
                append("Tous les fichiers explorés ont un contenu ou un statut final.")
            } else {
                append("Fichiers non lus / incomplets : ${unread.joinToString("; ")}.")
            }
        }

        val draft = CardDumpDocument(
            createdAt = isoInstant.format(createdAt),
            appVersion = appVersion,
            integritySha256 = null,
            note = note,
            card = DumpCardSection(
                typeLabel = identity.typeLabel,
                uidTag = identity.uidFromTag?.let { Hex.encode(it) },
                uidKind = identity.uidKind.name,
                uidReal = realUidHex?.uppercase(),
                freeMemoryBytes = identity.freeMemoryBytes,
                softwareVersion = identity.version?.softwareVersionLabel,
                production = identity.version?.productionLabel,
                notes = identity.rawNotes,
            ),
            structure = DumpStructureSection(
                applications = allApps,
                unreadFiles = unread,
            ),
            data = DumpDataSection(files = dataFiles),
            secrets = DumpSecretsSection(keysIncluded = false),
        )
        val hash = sha256Hex(canonicalJsonWithoutHash(draft))
        return draft.copy(integritySha256 = hash)
    }

    fun toPrettyJson(doc: CardDumpDocument): String = jsonPretty.encodeToString(doc)

    fun toHumanText(doc: CardDumpDocument): String = buildString {
        appendLine("CardRW dump v${doc.formatVersion}")
        appendLine("created: ${doc.createdAt}")
        appendLine("app: ${doc.appVersion}")
        appendLine("sha256: ${doc.integritySha256 ?: "—"}")
        appendLine()
        appendLine("=== CARTE ===")
        appendLine("type: ${doc.card.typeLabel}")
        appendLine("uid tag: ${doc.card.uidTag ?: "—"} (${doc.card.uidKind})")
        if (doc.card.uidReal != null) appendLine("uid réel: ${doc.card.uidReal}")
        appendLine("SW: ${doc.card.softwareVersion ?: "—"} · prod: ${doc.card.production ?: "—"}")
        appendLine("mémoire libre: ${doc.card.freeMemoryBytes?.let { "$it o" } ?: "—"}")
        appendLine()
        appendLine("=== STRUCTURE ===")
        for (app in doc.structure.applications) {
            appendLine("AID ${app.aid}${app.friendlyName?.let { " ($it)" } ?: ""}" +
                if (app.explored) "" else " [non exploré]")
            app.keySettings?.let { ks ->
                appendLine("  key settings 0x${ks.settingsRaw.toString(16)} · maxKeys ${ks.maxKeys}")
            }
            for (f in app.files) {
                appendLine(
                    "  F${f.fileNo} ${f.type} ${f.commMode} " +
                        "${f.sizeBytes?.let { "$it o" } ?: "?"} · ${f.accessRights} · ${f.dataStatus}",
                )
            }
            if (app.files.isEmpty() && app.explored) appendLine("  (aucun fichier)")
        }
        appendLine()
        appendLine("=== DONNÉES (hex) ===")
        if (doc.data.files.isEmpty()) {
            appendLine("(aucune)")
        } else {
            for ((k, v) in doc.data.files) {
                appendLine("$k (${v.length / 2} o): $v")
            }
        }
        appendLine()
        appendLine("=== NOTE ===")
        appendLine(doc.note)
        appendLine("secrets inclus: ${doc.secrets.keysIncluded}")
    }

    private fun toDumpApp(
        aidHex: String,
        explore: ApplicationExploreResult?,
        friendly: String?,
    ): DumpApplication {
        if (explore == null) {
            return DumpApplication(
                aid = aidHex,
                friendlyName = friendly,
                explored = false,
            )
        }
        val ks = explore.keySettings?.let {
            DumpKeySettings(
                settingsRaw = it.settingsRaw,
                maxKeys = it.maxKeys,
                freeDirectoryList = it.bits.freeDirectoryListWithoutMaster,
            )
        }
        return DumpApplication(
            aid = aidHex,
            friendlyName = friendly,
            keySettings = ks,
            files = explore.files.map { toDumpFile(it) },
            notes = explore.notes,
            explored = true,
        )
    }

    private fun toDumpFile(node: FileNode): DumpFileMeta {
        val s = node.settings
        val ar = s.accessRights
        val status = when {
            node.dataHex != null -> "read"
            node.dataError != null -> "error"
            ar.isReadNever -> "never"
            ar.isReadFree -> "free_unread"
            else -> "unread"
        }
        return DumpFileMeta(
            fileNo = node.fileNo,
            type = s.fileType.label,
            commMode = s.commMode.label,
            sizeBytes = s.sizeBytes,
            accessRights = ar.compactLabel,
            dataStatus = status,
        )
    }

    private fun canonicalJsonWithoutHash(doc: CardDumpDocument): String {
        val without = doc.copy(integritySha256 = null)
        return Json {
            encodeDefaults = true
            prettyPrint = false
        }.encodeToString(without)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return Hex.encode(digest)
    }
}
