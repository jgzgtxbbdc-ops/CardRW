package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex

/**
 * Réponse concaténée de GetVersion (7 + 7 + 14 = 28 octets typiques).
 * Champs bruts toujours exposés en plus du libellé marketing (CDC §5.4).
 */
data class VersionInfo(
    val hardwareVendorId: Int,
    val hardwareType: Int,
    val hardwareSubtype: Int,
    val hardwareVersionMajor: Int,
    val hardwareVersionMinor: Int,
    val hardwareStorageSizeRaw: Int,
    val hardwareProtocol: Int,
    val softwareVendorId: Int,
    val softwareType: Int,
    val softwareSubtype: Int,
    val softwareVersionMajor: Int,
    val softwareVersionMinor: Int,
    val softwareStorageSizeRaw: Int,
    val softwareProtocol: Int,
    val uid: ByteArray,
    val batchNumber: ByteArray,
    val productionWeek: Int,
    val productionYear: Int,
) {
    val uidHex: String get() = Hex.encode(uid)

    /** SW version — fiable pour l’heuristique EV (ex. 3.1). */
    val softwareVersionLabel: String get() = "$softwareVersionMajor.$softwareVersionMinor"

    /**
     * HW « version » en hex compact (capture labo major=0x33 → pas un n° décimal de génération).
     */
    val hardwareVersionHexLabel: String
        get() = "${hardwareVersionMajor.toString(16).uppercase().padStart(2, '0')}." +
            "${hardwareVersionMinor.toString(16).uppercase().padStart(2, '0')}h"

    /**
     * Semaine / année de production : octets souvent en BCD (ex. 0x07 / 0x24 → S07 / 2024).
     * Afficher en décimal brut donnait « 2036 » pour 0x24.
     */
    val productionWeekBcd: Int get() = bcdToInt(productionWeek)
    val productionYearTwoDigitsBcd: Int get() = bcdToInt(productionYear)
    val productionYearFull: Int get() = 2000 + productionYearTwoDigitsBcd
    /** Affichage atelier : « 2024, semaine 07 ». */
    val productionLabel: String
        get() = "$productionYearFull, semaine ${productionWeekBcd.toString().padStart(2, '0')}"

    /**
     * Taille mémoire estimée en octets à partir du champ storage size NXP
     * (2^(n/2) pour n pair ; approximation documentée côté produit).
     */
    val estimatedMemoryBytes: Int?
        get() = decodeStorageSize(hardwareStorageSizeRaw)

    val cardFamily: CardFamily get() = CardFamilyHeuristic.classify(this)

    val typeLabel: String get() = cardFamily.displayLabel

    val batchHex: String get() = Hex.encode(batchNumber)

    /**
     * Champs techniques GetVersion **sans** redoubler le résumé UI
     * (type / UID / mémoire / SW / production sont déjà en tête d’écran).
     */
    fun technicalDetailRows(): List<Pair<String, String>> = listOf(
        "HW vendor / type / sub" to
            "0x${hardwareVendorId.toString(16)} / $hardwareType / $hardwareSubtype",
        "HW version (hex)" to hardwareVersionHexLabel,
        "HW storageRaw / proto" to "$hardwareStorageSizeRaw / $hardwareProtocol",
        "SW vendor / type / sub" to
            "0x${softwareVendorId.toString(16)} / $softwareType / $softwareSubtype",
        "SW storageRaw / proto" to "$softwareStorageSizeRaw / $softwareProtocol",
        "Batch" to batchHex,
    )

    /** @deprecated prefer [technicalDetailRows] for UI */
    fun detailLines(): List<Pair<String, String>> = technicalDetailRows()

    fun rawFieldsSummary(): String =
        technicalDetailRows().joinToString("; ") { (k, v) -> "$k: $v" }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VersionInfo) return false
        return hardwareVendorId == other.hardwareVendorId &&
            hardwareType == other.hardwareType &&
            hardwareSubtype == other.hardwareSubtype &&
            hardwareVersionMajor == other.hardwareVersionMajor &&
            hardwareVersionMinor == other.hardwareVersionMinor &&
            hardwareStorageSizeRaw == other.hardwareStorageSizeRaw &&
            hardwareProtocol == other.hardwareProtocol &&
            softwareVendorId == other.softwareVendorId &&
            softwareType == other.softwareType &&
            softwareSubtype == other.softwareSubtype &&
            softwareVersionMajor == other.softwareVersionMajor &&
            softwareVersionMinor == other.softwareVersionMinor &&
            softwareStorageSizeRaw == other.softwareStorageSizeRaw &&
            softwareProtocol == other.softwareProtocol &&
            uid.contentEquals(other.uid) &&
            batchNumber.contentEquals(other.batchNumber) &&
            productionWeek == other.productionWeek &&
            productionYear == other.productionYear
    }

    override fun hashCode(): Int {
        var result = hardwareVendorId
        result = 31 * result + hardwareType
        result = 31 * result + hardwareSubtype
        result = 31 * result + hardwareVersionMajor
        result = 31 * result + hardwareVersionMinor
        result = 31 * result + hardwareStorageSizeRaw
        result = 31 * result + hardwareProtocol
        result = 31 * result + softwareVendorId
        result = 31 * result + softwareType
        result = 31 * result + softwareSubtype
        result = 31 * result + softwareVersionMajor
        result = 31 * result + softwareVersionMinor
        result = 31 * result + softwareStorageSizeRaw
        result = 31 * result + softwareProtocol
        result = 31 * result + uid.contentHashCode()
        result = 31 * result + batchNumber.contentHashCode()
        result = 31 * result + productionWeek
        result = 31 * result + productionYear
        return result
    }

    companion object {
        const val EXPECTED_LENGTH = 28

        fun parse(bytes: ByteArray): VersionInfo {
            require(bytes.size >= EXPECTED_LENGTH) {
                "GetVersion data length ${bytes.size} < $EXPECTED_LENGTH"
            }
            var i = 0
            fun u8(): Int = bytes[i++].toInt() and 0xFF
            fun take(n: Int): ByteArray = bytes.copyOfRange(i, i + n).also { i += n }

            return VersionInfo(
                hardwareVendorId = u8(),
                hardwareType = u8(),
                hardwareSubtype = u8(),
                hardwareVersionMajor = u8(),
                hardwareVersionMinor = u8(),
                hardwareStorageSizeRaw = u8(),
                hardwareProtocol = u8(),
                softwareVendorId = u8(),
                softwareType = u8(),
                softwareSubtype = u8(),
                softwareVersionMajor = u8(),
                softwareVersionMinor = u8(),
                softwareStorageSizeRaw = u8(),
                softwareProtocol = u8(),
                uid = take(7),
                batchNumber = take(5),
                productionWeek = u8(),
                productionYear = u8(),
            )
        }

        /**
         * NXP storage size encoding: value n means ~2^(n/2) bytes when n is even.
         * Odd n means « greater than » the even value below — we still report the power-of-two floor.
         */
        fun decodeStorageSize(raw: Int): Int? {
            if (raw <= 0) return null
            val n = raw and 0xFE // clear low bit for « > » marker
            if (n > 32) return null
            val bytes = 1 shl (n / 2)
            return if (bytes > 0) bytes else null
        }

        /** Interprète un octet comme BCD (0x24 → 24). Si nibble > 9, fallback décimal. */
        fun bcdToInt(byte: Int): Int {
            val b = byte and 0xFF
            val hi = b ushr 4
            val lo = b and 0x0F
            return if (hi <= 9 && lo <= 9) hi * 10 + lo else b
        }
    }
}

