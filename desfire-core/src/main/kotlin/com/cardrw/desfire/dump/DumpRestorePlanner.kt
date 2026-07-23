package com.cardrw.desfire.dump

import com.cardrw.desfire.model.CommMode

/**
 * Plan de restauration depuis un dump moniteur (CDC §8.5) — **sans secrets**.
 *
 * Labo : suppose master PICC / apps en AES usine (ou déjà auth côté client).
 * Les droits non exportés en raw → Free `0xEEEE` ; comm non reconnue → FULL.
 */
object DumpRestorePlanner {

    enum class Mode {
        /** Create app + fichiers + WriteData des contenus lus. */
        STRUCTURE_AND_DATA,
        /** Create app + fichiers seulement. */
        STRUCTURE_ONLY,
        /** WriteData seulement (fichiers déjà présents). */
        DATA_ONLY,
    }

    sealed class Step {
        abstract val label: String

        data class FormatPicc(override val label: String = "FormatPICC (efface toutes les apps)") : Step()
        data class CreateApplication(
            val aidHex: String,
            val keySettings: Int,
            val maxKeys: Int,
            override val label: String,
        ) : Step()
        data class SelectApplication(val aidHex: String, override val label: String) : Step()
        data class CreateStdDataFile(
            val aidHex: String,
            val fileNo: Int,
            val sizeBytes: Int,
            val commSettings: Int,
            val accessRights: Int,
            override val label: String,
        ) : Step()
        data class WriteData(
            val aidHex: String,
            val fileNo: Int,
            val dataHex: String,
            /** MDAR logique 16 bits (pour auth W/RW). */
            val accessRights: Int,
            /** Wire comm 0x00/01/03 — effectif si session a la clé W. */
            val commSettings: Int,
            override val label: String,
        ) : Step()
        data class Skip(override val label: String) : Step()
    }

    data class Plan(
        val mode: Mode,
        val formatFirst: Boolean,
        val steps: List<Step>,
        val warnings: List<String>,
    ) {
        val actionableCount: Int get() = steps.count { it !is Step.Skip }
    }

