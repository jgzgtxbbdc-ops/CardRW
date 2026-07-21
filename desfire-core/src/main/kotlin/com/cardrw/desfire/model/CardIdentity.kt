package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex

enum class UidKind {
    /** UID stable renvoyé par le tag NFC / GetVersion. */
    FIXED,
    /** Random ID — GetCardUID après auth pour l’UID réel. */
    RANDOM,
    UNKNOWN,
}

/**
 * Profil identité carte affiché en tête de wizard « Carte » (CDC §7.1).
 */
data class CardIdentity(
    val uidFromTag: ByteArray?,
    val uidKind: UidKind,
    val version: VersionInfo?,
    val freeMemoryBytes: Int?,
    val applications: List<Aid>,
    val typeLabel: String,
    val rawNotes: List<String> = emptyList(),
) {
    val displayUid: String
        get() = when {
            uidFromTag != null -> Hex.encode(uidFromTag)
            version != null -> version.uidHex
            else -> "—"
        }

    /**
     * Une seule ligne lisible : « ≈ 16 Ko · libre 14,5 Ko ».
     * Si l’heuristique GetVersion sous-estime vs GetFreeMemory, on n’affiche pas un total absurde.
     */
    val memoryLabel: String
        get() {
            val total = version?.estimatedMemoryBytes
            val free = freeMemoryBytes
            return when {
                total != null && free != null && free > total ->
                    "libre ${formatBytes(free)} (estim. carte ≈ ${formatBytes(total)})"
                total != null && free != null ->
                    "≈ ${formatBytes(total)} · libre ${formatBytes(free)}"
                total != null -> "≈ ${formatBytes(total)}"
                free != null -> "libre ${formatBytes(free)}"
                else -> "mémoire inconnue"
            }
        }

    private fun formatBytes(n: Int): String = when {
        n >= 1024 && n % 1024 == 0 -> "${n / 1024} Ko"
        n >= 1024 -> {
            val k = n / 1024.0
            val rounded = (kotlin.math.round(k * 10) / 10.0)
            if (rounded == rounded.toLong().toDouble()) {
                "${rounded.toLong()} Ko"
            } else {
                "${rounded.toString().replace('.', ',')} Ko"
            }
        }
        else -> "$n o"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardIdentity) return false
        return uidFromTag.contentEquals(other.uidFromTag) &&
            uidKind == other.uidKind &&
            version == other.version &&
            freeMemoryBytes == other.freeMemoryBytes &&
            applications == other.applications &&
            typeLabel == other.typeLabel &&
            rawNotes == other.rawNotes
    }

    override fun hashCode(): Int {
        var result = uidFromTag?.contentHashCode() ?: 0
        result = 31 * result + uidKind.hashCode()
        result = 31 * result + (version?.hashCode() ?: 0)
        result = 31 * result + (freeMemoryBytes ?: 0)
        result = 31 * result + applications.hashCode()
        result = 31 * result + typeLabel.hashCode()
        result = 31 * result + rawNotes.hashCode()
        return result
    }
}
