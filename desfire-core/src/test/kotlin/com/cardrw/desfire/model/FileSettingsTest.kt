package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSettingsTest {

    @Test
    fun parse_standard_plain_free() {
        // type=00, comm=00, rights=EEEE (free), size=32 (0x20 LE)
        val raw = Hex.decode("0000EEEE200000")
        val fs = FileSettings.parse(0, raw)
        assertEquals(FileType.STANDARD, fs.fileType)
        assertEquals(CommMode.PLAIN, fs.commMode)
        assertEquals(32, fs.sizeBytes)
        assertEquals(0x0E, fs.accessRights.read)
        assertEquals("Free", fs.accessRights.readLabel)
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
        assertEquals("Clé 0", fs.accessRights.readLabel)
    }

    @Test
    fun access_rights_never() {
        val ar = AccessRights.parse(0xF0F0)
        assertEquals(0x0F, ar.read)
        assertEquals("Never", ar.readLabel)
        assertEquals(0x00, ar.write)
        assertEquals(0x0F, ar.readWrite)
        assertEquals(0x00, ar.change)
    }

    @Test
    fun can_read_with_key_rights() {
        // R=2 W=0 RW=1 Ch=2  (0x2012) — cas labo B013F5 fichier 0
        val ar = AccessRights.parse(0x2012)
        assertEquals(2, ar.read)
        assertEquals(0, ar.write)
        assertEquals(1, ar.readWrite)
        assertEquals(false, ar.canReadWith(0)) // master ≠ passe-droit lecture
        assertEquals(true, ar.canReadWith(1)) // RW
        assertEquals(true, ar.canReadWith(2)) // R
        assertEquals(false, ar.canReadWith(null))
        assertEquals(true, AccessRights.parse(0xEEEE).canReadWith(null)) // Free
    }

    @Test
    fun free_write_uses_plain_even_if_file_full() {
        // Create lab Free 0xEEEE + FULL wire → write effectif PLAIN (sinon 0x7E)
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
        // Clé W dédiée → FULL
        val protected = AccessRights.parse(0x2000) // R=2 W=0 ...
        // 0x2000: R=2, W=0, RW=0, Ch=0
        val fs2 = fs.copy(accessRights = AccessRights.parse(0x2000))
        assertEquals(CommMode.FULL, fs2.effectiveCommModeForWrite(sessionKeyNo = 0))
        assertEquals(CommMode.PLAIN, fs2.effectiveCommModeForWrite(sessionKeyNo = 1))
    }

    @Test
    fun key_settings_parse() {
        val info = KeySettingsInfo.parse(Hex.decode("0F81"))
        assertEquals(0x0F, info.settingsRaw)
        assertEquals(1, info.maxKeys) // nibble bas — selon carte peut être nb clés
        assertTrue(info.bits.allowMasterKeyChange)
        assertTrue(info.bits.configurationChangeable)
    }
}
