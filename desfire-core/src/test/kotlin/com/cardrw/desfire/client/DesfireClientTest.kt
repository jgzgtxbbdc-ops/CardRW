package com.cardrw.desfire.client

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesfireClientTest {

    // Full 28-byte payload split across 3 frames like real GetVersion
    private val fullVersion = Hex.decode(
        "04010103001A05" +
            "04010103001A05" +
            "04A3B2C1D4E580" +
            "1122334455" +
            "0C18",
    )

    @Test
    fun getVersion_chains_additional_frames() {
        val f1 = fullVersion.copyOfRange(0, 7)
        val f2 = fullVersion.copyOfRange(7, 14)
        val f3 = fullVersion.copyOfRange(14, 28)

        val ordered = OrderedTransceiver(
            listOf(
                "9060000000" to Hex.encode(f1) + "91AF",
                "90AF000000" to Hex.encode(f2) + "91AF",
                "90AF000000" to Hex.encode(f3) + "9100",
            ),
        )
        val client = DesfireClient(ordered)
        val info = client.getVersion()
        assertEquals("04A3B2C1D4E580", info.uidHex)
        assertEquals(3, info.softwareVersionMajor)
        assertTrue(client.journal.all.size >= 6) // 3 out + 3 in
    }

    @Test
    fun getApplicationIds_and_select() {
        val ordered = OrderedTransceiver(
            listOf(
                "906A000000" to "01F40201F4039100",
                "905A00000301F40200" to "9100",
            ),
        )
        val client = DesfireClient(ordered)
        val aids = client.getApplicationIds()
        assertEquals(listOf("01F402", "01F403"), aids.map { it.hex })
        client.selectApplication(aids.first())
        assertEquals(4, client.journal.all.size)
    }

    @Test
    fun getFreeMemory_little_endian() {
        // 0x001068 = 4200
        val ordered = OrderedTransceiver(
            listOf("906E000000" to "6810009100"),
        )
        val client = DesfireClient(ordered)
        assertEquals(0x1068, client.getFreeMemory())
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
