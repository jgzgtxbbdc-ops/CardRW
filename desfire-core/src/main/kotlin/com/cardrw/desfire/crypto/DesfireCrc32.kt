package com.cardrw.desfire.crypto

/**
 * CRC32 utilisé par DESFire (polynôme 0xEDB88320, init 0xFFFFFFFF, **sans** final XOR).
 * Diffère du CRC32 Ethernet/zip (qui XOR final avec 0xFFFFFFFF).
 *
 * Requis pour SM EV1 (v0.5) — fourni dès le squelette pour tests golden.
 */
object DesfireCrc32 {
    private val TABLE: IntArray = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if (crc and 1 != 0) {
                (crc ushr 1) xor 0xEDB88320.toInt()
            } else {
                crc ushr 1
            }
        }
        crc
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            val index = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor TABLE[index]
        }
        // DESFire : pas de final XOR
        return crc
    }

    /** 4 octets little-endian. */
    fun computeBytes(data: ByteArray): ByteArray {
        val crc = compute(data)
        return byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 24) and 0xFF).toByte(),
        )
    }
}
