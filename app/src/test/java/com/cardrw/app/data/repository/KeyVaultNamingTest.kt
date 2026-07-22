package com.cardrw.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultNamingTest {

    @Test
    fun nextDefaultName_empty_isKey1() {
        assertEquals("key1", KeyVaultNaming.nextDefaultName(emptyList()))
    }

    @Test
    fun nextDefaultName_fillsHoles() {
        assertEquals("key2", KeyVaultNaming.nextDefaultName(listOf("key1", "key3")))
        assertEquals("key1", KeyVaultNaming.nextDefaultName(listOf("key2")))
    }

    @Test
    fun nextDefaultName_caseInsensitive() {
        assertEquals("key2", KeyVaultNaming.nextDefaultName(listOf("KEY1")))
    }

    @Test
    fun looksLikeKeyHex() {
        assertTrue(KeyVaultNaming.looksLikeKeyHex("00".repeat(16)))
        assertFalse(KeyVaultNaming.looksLikeKeyHex("labo-master"))
    }
}
