package com.cardrw.desfire.framing

import com.cardrw.desfire.command.DesfireCommand
import com.cardrw.desfire.status.DesfireStatus
import com.cardrw.desfire.util.Hex

/**
 * Framing DESFire **natif wrappé** pour IsoDep :
 * - Commande : `90 CMD 00 00 [Lc DATA] 00`
 * - Réponse  : `[DATA…] 91 SW2`
 *
 * ISO 7816 wrapping « complet » alternatif : hors scope v1 (CDC §5.1).
 */
object NativeFraming {
    const val CLA: Int = 0x90
    const val SW1_DESFIRE: Int = 0x91

    /**
     * Construit l’APDU native wrappée.
     * @param cmd opcode DESFire
     * @param data payload (peut être vide)
     */
    fun wrap(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(cmd in 0..0xFF) { "cmd out of range: $cmd" }
        require(data.size <= 255) {
            "Payload > 255 octets : découper / chaining non géré à ce niveau (${data.size})"
        }
        return if (data.isEmpty()) {
            byteArrayOf(
                CLA.toByte(),
                cmd.toByte(),
                0x00, 0x00,
                0x00, // Le
            )
        } else {
            ByteArray(6 + data.size).also { apdu ->
                apdu[0] = CLA.toByte()
                apdu[1] = cmd.toByte()
                apdu[2] = 0x00
                apdu[3] = 0x00
                apdu[4] = data.size.toByte() // Lc
                data.copyInto(apdu, 5)
                apdu[5 + data.size] = 0x00 // Le
            }
        }
    }

    fun wrap(command: DesfireCommand, data: ByteArray = ByteArray(0)): ByteArray =
        wrap(command.code, data)

    /**
     * Parse une réponse IsoDep. Tolère les trames brutes même si incomplètes
     * (mode capture journal — CDC §5.3).
     */
    fun parseResponse(raw: ByteArray): DesfireResponse {
        if (raw.size < 2) {
            return DesfireResponse(
                data = raw.copyOf(),
                status = DesfireStatus.UNKNOWN,
                sw2 = -1,
                raw = raw.copyOf(),
                parseOk = false,
                parseNote = "Réponse trop courte (< 2 octets) — trame brute conservée.",
            )
        }
        val sw1 = raw[raw.size - 2].toInt() and 0xFF
        val sw2 = raw[raw.size - 1].toInt() and 0xFF
        val data = raw.copyOfRange(0, raw.size - 2)
        if (sw1 != SW1_DESFIRE) {
            return DesfireResponse(
                data = data,
                status = DesfireStatus.UNKNOWN,
                sw2 = sw2,
                raw = raw.copyOf(),
                parseOk = false,
                parseNote = "SW1 inattendu 0x${sw1.toString(16).uppercase()} (attendu 0x91).",
            )
        }
        return DesfireResponse(
            data = data,
            status = DesfireStatus.fromCode(sw2),
            sw2 = sw2,
            raw = raw.copyOf(),
            parseOk = true,
            parseNote = null,
        )
    }
}

data class DesfireResponse(
    val data: ByteArray,
    val status: DesfireStatus,
    val sw2: Int,
    val raw: ByteArray,
    val parseOk: Boolean = true,
    val parseNote: String? = null,
) {
    val isSuccess: Boolean get() = status.isSuccess
    val isAdditionalFrame: Boolean get() = status.isAdditionalFrame
    val dataHex: String get() = Hex.encode(data)
    val rawHex: String get() = Hex.encode(raw)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesfireResponse) return false
        return data.contentEquals(other.data) &&
            status == other.status &&
            sw2 == other.sw2 &&
            raw.contentEquals(other.raw) &&
            parseOk == other.parseOk &&
            parseNote == other.parseNote
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + sw2
        result = 31 * result + raw.contentHashCode()
        result = 31 * result + parseOk.hashCode()
        result = 31 * result + (parseNote?.hashCode() ?: 0)
        return result
    }
}
