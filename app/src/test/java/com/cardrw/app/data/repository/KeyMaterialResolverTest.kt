package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.desfire.crypto.AesConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMaterialResolverTest {

    private val siteA = KeyProfile(
        id = "p1",
        displayName = "Site A",
        createdAt = 1L,
        updatedAt = 1L,
        allowFactoryFallback = false,
        bindings = listOf(
            KeyBinding(
                BindingScope.Picc,
                0,
                MaterialRef.VaultEntry("vault-picc"),
                roleHint = "master",
                required = true,
            ),
            KeyBinding(
                BindingScope.Application("F40101"),
                1,
                MaterialRef.VaultEntry("vault-r"),
                roleHint = "read",
            ),
            KeyBinding(
                BindingScope.Application("F40101"),
                2,
                MaterialRef.FactoryZero,
                roleHint = "write",
            ),
        ),
    )

    private val vault = mapOf(
        "vault-picc" to ByteArray(16) { 0x11 },
        "vault-r" to ByteArray(16) { 0x22 },
    )

    private suspend fun load(id: String): ByteArray =
        vault[id]?.copyOf() ?: error("secret introuvable pour $id")

    @Test
    fun scopeForAid_picc_and_app() {
        assertEquals(BindingScope.Picc, KeyMaterialResolver.scopeForAid("000000"))
        assertEquals(
            BindingScope.Application("F40101"),
            KeyMaterialResolver.scopeForAid("f4 01 01"),
        )
    }

    @Test
    fun remembered_wins_over_profile() = runBlocking {
        val mem = ByteArray(16) { 0x55 }
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Picc,
            keyNo = 0,
            profile = siteA,
            rememberedMaterial = mem,
            rememberedVaultId = null,
            knownVaultIds = vault.keys,
            loadVault = ::load,
        )
        val ready = r as KeyMaterialResolver.ResolveResult.Ready
        assertEquals(KeyMaterialResolver.MaterialSource.REMEMBERED, ready.source)
        assertArrayEquals(mem, ready.keyBytes)
    }

    @Test
    fun profile_vault_binding() = runBlocking {
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Application("F40101"),
            keyNo = 1,
            profile = siteA,
            rememberedMaterial = null,
            knownVaultIds = vault.keys,
            loadVault = ::load,
        )
        val ready = r as KeyMaterialResolver.ResolveResult.Ready
        assertEquals(KeyMaterialResolver.MaterialSource.PROFILE, ready.source)
        assertEquals("vault-r", ready.vaultEntryId)
        assertArrayEquals(ByteArray(16) { 0x22 }, ready.keyBytes)
    }

    @Test
    fun profile_factory_binding() = runBlocking {
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Application("F40101"),
            keyNo = 2,
            profile = siteA,
            rememberedMaterial = null,
            knownVaultIds = vault.keys,
            loadVault = ::load,
        )
        val ready = r as KeyMaterialResolver.ResolveResult.Ready
        assertEquals(KeyMaterialResolver.MaterialSource.PROFILE, ready.source)
        assertArrayEquals(AesConstants.FACTORY_KEY, ready.keyBytes)
    }

    @Test
    fun no_binding_no_fallback_needs_user() = runBlocking {
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Application("F40101"),
            keyNo = 0,
            profile = siteA,
            rememberedMaterial = null,
            knownVaultIds = vault.keys,
            loadVault = ::load,
        )
        assertTrue(r is KeyMaterialResolver.ResolveResult.NeedsUser)
    }

    @Test
    fun no_binding_with_fallback_factory() = runBlocking {
        val labo = siteA.copy(allowFactoryFallback = true)
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Application("F40101"),
            keyNo = 0,
            profile = labo,
            rememberedMaterial = null,
            knownVaultIds = vault.keys,
            loadVault = ::load,
        )
        val ready = r as KeyMaterialResolver.ResolveResult.Ready
        assertEquals(KeyMaterialResolver.MaterialSource.FACTORY, ready.source)
    }

    @Test
    fun without_profile_proposes_factory() = runBlocking {
        val r = KeyMaterialResolver.resolve(
            scope = BindingScope.Picc,
            keyNo = 0,
            profile = null,
            rememberedMaterial = null,
            knownVaultIds = emptySet(),
            loadVault = ::load,
        )
        val ready = r as KeyMaterialResolver.ResolveResult.Ready
        assertEquals(KeyMaterialResolver.MaterialSource.FACTORY, ready.source)
    }

    @Test
    fun missing_vault_required_is_impossible() = runBlocking {
        val r = KeyMaterialResolver.resolveBinding(
            binding = siteA.bindings[0], // PICC k0 required
            knownVaultIds = emptySet(),
            loadVault = { error("nope") },
        )
        // knownVaultIds empty : on tente load → exception → Impossible (required)
        assertTrue(r is KeyMaterialResolver.ResolveResult.Impossible)
    }

    @Test
    fun missing_vault_optional_needs_user() = runBlocking {
        val r = KeyMaterialResolver.resolveBinding(
            binding = siteA.bindings[1], // F40101 k1 not required
            knownVaultIds = setOf("other"),
            loadVault = ::load,
        )
        assertTrue(r is KeyMaterialResolver.ResolveResult.NeedsUser)
    }

    @Test
    fun bindingsForScope_ordered_master_first() {
        val list = KeyMaterialResolver.bindingsForScope(
            siteA,
            BindingScope.Application("F40101"),
        )
        assertEquals(listOf(1, 2), list.map { it.keyNo })
    }

    @Test
    fun allowsFactoryFallback() {
        assertTrue(KeyMaterialResolver.allowsFactoryFallback(null))
        assertTrue(KeyMaterialResolver.allowsFactoryFallback(siteA.copy(allowFactoryFallback = true)))
        assertTrue(!KeyMaterialResolver.allowsFactoryFallback(siteA))
    }
}
