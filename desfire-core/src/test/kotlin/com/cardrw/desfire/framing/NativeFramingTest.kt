package com.cardrw.desfire.framing

import com.cardrw.desfire.command.DesfireCommand
import com.cardrw.desfire.status.DesfireStatus
import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFramingTest {

    @Test
    fun wrap_getVersion_noPayload() {
        val apdu = NativeFraming.wrap(DesfireCommand.GET_VERSION)
        // 90 60 00 00 00
        assertEquals("9060000000", Hex.encode(apdu))
    }

    @Test
    fun wrap_selectApplication_withAid() {
        val aid = Hex.decode("01F402")
        val apdu = NativeFraming.wrap(DesfireCommand.SELECT_APPLICATION, aid)
        assertEquals("905A00000301F40200", Hex.encode(apdu))
    }

    @Test
    fun parse_success_empty() {
        val raw = Hex.decode("9100")
        val r = NativeFraming.parseResponse(raw)
        assertTrue(r.isSuccess)
        assertEquals(DesfireStatus.SUCCESS, r.status)
        assertEquals(0, r.data.size)
        assertTrue(r.parseOk)
    }

    @Test
    fun parse_additionalFrame_withData() {
        val raw = Hex.decode("01020391AF")
        val r = NativeFraming.parseResponse(raw)
        assertTrue(r.isAdditionalFrame)
        assertEquals("010203", r.dataHex)
    }

    @Test
    fun parse_short_frame_still_captured() {
        val raw = byteArrayOf(0x00)
        val r = NativeFraming.parseResponse(raw)
        assertFalse(r.parseOk)
        assertEquals(1, r.raw.size)
    }
}
