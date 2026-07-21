package com.cardrw.desfire.crypto

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

/**
 * [AesConstants.FACTORY_KEY] doit être une copie fraîche à chaque accès.
 */
class FactoryKeyTest {

    @Test
    fun factory_key_is_16_zero_bytes() {
        val k = AesConstants.FACTORY_KEY
        assertEquals(16, k.size)
        assertEquals("00000000000000000000000000000000", Hex.encode(k))
    }

    @Test
    fun factory_key_defensive_copy_not_shared() {
        val a = AesConstants.FACTORY_KEY
        val b = AesConstants.FACTORY_KEY
        assertNotSame(a, b)
        assertContentEquals(a, b)

        // Mutation d’une instance ne doit pas polluer les accès suivants
        a[0] = 0xFF.toByte()
        val c = AesConstants.FACTORY_KEY
        assertEquals(0, c[0].toInt() and 0xFF)
        assertFalse(a.contentEquals(c))
    }
}
