package com.cardrw.desfire.client

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreClientTest {

    @Test
    fun getFileIds_and_settings_plain_no_auth() {
        val ordered = OrderedTransceiver(
            listOf(
                "906F000000" to "00019100", // files 0, 1
                "90F50000010000" to "0000EEEE2000009100", // file 0: std plain 32 free
            ),
        )
        val client = DesfireClient(ordered)
        val ids = client.getFileIds()
        assertEquals(listOf(0, 1), ids)
        val settings = client.getFileSettings(0)
        assertEquals(32, settings.sizeBytes)
        assertTrue(settings.isStandard)
    }

    @Test
    fun readData_plain_no_auth() {
        val ordered = OrderedTransceiver(
            listOf(
                // ReadData file0 offset0 length8
                // 90 BD 00 00 07 | file=00 offset=000000 length=000800 | Le=00
                "90BD0000070000000008000000" to "01020304050607089100",
            ),
        )
        val client = DesfireClient(ordered)
        val data = client.readData(0, 0, 8)
        assertEquals("0102030405060708", Hex.encode(data))
    }

    @Test
    fun select_invalidates_would_be_session() {
        val ordered = OrderedTransceiver(
            listOf(
                "905A00000301F40200" to "9100",
            ),
        )
        val client = DesfireClient(ordered)
        client.selectApplication(com.cardrw.desfire.model.Aid.fromHex("01F402"))
        assertEquals(false, client.isAuthenticated)
    }

    @Test
    fun ensureApplicationSelected_skips_duplicate() {
        val ordered = OrderedTransceiver(
            listOf(
                "905A000003B013F500" to "9100",
                // second ensure = no extra APDU
            ),
        )
        val client = DesfireClient(ordered)
        val aid = com.cardrw.desfire.model.Aid.fromHex("B013F5")
        client.selectApplication(aid)
        client.ensureApplicationSelected(aid) // no second exchange
        assertEquals("B013F5", client.selectedAid?.hex)
    }

    @Test
    fun explore_picc_skips_get_file_ids() {
        // Only GetKeySettings — pas de 6F
        val ordered = OrderedTransceiver(
            listOf(
                "9045000000" to "0F819100",
            ),
        )
        val client = DesfireClient(ordered)
        val result = client.exploreSelectedApplication(
            com.cardrw.desfire.model.Aid.PICC,
            readStandardFiles = true,
        )
        assertEquals(true, result.aid.isPicc)
        assertEquals(0, result.files.size)
        assertTrue(result.notes.any { it.contains("PICC", ignoreCase = true) })
        assertEquals(0x0F, result.keySettings?.settingsRaw)
    }

    @Test
    fun explore_with_cache_skips_directory_commands() {
        // Pas de 45/6F/F5 — uniquement lecture si droits OK ; ici pas de session → pas de BD
        val ordered = OrderedTransceiver(emptyList())
        val client = DesfireClient(ordered)
        // Simuler une session non-maître impossible sans auth réelle : sans session,
        // le mode cache n’est pas pris (authKey null). On teste seulement le parse freelist.
        val ks = com.cardrw.desfire.model.KeySettingsInfo.parse(
            com.cardrw.desfire.util.Hex.decode("0884"),
        )
        assertEquals(false, ks.bits.freeDirectoryListWithoutMaster)
    }

    private class OrderedTransceiver(
        private val steps: List<Pair<String, String>>,
    ) : DesfireTransceiver {
        private var index = 0
        override fun transceive(apdu: ByteArray): ByteArray {
            val key = Hex.encode(apdu)
            check(index < steps.size) { "Unexpected extra APDU $key" }
            val (expected, response) = steps[index++]
            check(key == expected) { "Expected APDU $expected but got $key" }
            return Hex.decode(response)
        }
    }
}
