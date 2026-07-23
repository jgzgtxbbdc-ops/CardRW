package com.cardrw.desfire.client

import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FormatPICC (0xFC) — session AES master clé 0, plain+CMAC IV, invalidation session.
 */
class FormatPiccTest {

    private val key = AesConstants.FACTORY_KEY
    private val rndA = Hex.decode("00112233445566778899AABBCCDDEEFF")
    private val rndB = Hex.decode("FFEEDDCCBBAA99887766554433221100")

    @Test
    fun format_picc_requires_aes_session() {
        val client = DesfireClient(object : DesfireTransceiver {
            override fun transceive(apdu: ByteArray): ByteArray =
                byteArrayOf(0x91.toByte(), 0x00)
        })
        assertFailsWith<DesfireProtocolException> {
            client.formatPicc()
        }
    }

    @Test
    fun format_picc_after_aes_auth_ok_and_clears_session() {
        val card = SimulatedAesThenFormatCard(key, rndB, authKeyNo = 0)
        val client = DesfireClient(card)
        client.authenticateAes(keyNo = 0, key = key, aidHex = "000000", rndA = rndA)
        assertTrue(client.isAuthenticated)

        client.formatPicc()

        assertFalse(client.isAuthenticated)
        assertNull(client.session)
        assertTrue(card.formatReceived)
    }

    @Test
    fun format_picc_rejects_non_master_key_session() {
        val card = SimulatedAesThenFormatCard(key, rndB, authKeyNo = 1)
        val client = DesfireClient(card)
        client.authenticateAes(keyNo = 1, key = key, aidHex = "000000", rndA = rndA)
        val ex = assertFailsWith<DesfireProtocolException> {
            client.formatPicc()
        }
        assertTrue(ex.message.orEmpty().contains("master PICC", ignoreCase = true))
        assertFalse(card.formatReceived)
    }

    /**
     * Auth AES EV1 puis accepte FormatPICC `0xFC` (data vide).
     */
    private class SimulatedAesThenFormatCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
        private val authKeyNo: Int,
    ) : DesfireTransceiver {
        private var step = 0
        private val iv = AesCbc.zeroIv()
        var formatReceived = false

        override fun transceive(apdu: ByteArray): ByteArray {
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
                    check(gotRndBRot.contentEquals(AesCbc.rotateLeft(rndB)))
                    val rndAPrime = AesCbc.rotateLeft(gotRndA)
                    AesCbc.cbcSend(key, iv, rndAPrime)
                    rndAPrime + byteArrayOf(0x91.toByte(), 0x00)
                }
                cmd == 0xFC && step == 2 -> {
                    // FormatPICC : pas de data métier (CMAC éventuellement non renvoyé)
                    formatReceived = true
                    step = 3
                    byteArrayOf(0x91.toByte(), 0x00)
                }
                else -> error(
                    "Unexpected APDU step=$step cmd=${cmd.toString(16)} " +
                        "authKeyNo=$authKeyNo: ${Hex.encode(apdu)}",
                )
            }
        }
    }
}
