package com.cardrw.desfire.session

import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AuthenticateEV2First + dérivation clés session EV2 (carte simulée).
 */
class Ev2SessionTest {

    private val key = AesConstants.FACTORY_KEY
    private val rndA = Hex.decode("00112233445566778899AABBCCDDEEFF")
    private val rndB = Hex.decode("FFEEDDCCBBAA99887766554433221100")
    private val ti = Hex.decode("AABBCCDD")

    @Test
    fun derive_session_keys_deterministic() {
        val (enc, mac) = Ev2Session.deriveSessionKeys(key, rndA, rndB)
        assertEquals(16, enc.size)
        assertEquals(16, mac.size)
        // Non trivial : différent de la clé usine et l’un de l’autre
        assertTrue(!enc.contentEquals(key))
        assertTrue(!mac.contentEquals(key))
        assertTrue(!enc.contentEquals(mac))
        // Reproductible
        val (enc2, mac2) = Ev2Session.deriveSessionKeys(key, rndA, rndB)
        assertEquals(Hex.encode(enc), Hex.encode(enc2))
        assertEquals(Hex.encode(mac), Hex.encode(mac2))
    }

    @Test
    fun truncate_ev2_mac_odd_bytes() {
        val full = Hex.decode("00112233445566778899AABBCCDDEEFF")
        val t = Ev2Session.truncateEv2Mac(full)
        assertEquals("1133557799BBDDFF", Hex.encode(t))
    }

    @Test
    fun authenticate_ev2_first_simulated() {
        val card = SimulatedEv2Card(key, rndB, ti)
        val client = DesfireClient(card)
        val session = client.authenticateEv2First(
            keyNo = 0,
            key = key,
            aidHex = "000000",
            rndA = rndA,
        )
        assertTrue(client.isAuthenticated)
        assertEquals(SecureMessagingLevel.EV2, session.smLevel)
        assertEquals("Auth AES · SM EV2", session.badgeLabel)
        assertEquals(Hex.encode(ti), Hex.encode(session.ti))
        val (enc, mac) = Ev2Session.deriveSessionKeys(key, rndA, rndB)
        assertEquals(Hex.encode(enc), Hex.encode(session.encKey))
        assertEquals(Hex.encode(mac), Hex.encode(session.macKey))
    }

    @Test
    fun prefer_ev1_falls_back_to_ev2_on_illegal_aa() {
        val card = Ev2OnlyCard(key, rndB, ti)
        val client = DesfireClient(card)
        val session = client.authenticateAesPreferEv1(
            keyNo = 0,
            key = key,
            aidHex = "000000",
            rndA = rndA,
        )
        assertEquals(SecureMessagingLevel.EV2, session.smLevel)
    }

    /** PICC EV2-only : 0xAA → 91 1C ; 0x71 → flux EV2 OK. */
    private class Ev2OnlyCard(
        key: ByteArray,
        rndB: ByteArray,
        ti: ByteArray,
    ) : DesfireTransceiver {
        private val inner = SimulatedEv2Card(key, rndB, ti)
        override fun transceive(apdu: ByteArray): ByteArray {
            val cmd = apdu[1].toInt() and 0xFF
            if (cmd == 0xAA) {
                return byteArrayOf(0x91.toByte(), 0x1C)
            }
            return inner.transceive(apdu)
        }
    }

    /**
     * Simule AuthenticateEV2First :
     * - 71 → ek(RndB) AF
     * - AF ek(RndA||RndB') → ek(RndA'||TI||PDCap2||PCDCap2) 00
     */
    private class SimulatedEv2Card(
        private val key: ByteArray,
        private val rndB: ByteArray,
        private val ti: ByteArray,
    ) : DesfireTransceiver {
        private var step = 0
        private val iv = AesCbc.zeroIv()

        override fun transceive(apdu: ByteArray): ByteArray {
            val cmd = apdu[1].toInt() and 0xFF
            val data = if (apdu.size > 5) {
                val lc = apdu[4].toInt() and 0xFF
                apdu.copyOfRange(5, 5 + lc)
            } else {
                ByteArray(0)
            }
            return when {
                cmd == 0x71 && step == 0 -> {
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
                    // RndA' || TI || PDCap2 || PCDCap2
                    val plain = AesCbc.rotateLeft(gotRndA) + ti + ByteArray(6) + ByteArray(6)
                    require(plain.size == 32)
                    AesCbc.cbcSend(key, iv, plain)
                    plain + byteArrayOf(0x91.toByte(), 0x00)
                }
                else -> error("Unexpected APDU step=$step cmd=${cmd.toString(16)}")
            }
        }
    }
}
