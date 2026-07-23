package com.cardrw.desfire.model

/**
 * Mode de communication d’un fichier DESFire (2 bits bas de FileSettings).
 * CDC §3.2 / §5.2.
 */
/** [label] : terme technique court (EN) pour moniteur / dumps. */
enum class CommMode(val wire: Int, val label: String) {
    PLAIN(0x00, "PLAIN"),
    MACED(0x01, "MAC"),
    FULL(0x03, "FULL"),
    ;

    companion object {
        fun fromWire(raw: Int): CommMode {
            // Seuls les 2 bits bas comptent pour le mode ; bit 1 parfois set → full
            return when (raw and 0x03) {
                0x00 -> PLAIN
                0x01 -> MACED
                0x03 -> FULL
                0x02 -> FULL // rarement utilisé ; traiter comme full
                else -> PLAIN
            }
        }
    }
}

/** [label] : court EN (Std / Backup / …) pour moniteur compact. */
enum class FileType(val code: Int, val label: String) {
    STANDARD(0x00, "Std"),
    BACKUP(0x01, "Backup"),
    VALUE(0x02, "Value"),
    LINEAR_RECORDS(0x03, "LinRec"),
    CYCLIC_RECORDS(0x04, "CycRec"),
    UNKNOWN(-1, "?"),
    ;

    companion object {
        fun fromCode(code: Int): FileType =
            entries.firstOrNull { it.code == (code and 0xFF) } ?: UNKNOWN
    }
}

/**
 * Droits d’accès fichier — n° de clé 0–13, Free (0xE / 14), Never (0xF / 15).
 */
data class AccessRights(
    val read: Int,
    val write: Int,
    val readWrite: Int,
    val change: Int,
    val raw: Int,
) {
    /**
     * Valeur isolée (messages / dumps) : free / never / k0…k13.
     * Le n° de slot garde le préfixe `k` pour coller aux CTA « Auth k1 ».
     */
    fun describe(key: Int): String = when (key and 0x0F) {
        0x0E -> "free"
        0x0F -> "never"
        else -> "k${key and 0x0F}"
    }

    /**
     * Valeur Proxmark (`hf mfdes`) pour un nibble de droits :
     * free / never / 0…13 (chiffre nu, comme `r: free w: key 0x00` → on compresse en `r:free ch:0`).
     */
    fun describeProx(key: Int): String = when (key and 0x0F) {
        0x0E -> "free"
        0x0F -> "never"
        else -> "${key and 0x0F}"
    }

    val readLabel: String get() = describe(read)
    val writeLabel: String get() = describe(write)
    val readWriteLabel: String get() = describe(readWrite)
    val changeLabel: String get() = describe(change)

    /**
     * Ligne moniteur style Proxmark3 : `r:1 w:2 rw:2 ch:0`
     * (ordre NXP / freefare MDAR : read, write, read_write, change).
     */
    val compactLabel: String
        get() = "r:${describeProx(read)} w:${describeProx(write)} " +
            "rw:${describeProx(readWrite)} ch:${describeProx(change)}"

    val isReadFree: Boolean get() = (read and 0x0F) == 0x0E
    val isReadNever: Boolean get() = (read and 0x0F) == 0x0F
    val isWriteFree: Boolean get() = (write and 0x0F) == 0x0E || (readWrite and 0x0F) == 0x0E

    /**
     * True si [authKeyNo] peut lire : Free, ou clé = Read, ou clé = ReadWrite.
     * (La clé maître app n’a **pas** de passe-droit implicite sur les fichiers.)
     */
    fun canReadWith(authKeyNo: Int?): Boolean {
        val r = read and 0x0F
        val rw = readWrite and 0x0F
        if (r == 0x0E) return true // Free
        if (r == 0x0F && rw == 0x0F) return false // Never both
        if (authKeyNo == null) return false
        val k = authKeyNo and 0x0F
        return k == r || k == rw
    }

    /**
     * True si la session a le droit W ou RW (pas Free — Free = tout le monde, y compris sans clé).
     * Aligné freefare `madame_soleil` : Free ne compte pas comme « clé de comm ».
     */
    fun sessionHasWriteKey(authKeyNo: Int?): Boolean {
        if (authKeyNo == null) return false
        val k = authKeyNo and 0x0F
        val w = write and 0x0F
        val rw = readWrite and 0x0F
        return (w in 0..13 && k == w) || (rw in 0..13 && k == rw)
    }

    fun sessionHasReadKey(authKeyNo: Int?): Boolean {
        if (authKeyNo == null) return false
        val k = authKeyNo and 0x0F
        val r = read and 0x0F
        val rw = readWrite and 0x0F
        return (r in 0..13 && k == r) || (rw in 0..13 && k == rw)
    }

    companion object {
        /**
         * Valeur logique 16 bits (packing MDAR freefare / NXP) :
         * bits 15–12 Read, 11–8 Write, 7–4 ReadWrite, 3–0 Change.
         */
        fun parse(rawLogical: Int): AccessRights {
            val ar = rawLogical and 0xFFFF
            return AccessRights(
                read = (ar ushr 12) and 0x0F,
                write = (ar ushr 8) and 0x0F,
                readWrite = (ar ushr 4) and 0x0F,
                change = ar and 0x0F,
                raw = ar,
            )
        }

        /**
         * Octets filaires GetFileSettings / CreateFile : **little-endian**
         * (libfreefare `le16toh` sur le champ access_rights).
         *
         * Ex. wire `20 12` → logique `0x1220` → R=1 W=2 RW=2 Ch=0
         * (et non BE `0x2012` qui inversait W/R aux yeux du moniteur).
         */
        fun parse(bytes: ByteArray, offset: Int = 0): AccessRights {
            require(bytes.size >= offset + 2)
            val raw = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            return parse(raw)
        }

        /** Encode logique → 2 o LE pour CreateStdDataFile / ChangeFileSettings. */
        fun toWireLe(rawLogical: Int): ByteArray {
            val ar = rawLogical and 0xFFFF
            return byteArrayOf(
                (ar and 0xFF).toByte(),
                ((ar ushr 8) and 0xFF).toByte(),
            )
        }
    }
}

