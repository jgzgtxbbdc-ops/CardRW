package com.cardrw.desfire.crypto

import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.session.Ev1Session
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AuthenticateAES bout-en-bout avec carte simulée (clé usine + RndA/RndB fixes).
 */
class AesAuthTest {

    private val key = AesConstants.FACTORY_KEY
    private val rndA = Hex.decode("00112233445566778899AABBCCDDEEFF")
    private val rndB = Hex.decode("FFEEDDCCBBAA99887766554433221100")

    @Test
    fun session_key_derivation_ti_formula() {
        // RndA[0..3]||RndB[0..3]||RndA[12..15]||RndB[12..15]
        val sk = Ev1Session.deriveSessionKey(rndA, rndB)
        assertEquals("00112233FFEEDDCCCCDDEEFF33221100", Hex.encode(sk))
    }

    @Test
    fun rotate_left_one_byte() {
        val r = AesCbc.rotateLeft(rndB)
        assertEquals("EEDDCCBBAA99887766554433221100FF", Hex.encode(r))
    }

    @Test
    fun authenticate_aes_with_simulated_card() {
        val card = SimulatedAesCard(key, rndB)
        val client = DesfireClient(card)
        val session = client.authenticateAes(
            keyNo = 0,
            key = key,
            aidHex = "000000",
            rndA = rndA,
        )
        assertTrue(client.isAuthenticated)
        assertEquals(0, session.keyNumber)
        assertEquals("000000", session.aidHex)
        assertEquals(Hex.encode(Ev1Session.deriveSessionKey(rndA, rndB)), Hex.encode(session.sessionKey))
        assertEquals("Auth AES · SM EV1", session.badgeLabel)
    }

    @Test
    fun wrong_key_fails() {
        val card = SimulatedAesCard(key, rndB)
        val client = DesfireClient(card)
        val wrongKey = Hex.decode("00000000000000000000000000000001")
        try {
            client.authenticateAes(0, wrongKey, "000000", rndA)
            throw AssertionError("expected failure")
        } catch (e: Exception) {
            // Carte simulée : RndB' incorrect ; vraie carte → Authentication error / RndA'.
            val msg = e.message.orEmpty()
            assertTrue(
                msg.contains("RndB", ignoreCase = true) ||
                    msg.contains("RndA", ignoreCase = true) ||
                    msg.contains("AuthenticateAES") ||
                    msg.contains("clé"),
                "unexpected: $msg",
            )
        }
    }

    /**
     * Simule une PICC DESFire AES :
     * - AA → ek(RndB) AF
     * - AF ek(RndA||RndB') → ek(RndA') 00
     */
    private class SimulatedAesCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
    ) : DesfireTransceiver {
        private var step = 0
        private val iv = AesCbc.zeroIv()

        override fun transceive(apdu: ByteArray): ByteArray {
            // APDU : 90 CMD 00 00 [Lc data] 00
            val cmd = apdu[1].toInt() and 0xFF
            val data = if (apdu.size > 5) {
                val lc = apdu[4].toInt() and 0xFF
                apdu.copyOfRange(5, 5 + lc)
            } else {
                ByteArray(0)
            }
            return when {
                cmd == 0xAA && step == 0 -> {
                    step = 1
                    // reset IV for auth start
                    for (i in iv.indices) iv[i] = 0
                    val ekRndB = rndB.copyOf()
                    AesCbc.cbcSend(key, iv, ekRndB)
                    ekRndB + byteArrayOf(0x91.toByte(), 0xAF.toByte())
                }
                cmd == 0xAF && step == 1 -> {
                    step = 2
                    val token = data.copyOf()
                    require(token.size == 32)
                    AesCbc.cbcReceive(key, iv, token)
                    val gotRndA = token.copyOfRange(0, 16)
                    val gotRndBRot = token.copyOfRange(16, 32)
                    val expectedRot = AesCbc.rotateLeft(rndB)
                    check(gotRndBRot.contentEquals(expectedRot)) {
                        "card: bad RndB' ${Hex.encode(gotRndBRot)} vs ${Hex.encode(expectedRot)}"
                    }
                    val rndAPrime = AesCbc.rotateLeft(gotRndA)
                    AesCbc.cbcSend(key, iv, rndAPrime)
                    rndAPrime + byteArrayOf(0x91.toByte(), 0x00)
                }
                else -> error("Unexpected APDU step=$step cmd=${cmd.toString(16)}: ${Hex.encode(apdu)}")
            }
        }
    }
}
