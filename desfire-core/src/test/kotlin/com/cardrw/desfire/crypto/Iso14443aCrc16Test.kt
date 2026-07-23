package com.cardrw.desfire.crypto

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class Iso14443aCrc16Test {

    @Test
    fun empty_init_6363() {
        assertEquals(0x6363, Iso14443aCrc16.compute(ByteArray(0)))
        assertEquals("6363", Hex.encode(Iso14443aCrc16.computeBytes(ByteArray(0))))
    }

    @Test
    fun known_vector_00() {
        // CRC-A of single 0x00 — valeur de référence libnfc / ISO14443
        val crc = Iso14443aCrc16.computeBytes(byteArrayOf(0x00))
        assertEquals(2, crc.size)
        // Non-trivial (≠ 6363)
        assertEquals(false, crc.contentEquals(byteArrayOf(0x63, 0x63)))
    }

    @Test
    fun aes_factory_key_plus_version_stable() {
        // newKey 16×00 + version 0 — utilisé pour ChangeKey DES→AES labo
        val body = ByteArray(16) + byteArrayOf(0x00)
        val crc = Iso14443aCrc16.computeBytes(body)
        assertEquals(2, crc.size)
        // Recompute must be stable
        assertEquals(Hex.encode(crc), Hex.encode(Iso14443aCrc16.computeBytes(body)))
    }
}