    fun plan(
        doc: CardDumpDocument,
        mode: Mode = Mode.STRUCTURE_AND_DATA,
        formatFirst: Boolean = false,
    ): Plan {
        val steps = mutableListOf<Step>()
        val warnings = mutableListOf<String>()

        if (doc.secrets.keysIncluded || doc.secrets.keys.isNotEmpty()) {
            warnings += "Ce dump déclare des secrets — CardRW labo n’applique pas les ChangeKey depuis le dump."
        }
        if (doc.structure.unreadFiles.isNotEmpty()) {
            warnings += "${doc.structure.unreadFiles.size} fichier(s) non lus dans le dump — pas de Write pour eux."
        }
        warnings += "Prérequis labo : master PICC AES authentifiée (souvent 00…00). " +
            "Les apps cibles sont créées avec settings ouverts ; droits fichier Free si raw absent."

        if (formatFirst && mode != Mode.DATA_ONLY) {
            steps += Step.FormatPicc()
        }

        val apps = doc.structure.applications.filter { it.aid.uppercase() != "000000" }

        if (mode == Mode.STRUCTURE_AND_DATA || mode == Mode.STRUCTURE_ONLY) {
            for (app in apps) {
                val aid = app.aid.uppercase().replace(" ", "")
                if (aid.length != 6) {
                    steps += Step.Skip("AID invalide « ${app.aid} » — ignoré")
                    continue
                }
                if (!app.explored && app.files.isEmpty()) {
                    steps += Step.Skip("AID $aid non exploré dans le dump — CreateApplication seulement")
                }
                val maxKeys = (app.keySettings?.maxKeys ?: 3).coerceIn(1, 14)
                val settings = app.keySettings?.settingsRaw ?: 0x0F
                steps += Step.CreateApplication(
                    aidHex = aid,
                    keySettings = settings,
                    maxKeys = maxKeys,
                    label = "CreateApplication $aid (maxKeys=$maxKeys, settings=0x${settings.toString(16)})",
                )
                steps += Step.SelectApplication(
                    aidHex = aid,
                    label = "SelectApplication $aid (+ auth master app si besoin)",
                )
                for (file in app.files) {
                    if (!file.type.contains("Std", ignoreCase = true) &&
                        !file.type.contains("Standard", ignoreCase = true)
                    ) {
                        steps += Step.Skip(
                            "F$aid/${file.fileNo} type « ${file.type} » — Create Std seulement (v1.2 labo)",
                        )
                        continue
                    }
                    val size = file.sizeBytes ?: 16
                    val comm = parseCommWire(file.commMode, file.commModeWire)
                    val rights = file.accessRightsRaw
                        ?: parseRightsOrFree(file.accessRights)
                    steps += Step.CreateStdDataFile(
                        aidHex = aid,
                        fileNo = file.fileNo,
                        sizeBytes = size,
                        commSettings = comm,
                        accessRights = rights,
                        label = "CreateStdDataFile $aid/F${file.fileNo} " +
                            "(${size}B, comm=0x${comm.toString(16)}, ar=0x${rights.toString(16)})",
                    )
                }
            }
        }

        if (mode == Mode.STRUCTURE_AND_DATA || mode == Mode.DATA_ONLY) {
            val fileMetaByKey = buildMap {
                for (app in apps) {
                    val a = app.aid.uppercase().replace(" ", "")
                    for (f in app.files) {
                        put("$a/${f.fileNo}", f)
                    }
                }
            }
            for ((key, hex) in doc.data.files) {
                val parts = key.split("/")
                if (parts.size != 2) {
                    steps += Step.Skip("Clé data « $key » invalide")
                    continue
                }
                val aid = parts[0].uppercase().replace(" ", "")
                val fileNo = parts[1].toIntOrNull()
                if (fileNo == null || aid.length != 6) {
                    steps += Step.Skip("Clé data « $key » invalide")
                    continue
                }
                val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
                if (clean.isEmpty()) {
                    steps += Step.Skip("Data vide pour $key")
                    continue
                }
                val meta = fileMetaByKey["$aid/$fileNo"]
                val rights = meta?.accessRightsRaw
                    ?: meta?.accessRights?.let { parseRightsOrFree(it) }
                    ?: 0xEEEE
                val comm = parseCommWire(
                    meta?.commMode ?: "FULL",
                    meta?.commModeWire,
                )
                // Auth W/RW (pas master 0 si W=2) — select inclus dans ensureWrite
                steps += Step.WriteData(
                    aidHex = aid,
                    fileNo = fileNo,
                    dataHex = clean.uppercase(),
                    accessRights = rights,
                    commSettings = comm,
                    label = "WriteData $aid/F$fileNo (${clean.length / 2} B, " +
                        "ar=0x${rights.toString(16)}, need W/RW key)",
                )
            }
        }

        if (steps.none { it !is Step.Skip }) {
            warnings += "Aucune opération actionnable — dump vide ou mode trop restrictif."
        }

        return Plan(mode = mode, formatFirst = formatFirst, steps = steps, warnings = warnings)
    }

    private fun parseCommWire(label: String, wire: Int?): Int {
        if (wire != null) return wire and 0x03
        return when (label.trim().uppercase()) {
            "PLAIN", "PLAIN TEXT", "0" -> CommMode.PLAIN.wire
            "MAC", "MACED" -> CommMode.MACED.wire
            "FULL", "FULLY ENCIPHERED" -> CommMode.FULL.wire
            else -> CommMode.FULL.wire
        }
    }

    /**
     * Parse Proxmark-like `r:1 w:2 rw:2 ch:0` / free / never → MDAR 16 bits.
     * Sinon Free labo `0xEEEE`.
     */
    internal fun parseRightsOrFree(label: String): Int {
        val map = linkedMapOf<String, Int>()
        val re = Regex("""(r|w|rw|ch)\s*:\s*(free|never|\d{1,2})""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(label)) {
            val slot = m.groupValues[1].lowercase()
            val v = when (val raw = m.groupValues[2].lowercase()) {
                "free" -> 0x0E
                "never" -> 0x0F
                else -> raw.toInt().coerceIn(0, 15)
            }
            map[slot] = v
        }
        if (map.isEmpty()) return 0xEEEE
        val r = map["r"] ?: 0x0E
        val w = map["w"] ?: 0x0E
        val rw = map["rw"] ?: 0x0E
        val ch = map["ch"] ?: 0x0E
        return ((r and 0x0F) shl 12) or ((w and 0x0F) shl 8) or
            ((rw and 0x0F) shl 4) or (ch and 0x0F)
    }
}
