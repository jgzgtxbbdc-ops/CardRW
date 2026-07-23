package com.cardrw.desfire.crypto

import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AuthenticateDES (0x0A) carte simulée — PICC usine 2KTDEA 00…00.
 */
class DesAuthTest {

    private val key = DesConstants.FACTORY_2KTDEA_KEY
    private val rndA = Hex.decode("0011223344556677")
    private val rndB = Hex.decode("FFEEDDCCBBAA9988")

    @Test
    fun authenticate_des_2ktdea_factory() {
        val card = SimulatedDesCard(key, rndB)
        val client = DesfireClient(card)
        val session = client.authenticateDes(
            keyNo = 0,
            key = key,
            aidHex = "000000",
            rndA = rndA,
        )
        assertTrue(client.isAuthenticated)
        assertEquals(SecureMessagingLevel.DES_LEGACY, session.smLevel)
        assertEquals("Auth DES · legacy", session.badgeLabel)
        // Session key 2KTDEA : RndA[0..3]‖RndB[0..3]‖RndA[4..7]‖RndB[4..7]
        assertEquals("00112233FFEEDDCC44556677BBAA9988", Hex.encode(session.sessionKey))
    }

    @Test
    fun prefer_aes_falls_back_to_des_on_ae_with_zero_key() {
        val card = DesOnlyCard(key, rndB)
        val client = DesfireClient(card)
        val session = client.authenticateAesPreferEv1(
            keyNo = 0,
            key = AesConstants.FACTORY_KEY,
            aidHex = "000000",
            rndA = Hex.decode("00112233445566778899AABBCCDDEEFF"),
        )
        assertEquals(SecureMessagingLevel.DES_LEGACY, session.smLevel)
    }

    /** 0xAA → AE ; 0x0A → OK. */
    private class DesOnlyCard(
        key: ByteArray,
        rndB: ByteArray,
    ) : DesfireTransceiver {
        private val inner = SimulatedDesCard(key, rndB)
        override fun transceive(apdu: ByteArray): ByteArray {
            val cmd = apdu[1].toInt() and 0xFF
            if (cmd == 0xAA || cmd == 0x71) {
                return byteArrayOf(0x91.toByte(), 0xAE.toByte())
            }
            return inner.transceive(apdu)
        }
    }

    /**
     * Simule PICC DES blank NXP : SEND=ENCYPHER CBC, RECV=DECYPHER CBC, IV=0 chaque étape.
     */
    private class SimulatedDesCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
    ) : DesfireTransceiver {
        private var step = 0

        override fun transceive(apdu: ByteArray): ByteArray {
            val cmd = apdu[1].toInt() and 0xFF
            val data = if (apdu.size > 5) {
                val lc = apdu[4].toInt() and 0xFF
                apdu.copyOfRange(5, 5 + lc)
            } else {
                ByteArray(0)
            }
            return when {
                cmd == 0x0A && step == 0 -> {
                    step = 1
                    val ek = rndB.copyOf()
                    DesCipher.cbcSendEncrypt(key, DesCipher.zeroIv(), ek)
                    ek + byteArrayOf(0x91.toByte(), 0xAF.toByte())
                }
                cmd == 0xAF && step == 1 -> {
                    step = 2
                    val token = data.copyOf()
                    require(token.size == 16)
                    DesCipher.cbcReceive(key, DesCipher.zeroIv(), token)
                    val gotRndA = token.copyOfRange(0, 8)
                    val gotRndBRot = token.copyOfRange(8, 16)
                    check(gotRndBRot.contentEquals(DesCipher.rotateLeft(rndB))) {
                        "bad RndB' ${Hex.encode(gotRndBRot)}"
                    }
                    val rndAPrime = DesCipher.rotateLeft(gotRndA)
                    val ek = rndAPrime.copyOf()
                    DesCipher.cbcSendEncrypt(key, DesCipher.zeroIv(), ek)
                    ek + byteArrayOf(0x91.toByte(), 0x00)
                }
                else -> error("Unexpected DES APDU step=$step cmd=${cmd.toString(16)}")
            }
        }
    }
}
