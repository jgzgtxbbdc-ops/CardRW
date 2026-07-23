package com.cardrw.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultNamingTest {

    @Test
    fun nextDefaultName_fills_holes() {
        assertEquals("key1", KeyVaultNaming.nextDefaultName(emptyList()))
        assertEquals("key2", KeyVaultNaming.nextDefaultName(listOf("key1")))
        assertEquals("key1", KeyVaultNaming.nextDefaultName(listOf("key2", "key3")))
    }

    @Test
    fun suggestContext_picc_and_app() {
        assertEquals(
            "PICC · k0",
            KeyVaultNaming.suggestContextName(emptyList(), "000000", 0),
        )
        assertEquals(
            "B613F5 · k2 Write",
            KeyVaultNaming.suggestContextName(emptyList(), "B613F5", 2, "Write"),
        )
    }

    @Test
    fun suggestContext_collision_suffix() {
        val existing = listOf("B613F5 · k2")
        val s = KeyVaultNaming.suggestContextName(existing, "B613F5", 2)
        assertTrue(s.contains("key1") || s != "B613F5 · k2")
        assertFalse(KeyVaultNaming.looksLikeKeyHex("B613F5 · k2"))
    }
}
