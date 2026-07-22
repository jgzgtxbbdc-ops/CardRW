package com.cardrw.desfire.crypto

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden dérivé d’une capture terrain carte vierge NXP (2026-07-22).
 * Valide : RECV/SEND CBC, IV=0 par étape, clé 2KTDEA 00…00.
 */
class DesRealApduProbeTest {
    private val key = DesConstants.FACTORY_2KTDEA_KEY
    private val ekRndB = Hex.decode("4C0E996C1126205F")
    private val tokenWire = Hex.decode("7C50DE2E3C2BC8AB6304836923F1E989")
    private val ekRndAPrime = Hex.decode("B76C4D722A410FAF")

    @Test
    fun real_blank_card_capture_roundtrip() {
        val rndB = ekRndB.copyOf()
        DesCipher.cbcReceive(key, DesCipher.zeroIv(), rndB)
        assertEquals("6AD0A749045F15F6", Hex.encode(rndB))

        val token = tokenWire.copyOf()
        DesCipher.cbcReceive(key, DesCipher.zeroIv(), token)
        val rndA = token.copyOfRange(0, 8)
        val rndBRot = token.copyOfRange(8, 16)
        assertEquals(Hex.encode(DesCipher.rotateLeft(rndB)), Hex.encode(rndBRot))

        // Re-chiffre le token comme le host (SEND ENC IV0)
        val tokenOut = rndA + rndBRot
        DesCipher.cbcSendEncrypt(key, DesCipher.zeroIv(), tokenOut)
        assertEquals(Hex.encode(tokenWire), Hex.encode(tokenOut))

        val rndAPrime = ekRndAPrime.copyOf()
        DesCipher.cbcReceive(key, DesCipher.zeroIv(), rndAPrime)
        assertEquals(Hex.encode(DesCipher.rotateLeft(rndA)), Hex.encode(rndAPrime))
        assertTrue(true)
    }
}
