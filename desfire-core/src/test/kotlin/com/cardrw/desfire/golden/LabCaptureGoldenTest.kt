package com.cardrw.desfire.golden

import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.model.CardFamily
import com.cardrw.desfire.model.VersionInfo
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vecteur golden issu d’une capture terrain (Journal APDU, 2026-07-21).
 * Source : desfire-core/src/test/resources/golden/lab-ev3-7apps-2026-07-21.json
 */
class LabCaptureGoldenTest {

    private val exchanges = listOf(
        "9060000000" to "04010233001C0591AF",
        "90AF000000" to "04010303011C0591AF",
        "90AF000000" to "04A27F524C1C90209797303007249100",
        "905A00000300000000" to "9100",
        "906E000000" to "E039009100",
        "906A000000" to "C023F5C123F5C223F5C323F5C423F5C523F5C623F59100",
    )

    private class OrderedTx(private val steps: List<Pair<String, String>>) : DesfireTransceiver {
        private var i = 0
        override fun transceive(apdu: ByteArray): ByteArray {
            val key = Hex.encode(apdu)
            val (expected, rx) = steps[i++]
            check(key == expected) { "APDU #$i: expected $expected got $key" }
            return Hex.decode(rx)
        }
    }

    @Test
    fun parse_version_from_lab_capture() {
        val concat = Hex.decode("04010233001C0504010303011C0504A27F524C1C9020979730300724")
        val info = VersionInfo.parse(concat)
        assertEquals("04A27F524C1C90", info.uidHex)
        assertEquals(3, info.softwareVersionMajor)
        assertEquals(1, info.softwareVersionMinor)
        assertEquals(CardFamily.DESFIRE_EV3, info.cardFamily)
        assertEquals(16384, info.estimatedMemoryBytes) // storage raw 0x1C → 16K
        // Production: week 0x07, year 0x24 → BCD S07 / 2024 (pas 2036)
        assertEquals(7, info.productionWeekBcd)
        assertEquals(2024, info.productionYearFull)
        assertEquals("2024, semaine 07", info.productionLabel)
        assertTrue(info.hardwareVersionHexLabel.startsWith("33."))
    }

    @Test
    fun client_read_path_matches_lab_journal() {
        val client = DesfireClient(OrderedTx(exchanges))
        val identity = client.readIdentity(tagUid = Hex.decode("04A27F524C1C90"))

        assertEquals(CardFamily.DESFIRE_EV3, identity.version!!.cardFamily)
        assertEquals(14816, identity.freeMemoryBytes)
        assertEquals(7, identity.applications.size)
        assertEquals(
            listOf("C023F5", "C123F5", "C223F5", "C323F5", "C423F5", "C523F5", "C623F5"),
            identity.applications.map { it.hex },
        )
        assertTrue(client.journal.all.isNotEmpty())
        // Annotations compactes : pas de pavé pédagogique sur 0xAF
        val afLines = client.journal.all.filter { it.status?.isAdditionalFrame == true }
        assertTrue(afLines.all { "suite" in it.annotation.lowercase() || "0xAF" in it.annotation })
        assertTrue(afLines.none { "continuer avec" in it.annotation })
    }
}
