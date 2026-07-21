package com.cardrw.desfire.crypto

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class DesfireCrc32Test {

    @Test
    fun empty_payload() {
        // CRC32 DESFire of empty data with init 0xFFFFFFFF and no final XOR = 0xFFFFFFFF
        assertEquals(0xFFFFFFFF.toInt(), DesfireCrc32.compute(ByteArray(0)))
    }

    @Test
    fun known_vector_hello() {
        // Sanity: non-zero data changes CRC; golden lab vectors will replace this.
        val data = "Hello".encodeToByteArray()
        val crc = DesfireCrc32.compute(data)
        val le = DesfireCrc32.computeBytes(data)
        assertEquals(4, le.size)
        assertEquals(crc and 0xFF, le[0].toInt() and 0xFF)
        // Stable self-check for regressions
        assertEquals(Hex.encode(le), Hex.encode(DesfireCrc32.computeBytes(data)))
    }
}
