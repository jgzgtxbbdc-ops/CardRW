package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionInfoTest {

    /**
     * Vecteur synthétique aligné sur la structure libfreefare / nfc-tools (28 octets).
     * Remplacer par captures labo réelles dès réception des cartes (CDC §14).
     */
    private val syntheticEv3 = Hex.decode(
        buildString {
            // HW: NXP, type 1, sub 1, ver 3.0, storage 0x1A (~8K), proto 5
            append("04")
            append("01")
            append("01")
            append("03")
            append("00")
            append("1A")
            append("05")
            // SW: same family, ver 3.0
            append("04")
            append("01")
            append("01")
            append("03")
            append("00")
            append("1A")
            append("05")
            // UID 7 + batch 5 + week + year
            append("04A3B2C1D4E580")
            append("1122334455")
            append("0C") // week 12
            append("18") // year 2024 (0x18)
        },
    )

    @Test
    fun parse_synthetic_ev3() {
        val info = VersionInfo.parse(syntheticEv3)
        assertEquals(0x04, info.hardwareVendorId)
        assertEquals(3, info.hardwareVersionMajor)
        assertEquals(3, info.softwareVersionMajor)
        assertEquals("04A3B2C1D4E580", info.uidHex)
        assertEquals(CardFamily.DESFIRE_EV3, info.cardFamily)
        assertTrue(info.typeLabel.contains("EV3"))
    }

    @Test
    fun storage_size_decode() {
        // 0x1A = 26 → n=26 → 2^13 = 8192
        assertEquals(8192, VersionInfo.decodeStorageSize(0x1A))
    }
}
