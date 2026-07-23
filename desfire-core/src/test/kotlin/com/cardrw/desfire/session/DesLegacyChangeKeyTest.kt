package com.cardrw.desfire.session

import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.DesCipher
import com.cardrw.desfire.crypto.DesConstants
import com.cardrw.desfire.crypto.Iso14443aCrc16
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ChangeKey DES→AES (PICC master) — crypto + client simulé.
 */
class DesLegacyChangeKeyTest {

    private val authKey = DesConstants.FACTORY_2KTDEA_KEY
    private val rndA = Hex.decode("0011223344556677")
    private val rndB = Hex.decode("FFEEDDCCBBAA9988")

    @Test
    fun derive_session_key_2ktdea() {
        val sk = DesLegacySession.deriveSessionKey(rndA, rndB, authKey)
        assertEquals(
            "00112233FFEEDDCC44556677BBAA9988",
            Hex.encode(sk),
        )
    }

    @Test
    fun prepare_change_key_des_to_aes_factory_shape_and_plaintext() {
        val sessionKey = DesLegacySession.deriveSessionKey(rndA, rndB, authKey)
        val sess = DesLegacySession("000000", 0, authKey, sessionKey)
        val newKey = AesConstants.FACTORY_KEY
        val plainBody = newKey + byteArrayOf(0x00)
        val crc = Iso14443aCrc16.computeBytes(plainBody)
        val expectedPadded = (plainBody + crc).copyOf(24)

        val payload = sess.prepareChangeKeyDesToAes(0, newKey, keyVersion = 0)
        // KeyNo wire = 0x80 (AES flag) + 24 o cryptogramme
        assertEquals(0x80, payload[0].toInt() and 0xFF)
        assertEquals(1 + 24, payload.size)

        val recovered = inverseSendDecypher(sessionKey, payload.copyOfRange(1, payload.size))
        assertEquals(Hex.encode(expectedPadded), Hex.encode(recovered))
    }

    @Test
    fun change_key_des_to_aes_simulated_card() {
        val card = SimulatedDesThenChangeKeyCard(authKey, rndB)
        val client = DesfireClient(card)
        client.authenticateDes(0, authKey, "000000", rndA)
        assertEquals(SecureMessagingLevel.DES_LEGACY, client.session!!.smLevel)

        val newKey = AesConstants.FACTORY_KEY
        client.changeKeyDesToAes(0, newKey, keyVersion = 0)

        assertFalse(client.isAuthenticated)
        assertNull(client.session)
        assertTrue(card.changeKeyOk)
        // Wire keyNo must be 0x80
        assertEquals(0x80, card.lastKeyNoWire)
    }

    @Test
    fun change_key_requires_des_session() {
        val client = DesfireClient(object : DesfireTransceiver {
            override fun transceive(apdu: ByteArray): ByteArray =
                byteArrayOf(0x91.toByte(), 0x00)
        })
        assertFailsWith<Exception> {
            client.changeKeyDesToAes(0, AesConstants.FACTORY_KEY)
        }
    }

    /**
     * Inverse de [DesCipher.cbcSendLegacyDecrypt] (SEND + DECYPHER) :
     * pour chaque bloc : `pt = ENC(ct) ⊕ IV` ; `IV ← ct`.
     */
    private fun inverseSendDecypher(key: ByteArray, cipher: ByteArray): ByteArray {
        val out = cipher.copyOf()
        val iv = DesCipher.zeroIv()
        var offset = 0
        while (offset < out.size) {
            val ct = out.copyOfRange(offset, offset + 8)
            val enc = DesCipher.encryptBlock(key, ct)
            for (i in 0 until 8) {
                out[offset + i] = (enc[i].toInt() xor iv[i].toInt()).toByte()
            }
            ct.copyInto(iv)
            offset += 8
        }
        return out
    }

    /**
     * Auth DES puis accepte un ChangeKey DES→AES bien formé (vérifie cryptogramme).
     */
    private class SimulatedDesThenChangeKeyCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
    ) : DesfireTransceiver {
        private var step = 0
        private var sessionKey: ByteArray? = null
        var changeKeyOk = false
        var lastKeyNoWire = -1

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
                    check(gotRndBRot.contentEquals(DesCipher.rotateLeft(rndB)))
                    sessionKey = DesLegacySession.deriveSessionKey(gotRndA, rndB, key)
                    val rndAPrime = DesCipher.rotateLeft(gotRndA)
                    val ek = rndAPrime.copyOf()
                    DesCipher.cbcSendEncrypt(key, DesCipher.zeroIv(), ek)
                    ek + byteArrayOf(0x91.toByte(), 0x00)
                }
                cmd == 0xC4 && step == 2 -> {
                    val sk = sessionKey!!
                    lastKeyNoWire = data[0].toInt() and 0xFF
                    check(lastKeyNoWire == 0x80) { "keyNo wire=${lastKeyNoWire.toString(16)}" }
                    val cipher = data.copyOfRange(1, data.size)
                    // Inverse SEND DECYPHER
                    val plain = cipher.copyOf()
                    val iv = DesCipher.zeroIv()
                    var off = 0
                    while (off < plain.size) {
                        val ct = plain.copyOfRange(off, off + 8)
                        val enc = DesCipher.encryptBlock(sk, ct)
                        for (i in 0 until 8) {
                            plain[off + i] = (enc[i].toInt() xor iv[i].toInt()).toByte()
                        }
                        ct.copyInto(iv)
                        off += 8
                    }
                    // newKey (16) + ver (1) + crc (2)
                    val body = plain.copyOfRange(0, 17)
                    val gotCrc = plain.copyOfRange(17, 19)
                    val expectCrc = Iso14443aCrc16.computeBytes(body)
                    check(gotCrc.contentEquals(expectCrc)) {
                        "CRC mismatch ${Hex.encode(gotCrc)} vs ${Hex.encode(expectCrc)}"
                    }
                    changeKeyOk = true
                    step = 3
                    byteArrayOf(0x91.toByte(), 0x00)
                }
                else -> error("Unexpected APDU step=$step cmd=${cmd.toString(16)}")
            }
        }
    }
}
