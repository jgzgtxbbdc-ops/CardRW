package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSettingsTest {

    @Test
    fun parse_standard_plain_free() {
        // type=00, comm=00, rights=EEEE (LE=BE), size=32 (0x20 LE)
        val raw = Hex.decode("0000EEEE200000")
        val fs = FileSettings.parse(0, raw)
        assertEquals(FileType.STANDARD, fs.fileType)
        assertEquals(CommMode.PLAIN, fs.commMode)
        assertEquals(32, fs.sizeBytes)
        assertEquals(0x0E, fs.accessRights.read)
        assertEquals("free", fs.accessRights.readLabel)
        assertEquals("r:free w:free rw:free ch:free", fs.accessRights.compactLabel)
        assertTrue(fs.isStandard)
    }

    @Test
    fun parse_standard_full_key0() {
        // type=00, comm=03, rights=0000 (all key 0), size=256
        val raw = Hex.decode("00030000000100")
        val fs = FileSettings.parse(1, raw)
        assertEquals(CommMode.FULL, fs.commMode)
        assertEquals(256, fs.sizeBytes)
        assertEquals(0, fs.accessRights.read)
        assertEquals("k0", fs.accessRights.readLabel)
        assertEquals("F1 · Std · 256B · FULL · r:0 w:0 rw:0 ch:0", fs.compactLine)
    }

    @Test
    fun access_rights_wire_little_endian_lab_card() {
        // Wire terrain GetFileSettings : 20 12 (comme journal B013F5 / B313F5)
        // freefare le16toh → logique 0x1220 → R=1 W=2 RW=2 Ch=0
        val ar = AccessRights.parse(Hex.decode("2012"), 0)
        assertEquals(0x1220, ar.raw)
        assertEquals(1, ar.read)
        assertEquals(2, ar.write)
        assertEquals(2, ar.readWrite)
        assertEquals(0, ar.change)
        assertEquals("r:1 w:2 rw:2 ch:0", ar.compactLabel)
        assertTrue(ar.canReadWith(1)) // R
        assertTrue(ar.canReadWith(2)) // RW
        assertFalse(ar.canReadWith(0))
        assertTrue(ar.canWriteWith(2)) // W et RW
        assertFalse(ar.canWriteWith(0))
        assertFalse(ar.canWriteWith(1))
    }

    @Test
    fun access_rights_logical_2012_roundtrip_wire() {
        // Logique R=2 W=0 RW=1 Ch=2 → wire LE 12 20
        val wire = AccessRights.toWireLe(0x2012)
        assertEquals("1220", Hex.encode(wire))
        val ar = AccessRights.parse(wire, 0)
        assertEquals(2, ar.read)
        assertEquals(0, ar.write)
        assertEquals(1, ar.readWrite)
        assertEquals(2, ar.change)
    }

    @Test
    fun access_rights_never() {
        val ar = AccessRights.parse(0xF0F0)
        assertEquals(0x0F, ar.read)
        assertEquals("never", ar.readLabel)
        assertEquals(0x00, ar.write)
        assertEquals(0x0F, ar.readWrite)
        assertEquals(0x00, ar.change)
    }

    @Test
    fun free_write_uses_plain_even_if_file_full() {
        val rights = AccessRights.parse(0xEEEE)
        val fs = FileSettings(
            fileNo = 15,
            fileType = FileType.STANDARD,
            commMode = CommMode.FULL,
            accessRights = rights,
            sizeBytes = 16,
            raw = byteArrayOf(0),
        )
        assertEquals(CommMode.PLAIN, fs.effectiveCommModeForWrite(sessionKeyNo = 0))
        assertEquals(CommMode.PLAIN, fs.effectiveCommModeForWrite(sessionKeyNo = null))
        // Logique R=2 W=0 → wire would be 00 20 for 0x2000
        val fs2 = fs.copy(accessRights = AccessRights.parse(0x2000))
        assertEquals(CommMode.FULL, fs2.effectiveCommModeForWrite(sessionKeyNo = 0))
        assertEquals(CommMode.PLAIN, fs2.effectiveCommModeForWrite(sessionKeyNo = 1))
    }

    @Test
    fun key_settings_parse() {
        val info = KeySettingsInfo.parse(Hex.decode("0F81"))
        assertEquals(0x0F, info.settingsRaw)
        assertEquals(1, info.maxKeys)
        assertTrue(info.bits.allowMasterKeyChange)
        assertTrue(info.bits.configurationChangeable)
    }
}