/**
 * Réponse GetFileSettings — v0.5 : Standard Data prioritaire.
 */
data class FileSettings(
    val fileNo: Int,
    val fileType: FileType,
    val commMode: CommMode,
    val accessRights: AccessRights,
    val sizeBytes: Int?,
    val raw: ByteArray,
) {
    val isStandard: Boolean get() = fileType == FileType.STANDARD
    /**
     * Ligne moniteur compacte EN : `F0 · Std · 16B · FULL`.
     * Droits à part via [AccessRights.compactLabel] (Proxmark-like).
     */
    val summaryLabel: String
        get() = buildString {
            append("F$fileNo · ${fileType.label}")
            if (sizeBytes != null) append(" · ${sizeBytes}B")
            append(" · ${commMode.label}")
        }

    /** Titre + droits Proxmark : `F0 · Std · 16B · FULL · r:1 w:2 rw:2 ch:0`. */
    val compactLine: String
        get() = "$summaryLabel · ${accessRights.compactLabel}"

    /**
     * Mode effectif pour **écriture** (aligné freefare `madame_soleil_get_write_communication_settings`) :
     * si la session n’est pas la clé W/RW (ex. Free `0xE`, ou clé maître 0 hors droits),
     * le PICC parle en **PLAIN** même si le fichier est déclaré FULL/MAC.
     * Sinon → [commMode] du fichier.
     */
    fun effectiveCommModeForWrite(sessionKeyNo: Int?): CommMode =
        if (accessRights.sessionHasWriteKey(sessionKeyNo)) commMode else CommMode.PLAIN

    /**
     * Mode effectif pour **lecture** (même heuristique freefare côté Read).
     */
    fun effectiveCommModeForRead(sessionKeyNo: Int?): CommMode =
        if (accessRights.sessionHasReadKey(sessionKeyNo) || accessRights.isReadFree && sessionKeyNo == null) {
            // Free sans session : plain ; Free avec session non-R : freefare → plain
            if (accessRights.sessionHasReadKey(sessionKeyNo)) commMode else CommMode.PLAIN
        } else if (accessRights.isReadFree) {
            CommMode.PLAIN
        } else {
            commMode
        }

    companion object {
        fun parse(fileNo: Int, data: ByteArray): FileSettings {
            require(data.isNotEmpty()) { "GetFileSettings data vide" }
            val type = FileType.fromCode(data[0].toInt())
            val comm = if (data.size >= 2) CommMode.fromWire(data[1].toInt()) else CommMode.PLAIN
            val rights = if (data.size >= 4) AccessRights.parse(data, 2) else AccessRights.parse(0xEEEE)
            val size = when (type) {
                FileType.STANDARD, FileType.BACKUP -> {
                    if (data.size >= 7) {
                        (data[4].toInt() and 0xFF) or
                            ((data[5].toInt() and 0xFF) shl 8) or
                            ((data[6].toInt() and 0xFF) shl 16)
                    } else null
                }
                else -> null
            }
            return FileSettings(
                fileNo = fileNo,
                fileType = type,
                commMode = comm,
                accessRights = rights,
                sizeBytes = size,
                raw = data.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileSettings) return false
        return fileNo == other.fileNo &&
            fileType == other.fileType &&
            commMode == other.commMode &&
            accessRights == other.accessRights &&
            sizeBytes == other.sizeBytes &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var r = fileNo
        r = 31 * r + fileType.hashCode()
        r = 31 * r + commMode.hashCode()
        r = 31 * r + accessRights.hashCode()
        r = 31 * r + (sizeBytes ?: 0)
        r = 31 * r + raw.contentHashCode()
        return r
    }
}

/**
 * GetKeySettings — PICC ou application.
 * Byte0 = settings bits ; byte1 = max keys (nibble bas) + type crypto (nibble haut, AES=0x8x souvent).
 */
data class KeySettingsInfo(
    val settingsRaw: Int,
    val maxKeys: Int,
    val raw: ByteArray,
) {
    val bits: KeySettingsBits get() = KeySettingsBits.from(settingsRaw)

    companion object {
        fun parse(data: ByteArray): KeySettingsInfo {
            require(data.size >= 2) { "GetKeySettings attend ≥ 2 octets, got ${data.size}" }
            val settings = data[0].toInt() and 0xFF
            val maxKeys = data[1].toInt() and 0x0F
            return KeySettingsInfo(settings, maxKeys, data.copyOf())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeySettingsInfo) return false
        return settingsRaw == other.settingsRaw && maxKeys == other.maxKeys && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var r = settingsRaw
        r = 31 * r + maxKeys
        r = 31 * r + raw.contentHashCode()
        return r
    }
}

/** Bits key settings (lecture) — CDC §3.2 / §7. */
data class KeySettingsBits(
    val allowMasterKeyChange: Boolean,
    val freeDirectoryListWithoutMaster: Boolean,
    val freeCreateDeleteWithoutMaster: Boolean,
    val configurationChangeable: Boolean,
    /** Bits 4–7 : change key access (0xE = auth with key to change, 0xF = same key only, etc.). */
    val changeKeyAccessBits: Int,
) {
    companion object {
        fun from(raw: Int): KeySettingsBits {
            val v = raw and 0xFF
            return KeySettingsBits(
                allowMasterKeyChange = v and 0x01 != 0,
                freeDirectoryListWithoutMaster = v and 0x02 != 0,
                freeCreateDeleteWithoutMaster = v and 0x04 != 0,
                configurationChangeable = v and 0x08 != 0,
                changeKeyAccessBits = (v ushr 4) and 0x0F,
            )
        }
    }
}

/** Nœud explorateur (lecture). */
data class FileNode(
    val settings: FileSettings,
    val dataHex: String? = null,
    val dataError: String? = null,
) {
    val fileNo: Int get() = settings.fileNo
}

data class ApplicationExploreResult(
    val aid: Aid,
    val keySettings: KeySettingsInfo?,
    val files: List<FileNode>,
    val notes: List<String> = emptyList(),
    /** True si les FileSettings viennent d’un explore précédent (mode lecture clé non-maître). */
    val structureFromCache: Boolean = false,
) {
    val aidHex: String get() = aid.hex
    val fileSettings: List<FileSettings> get() = files.map { it.settings }
}
