package com.cardrw.desfire.crypto

/**
 * CRC-A ISO/IEC 14443-3 (CRC-16) utilisé par le SM DESFire **legacy** (AS_LEGACY).
 *
 * Init `0x6363`, poly réfléchi, sortie little-endian — distinct du [DesfireCrc32] EV1/EV2.
 *
 * Réf. libfreefare `iso14443a_crc` / libnfc.
 */
object Iso14443aCrc16 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x6363
        for (i in offset until offset + length) {
            var bt = data[i].toInt() and 0xFF
            bt = bt xor (crc and 0xFF)
            bt = bt xor (bt shl 4)
            crc = (crc ushr 8) xor
                ((bt and 0xFF) shl 8) xor
                ((bt and 0xFF) shl 3) xor
                ((bt and 0xFF) ushr 4)
            crc = crc and 0xFFFF
        }
        return crc
    }

    /** 2 octets little-endian. */
    fun computeBytes(data: ByteArray): ByteArray {
        val crc = compute(data)
        return byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
        )
    }
}
