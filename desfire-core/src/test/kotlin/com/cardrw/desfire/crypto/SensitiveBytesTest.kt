package com.cardrw.desfire.crypto

import com.cardrw.desfire.session.Ev1Session
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertTrue

class SensitiveBytesTest {

    @Test
    fun wipe_zeros_arrays() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        SensitiveBytes.wipe(a, b, null)
        assertTrue(a.all { it == 0.toByte() })
        assertTrue(b.all { it == 0.toByte() })
    }

    @Test
    fun ev1_wipe_clears_session_key() {
        val rndA = Hex.decode("00112233445566778899AABBCCDDEEFF")
        val rndB = Hex.decode("FFEEDDCCBBAA99887766554433221100")
        val sess = Ev1Session.create("000000", 0, rndA, rndB)
        assertTrue(sess.sessionKey.any { it != 0.toByte() })
        sess.wipeSecrets()
        assertTrue(sess.sessionKey.all { it == 0.toByte() })
    }
}
