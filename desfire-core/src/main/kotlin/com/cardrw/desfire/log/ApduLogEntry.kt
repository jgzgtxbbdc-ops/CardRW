package com.cardrw.desfire.log

import com.cardrw.desfire.command.DesfireCommand
import com.cardrw.desfire.status.DesfireStatus
import com.cardrw.desfire.util.Hex

enum class ApduDirection { OUT, IN }

enum class AnnotationLevel {
    /** Intention + résultat humain. */
    SUMMARY,
    /** + nom commande, status pédagogique. */
    DETAILED,
    /** Hex pur uniquement. */
    HEX,
}

/**
 * Entrée de journal APDU — objet de première classe (CDC §7.5).
 */
data class ApduLogEntry(
    val timestampEpochMs: Long,
    val direction: ApduDirection,
    val raw: ByteArray,
    val command: DesfireCommand? = null,
    val status: DesfireStatus? = null,
    val annotation: String,
    val sessionId: String? = null,
) {
    val rawHex: String get() = Hex.pretty(raw)

    fun format(level: AnnotationLevel = AnnotationLevel.DETAILED): String {
        val arrow = if (direction == ApduDirection.OUT) "→" else "←"
        return when (level) {
            AnnotationLevel.HEX -> "$arrow $rawHex"
            AnnotationLevel.SUMMARY -> "$arrow $annotation"
            AnnotationLevel.DETAILED -> {
                // Hex brut d’abord ; annotation courte sans redoubler le nom de commande.
                if (annotation.isBlank()) {
                    "$arrow $rawHex"
                } else {
                    "$arrow $rawHex  ·  $annotation"
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApduLogEntry) return false
        return timestampEpochMs == other.timestampEpochMs &&
            direction == other.direction &&
            raw.contentEquals(other.raw) &&
            command == other.command &&
            status == other.status &&
            annotation == other.annotation &&
            sessionId == other.sessionId
    }

    override fun hashCode(): Int {
        var result = timestampEpochMs.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + raw.contentHashCode()
        result = 31 * result + (command?.hashCode() ?: 0)
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + annotation.hashCode()
        result = 31 * result + (sessionId?.hashCode() ?: 0)
        return result
    }
}

/** Journal en mémoire pour une session carte. */
class ApduJournal {
    private val entries = mutableListOf<ApduLogEntry>()

    val all: List<ApduLogEntry> get() = entries.toList()

    fun clear() = entries.clear()

    fun append(entry: ApduLogEntry) {
        entries += entry
    }

    fun exportText(level: AnnotationLevel = AnnotationLevel.DETAILED): String =
        entries.joinToString("\n") { it.format(level) }
}
