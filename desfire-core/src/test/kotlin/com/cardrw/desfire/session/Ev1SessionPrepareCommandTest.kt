package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.DesfireCrc32
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SM EV1 [Ev1Session.prepareCommand] — PLAIN (ReadData TX) + FULL en-têtes clairs (Write/ChangeKey).
 */
class Ev1SessionPrepareCommandTest {

    private val rndA = Hex.decode("00112233445566778899AABBCCDDEEFF")
    private val rndB = Hex.decode("FFEEDDCCBBAA99887766554433221100")

    private fun session(): Ev1Session = Ev1Session.create("F00102", keyNumber = 0, rndA, rndB)

    @Test
    fun plain_read_data_tx_returns_params_unchanged() {
        // ReadData FULL fichier : TX reste PLAIN (FileNo‖Offset‖Length)
        val sess = session()
        val params = Hex.decode("00" + "000000" + "100000") // file 0, off 0, len 16 LE
        val out = sess.prepareCommand(0xBD, params, CommMode.PLAIN)
        assertContentEquals(params, out)
        assertEquals(7, out.size)
    }

    @Test
    fun maced_appends_8_byte_cmac() {
        val sess = session()
        val data = Hex.decode("00" + "000000" + "040000")
        val out = sess.prepareCommand(0xBD, data, CommMode.MACED)
        assertEquals(data.size + Ev1Session.CMAC_TX_LEN, out.size)
        assertContentEquals(data, out.copyOf(data.size))
    }

    @Test
    fun full_write_data_clear_header_7() {
        // WriteData 0x3D : header FileNo‖Offset‖Length (7) en clair, corps chiffré
        val sess = session()
        val header = Hex.decode("01" + "000000" + "040000") // file 1, off 0, len 4
        val body = Hex.decode("DEADBEEF")
        val data = header + body
        val opcode = 0x3D

        val out = sess.prepareCommand(opcode, data, CommMode.FULL, clearHeaderLength = 7)

        // En-tête inchangé en tête du data field
        assertContentEquals(header, out.copyOf(7))
        // Corps chiffré : body(4) + CRC(4) + pad → 16 o (1 bloc)
        val cipher = out.copyOfRange(7, out.size)
        assertEquals(16, cipher.size)
        assertEquals(7 + 16, out.size)

        // Déchiffre et vérifie body + CRC(cmd‖header‖body)
        val ivAfter = AesCbc.zeroIv() // IV de session a déjà été mis à jour par prepare ;
        // On rejoue le chiffrement manuellement pour valider la structure attendue.
        val expectedCrc = DesfireCrc32.computeBytes(byteArrayOf(opcode.toByte()) + data)
        val plainBlock = (body + expectedCrc + ByteArray(8)).copyOf() // pad to 16
        assertEquals(16, plainBlock.size)
        val sk = sess.sessionKey
        val iv = AesCbc.zeroIv()
        AesCbc.cbcSend(sk, iv, plainBlock)
        assertContentEquals(plainBlock, cipher)
    }

    @Test
    fun full_change_key_clear_header_1() {
        // ChangeKey 0xC4 : KeyNo (1 o) clair, 16 o de nouvelle clé chiffrés (+ CRC + pad)
        val sess = session()
        val header = byteArrayOf(0x00) // keyNo 0
        val newKey = Hex.decode("0123456789ABCDEFFEDCBA9876543210")
        val data = header + newKey
        val opcode = 0xC4

        val out = sess.prepareCommand(opcode, data, CommMode.FULL, clearHeaderLength = 1)
        assertEquals(0x00, out[0].toInt() and 0xFF)

        val cipher = out.copyOfRange(1, out.size)
        // body 16 + crc 4 = 20 → pad to 32
        assertEquals(32, cipher.size)

        val expectedCrc = DesfireCrc32.computeBytes(byteArrayOf(opcode.toByte()) + data)
        val toEnc = newKey + expectedCrc
        val rem = toEnc.size % 16
        val pad = if (rem == 0) 0 else 16 - rem
        val plain = toEnc + ByteArray(pad)
        val sk = sess.sessionKey
        val iv = AesCbc.zeroIv()
        AesCbc.cbcSend(sk, iv, plain)
        assertContentEquals(plain, cipher)
    }

    @Test
    fun full_clear_header_zero_encrypts_entire_data() {
        val sess = session()
        val data = Hex.decode("AABBCCDD")
        val opcode = 0x51 // style « tout chiffré » (hors ReadData TX)
        val out = sess.prepareCommand(opcode, data, CommMode.FULL, clearHeaderLength = 0)

        // 4 + 4 crc = 8 → pad 16
        assertEquals(16, out.size)
        val expectedCrc = DesfireCrc32.computeBytes(byteArrayOf(opcode.toByte()) + data)
        val plain = (data + expectedCrc + ByteArray(8)).copyOf()
        val sk = sess.sessionKey
        val iv = AesCbc.zeroIv()
        AesCbc.cbcSend(sk, iv, plain)
        assertContentEquals(plain, out)
    }

    @Test
    fun full_rejects_clear_header_out_of_range() {
        val sess = session()
        val data = Hex.decode("010203")
        assertFailsWith<IllegalArgumentException> {
            sess.prepareCommand(0x3D, data, CommMode.FULL, clearHeaderLength = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            sess.prepareCommand(0x3D, data, CommMode.FULL, clearHeaderLength = -1)
        }
    }

    @Test
    fun full_default_clear_header_is_zero() {
        // Signature rétro-compatible : prepareCommand(op, data, FULL) == clearHeaderLength=0
        val sessA = session()
        val sessB = session()
        val data = Hex.decode("CAFEBABE")
        val a = sessA.prepareCommand(0x3D, data, CommMode.FULL)
        val b = sessB.prepareCommand(0x3D, data, CommMode.FULL, clearHeaderLength = 0)
        assertContentEquals(a, b)
        assertTrue(a.size % 16 == 0)
    }
}
