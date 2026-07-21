package com.cardrw.desfire.crypto

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vecteurs NIST SP 800-38B Example 1 (AES-128).
 * Key = 2b7e151628aed2a6abf7158809cf4f3c
 */
class DesfireCmacTest {

    private val key = Hex.decode("2b7e151628aed2a6abf7158809cf4f3c")

    @Test
    fun nist_empty_message() {
        val cmac = DesfireCmac(key)
        val iv = AesCbc.zeroIv()
        val result = cmac.compute(iv, ByteArray(0))
        assertEquals("BB1D6929E95937287FA37D129B756746", Hex.encode(result))
    }

    @Test
    fun nist_16_byte_message() {
        val cmac = DesfireCmac(key)
        val iv = AesCbc.zeroIv()
        val msg = Hex.decode("6bc1bee22e409f96e93d7e117393172a")
        val result = cmac.compute(iv, msg)
        assertEquals("070A16B46B4D4144F79BDD9DD04A287C", Hex.encode(result))
    }

    @Test
    fun nist_40_byte_message() {
        val cmac = DesfireCmac(key)
        val iv = AesCbc.zeroIv()
        val msg = Hex.decode(
            "6bc1bee22e409f96e93d7e117393172a" +
                "ae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411",
        )
        val result = cmac.compute(iv, msg)
        assertEquals("DFA66747DE9AE63030CA32611497C827", Hex.encode(result))
    }
}
