package com.cardrw.desfire.golden

import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.session.Ev1Session
import com.cardrw.desfire.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden versionné : dérivation session + ReadData TX PLAIN + Write FULL header=7.
 * Source : `resources/golden/ev1-auth-readdata-full-vectors.json` (vecteurs fixes labo).
 */
class Ev1AuthReadDataFullGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRoot() = json.parseToJsonElement(
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "golden/ev1-auth-readdata-full-vectors.json",
            ),
        ) { "golden missing" }.bufferedReader().readText(),
    ).jsonObject

    @Test
    fun session_key_matches_golden() {
        val root = loadRoot()
        val rndA = Hex.decode(root["rnd_a_hex"]!!.jsonPrimitive.content)
        val rndB = Hex.decode(root["rnd_b_hex"]!!.jsonPrimitive.content)
        val expected = root["session_key_hex"]!!.jsonPrimitive.content
        assertEquals(expected, Hex.encode(Ev1Session.deriveSessionKey(rndA, rndB)))
        assertEquals(
            root["auth_key_hex"]!!.jsonPrimitive.content,
            Hex.encode(AesConstants.FACTORY_KEY),
        )
    }

    @Test
    fun read_data_tx_plain_matches_golden() {
        val root = loadRoot()
        val rndA = Hex.decode(root["rnd_a_hex"]!!.jsonPrimitive.content)
        val rndB = Hex.decode(root["rnd_b_hex"]!!.jsonPrimitive.content)
        val v = root["vectors"]!!.jsonObject["read_data_tx_plain"]!!.jsonObject
        val params = Hex.decode(v["params_hex"]!!.jsonPrimitive.content)
        val expected = Hex.decode(v["tx_data_hex"]!!.jsonPrimitive.content)
        val sess = Ev1Session.create("000000", 0, rndA, rndB)
        val out = sess.prepareCommand(0xBD, params, CommMode.PLAIN)
        assertContentEquals(expected, out)
        sess.wipeSecrets()
        assertTrue(sess.sessionKey.all { it == 0.toByte() })
    }

    @Test
    fun write_data_full_header_structure() {
        val root = loadRoot()
        val rndA = Hex.decode(root["rnd_a_hex"]!!.jsonPrimitive.content)
        val rndB = Hex.decode(root["rnd_b_hex"]!!.jsonPrimitive.content)
        val v = root["vectors"]!!.jsonObject["write_data_full_header7"]!!.jsonObject
        val header = Hex.decode(v["header_hex"]!!.jsonPrimitive.content)
        val body = Hex.decode(v["body_hex"]!!.jsonPrimitive.content)
        val clearLen = v["clear_header_length"]!!.jsonPrimitive.content.toInt()
        val sess = Ev1Session.create("F00102", 0, rndA, rndB)
        val out = sess.prepareCommand(0x3D, header + body, CommMode.FULL, clearHeaderLength = clearLen)
        assertContentEquals(header, out.copyOf(clearLen))
        assertEquals(clearLen + 16, out.size) // 1 bloc cipher
        sess.wipeSecrets()
    }
}
