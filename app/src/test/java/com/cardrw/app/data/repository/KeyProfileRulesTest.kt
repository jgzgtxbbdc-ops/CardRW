package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.KeyProfilesFile
import com.cardrw.app.data.model.MaterialRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyProfileRulesTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun displayName_valid_and_length() {
        assertTrue(KeyProfileRules.isValidDisplayName("Site A"))
        assertTrue(KeyProfileRules.isValidDisplayName("  Labo  "))
        assertFalse(KeyProfileRules.isValidDisplayName(""))
        assertFalse(KeyProfileRules.isValidDisplayName("   "))
        assertFalse(KeyProfileRules.isValidDisplayName("x".repeat(41)))
        assertTrue(KeyProfileRules.isValidDisplayName("x".repeat(40)))
    }

    @Test
    fun nextDefaultName_fills_holes() {
        assertEquals("Profil 1", KeyProfileRules.nextDefaultName(emptyList()))
        assertEquals("Profil 2", KeyProfileRules.nextDefaultName(listOf("Profil 1")))
        assertEquals("Profil 1", KeyProfileRules.nextDefaultName(listOf("Site A", "Profil 2")))
    }

    @Test
    fun name_compare_case_insensitive() {
        assertEquals(
            KeyProfileRules.normalizeForCompare("Site A"),
            KeyProfileRules.normalizeForCompare("site a"),
        )
    }

    @Test
    fun keyNo_range() {
        assertTrue(KeyProfileRules.isValidKeyNo(0))
        assertTrue(KeyProfileRules.isValidKeyNo(13))
        assertFalse(KeyProfileRules.isValidKeyNo(-1))
        assertFalse(KeyProfileRules.isValidKeyNo(14))
    }

    @Test
    fun normalizeAid_accepts_spaces_and_case() {
        assertEquals("F40101", KeyProfileRules.normalizeAidHex("f4 01 01"))
        assertEquals("000000", KeyProfileRules.normalizeAidHex("000000"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeAid_rejects_short() {
        KeyProfileRules.normalizeAidHex("F401")
    }

    @Test
    fun uniqueness_scope_keyNo_same_scope_duplicate() {
        val bindings = listOf(
            KeyBinding(BindingScope.Picc, 0, MaterialRef.FactoryZero),
            KeyBinding(BindingScope.Picc, 0, MaterialRef.VaultEntry("v1")),
        )
        try {
            KeyProfileRules.validateAndNormalizeBindings(bindings)
            fail("expected duplicate (PICC, k0)")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("déjà défini") || e.message!!.contains("PICC"))
        }
    }

    @Test
    fun uniqueness_allows_same_keyNo_different_scope() {
        val bindings = listOf(
            KeyBinding(BindingScope.Picc, 0, MaterialRef.FactoryZero, roleHint = "master"),
            KeyBinding(
                BindingScope.Application("f40101"),
                0,
                MaterialRef.VaultEntry("vault-app"),
                roleHint = "master",
            ),
            KeyBinding(
                BindingScope.Application("F40101"),
                1,
                MaterialRef.VaultEntry("vault-r"),
            ),
            KeyBinding(
                BindingScope.Application("F40101"),
                2,
                MaterialRef.VaultEntry("vault-w"),
            ),
        )
        val n = KeyProfileRules.validateAndNormalizeBindings(bindings)
        assertEquals(4, n.size)
        assertEquals("F40101", (n[1].scope as BindingScope.Application).aidHex)
        assertEquals(
            setOf("PICC|0", "APP|F40101|0", "APP|F40101|1", "APP|F40101|2"),
            n.map { KeyProfileRules.bindingSlotKey(it) }.toSet(),
        )
    }

    @Test
    fun uniqueness_same_keyNo_two_apps_ok() {
        val bindings = listOf(
            KeyBinding(BindingScope.Application("AAAAAA"), 1, MaterialRef.FactoryZero),
            KeyBinding(BindingScope.Application("BBBBBB"), 1, MaterialRef.FactoryZero),
        )
        assertEquals(2, KeyProfileRules.validateAndNormalizeBindings(bindings).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_rejects_bad_keyNo() {
        KeyProfileRules.validateAndNormalizeBindings(
            listOf(KeyBinding(BindingScope.Picc, 99, MaterialRef.FactoryZero)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_rejects_blank_vaultId() {
        KeyProfileRules.validateAndNormalizeBindings(
            listOf(KeyBinding(BindingScope.Picc, 0, MaterialRef.VaultEntry("  "))),
        )
    }

    @Test
    fun broken_vault_binding_detected() {
        val bindings = listOf(
            KeyBinding(BindingScope.Picc, 0, MaterialRef.VaultEntry("alive")),
            KeyBinding(BindingScope.Application("F40101"), 1, MaterialRef.VaultEntry("gone")),
            KeyBinding(BindingScope.Application("F40101"), 2, MaterialRef.FactoryZero),
        )
        val broken = KeyProfileRules.findBrokenVaultBindings(
            bindings,
            knownVaultIds = setOf("alive"),
        )
        assertEquals(1, broken.size)
        assertEquals(1, broken[0].keyNo)
        assertTrue(
            KeyProfileRules.isVaultBindingBroken(
                bindings[1],
                knownVaultIds = setOf("alive"),
            ),
        )
        assertFalse(
            KeyProfileRules.isVaultBindingBroken(
                bindings[0],
                knownVaultIds = setOf("alive"),
            ),
        )
        assertFalse(
            KeyProfileRules.isVaultBindingBroken(
                bindings[2],
                knownVaultIds = emptySet(),
            ),
        )
    }

    @Test
    fun broken_when_vault_empty_all_vault_refs() {
        val bindings = listOf(
            KeyBinding(BindingScope.Picc, 0, MaterialRef.VaultEntry("x")),
            KeyBinding(BindingScope.Picc, 1, MaterialRef.FactoryZero),
        )
        val broken = KeyProfileRules.findBrokenVaultBindings(bindings, emptySet())
        assertEquals(1, broken.size)
    }

    @Test
    fun json_roundtrip_profile_site_a() {
        val profile = KeyProfile(
            id = "p1",
            displayName = "Site A",
            notes = "atelier",
            createdAt = 1L,
            updatedAt = 2L,
            lastUsedAt = null,
            allowFactoryFallback = true,
            preferEv2 = null,
            bindings = listOf(
                KeyBinding(
                    scope = BindingScope.Picc,
                    keyNo = 0,
                    materialRef = MaterialRef.VaultEntry("vault-picc"),
                    roleHint = "master",
                    required = true,
                ),
                KeyBinding(
                    scope = BindingScope.Application("F40101"),
                    keyNo = 1,
                    materialRef = MaterialRef.VaultEntry("vault-r"),
                    roleHint = "read",
                ),
                KeyBinding(
                    scope = BindingScope.Application("F40101"),
                    keyNo = 2,
                    materialRef = MaterialRef.VaultEntry("vault-w"),
                    roleHint = "write",
                ),
            ),
        )
        val encoded = json.encodeToString(KeyProfilesFile(listOf(profile)))
        val decoded = json.decodeFromString<KeyProfilesFile>(encoded)
        assertEquals(1, decoded.profiles.size)
        val p = decoded.profiles.single()
        assertEquals("Site A", p.displayName)
        assertEquals(3, p.bindings.size)
        assertEquals(BindingScope.Picc, p.bindings[0].scope)
        assertEquals(
            MaterialRef.VaultEntry("vault-picc"),
            p.bindings[0].materialRef,
        )
        assertEquals(
            "F40101",
            (p.bindings[1].scope as BindingScope.Application).aidHex,
        )
        // Profil = références uniquement (pas d’octets secret dans le JSON)
        assertFalse(encoded.contains("\"keyBytes\""))
        assertFalse(encoded.matches(Regex("(?s).*\"[0-9a-fA-F]{32}\".*")))
    }

    @Test
    fun formatBindingLabel() {
        val b = KeyBinding(
            BindingScope.Application("F40101"),
            2,
            MaterialRef.FactoryZero,
            roleHint = "write",
        )
        assertEquals("F40101 k2 · write", KeyProfileRules.formatBindingLabel(b))
    }
}
