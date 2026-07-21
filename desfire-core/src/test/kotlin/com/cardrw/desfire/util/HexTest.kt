package com.cardrw.desfire.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HexTest {
    @Test
    fun roundtrip() {
        val bytes = byteArrayOf(0x01, 0xF4.toByte(), 0x02)
        assertEquals("01F402", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("01F402"))
        assertContentEquals(bytes, Hex.decode("01 f4:02"))
    }
}