enum class CardFamily(val displayLabel: String) {
    DESFIRE_EV1("DESFire EV1 (probable)"),
    DESFIRE_EV2("DESFire EV2 (probable)"),
    DESFIRE_EV3("DESFire EV3 (probable)"),
    DESFIRE_UNKNOWN("DESFire (famille non déterminée)"),
    NOT_DESFIRE("Non DESFire / inconnu"),
}

/**
 * Heuristique documentée — à affiner avec la matrice labo (CDC §5.4, §14).
 * NXP vendor ID = 0x04.
 *
 * Capture labo (2026-07-21) : HW `04 01 02 33 00 1C 05`, SW `04 01 03 03 01 1C 05`
 * → le **major logiciel** (3.1) est le signal fiable ; le major HW (0x33) n’est pas
 * un numéro de génération EV et ne doit pas dominer la classification.
 */
object CardFamilyHeuristic {
    const val NXP_VENDOR_ID = 0x04

    fun classify(info: VersionInfo): CardFamily {
        if (info.hardwareVendorId != NXP_VENDOR_ID && info.softwareVendorId != NXP_VENDOR_ID) {
            return CardFamily.NOT_DESFIRE
        }
        // Priorité stricte au major logiciel (captures EV3 terrain).
        val major = info.softwareVersionMajor
        return when {
            major >= 3 -> CardFamily.DESFIRE_EV3
            major == 2 -> CardFamily.DESFIRE_EV2
            major == 1 -> CardFamily.DESFIRE_EV1
            else -> {
                // Fallback HW seulement si major plausible (1–3), pas un octet opaque.
                when (info.hardwareVersionMajor) {
                    3 -> CardFamily.DESFIRE_EV3
                    2 -> CardFamily.DESFIRE_EV2
                    1 -> CardFamily.DESFIRE_EV1
                    else -> CardFamily.DESFIRE_UNKNOWN
                }
            }
        }
    }
}
