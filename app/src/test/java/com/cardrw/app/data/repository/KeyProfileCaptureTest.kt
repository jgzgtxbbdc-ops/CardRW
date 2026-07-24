package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.MaterialRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyProfileCaptureTest {

    private fun slot(
        aid: String,
        keyNo: Int,
        bytes: ByteArray,
        vaultId: String? = null,
    ) = KeyProfileCapture.SessionSlot(aid, keyNo, vaultId, bytes)

    @Test
    fun factory_becomes_FactoryZero() {
        val plan = KeyProfileCapture.plan(
            slots = listOf(slot("000000", 0, ByteArray(16))),
            knownVaultIds = emptySet(),
            existingVaultNames = emptyList(),
        )
        assertEquals(1, plan.size)
        assertEquals(KeyProfileCapture.MaterialPlan.FactoryZero, plan[0].plan)
        assertEquals(BindingScope.Picc, plan[0].scope)
    }

    @Test
    fun existing_vault_reused() {
        val material = ByteArray(16) { 0x11 }
        val plan = KeyProfileCapture.plan(
            slots = listOf(slot("F40101", 1, material, vaultId = "v1")),
            knownVaultIds = setOf("v1"),
            existingVaultNames = listOf("SiteA-Read"),
        )
        assertEquals(
            KeyProfileCapture.MaterialPlan.UseVault("v1"),
            plan.single().plan,
        )
    }

    @Test
    fun missing_vault_plans_create() {
        val material = ByteArray(16) { 0x22 }
        val plan = KeyProfileCapture.plan(
            slots = listOf(slot("F40101", 2, material, vaultId = "gone")),
            knownVaultIds = emptySet(),
            existingVaultNames = emptyList(),
        )
        val create = plan.single().plan as KeyProfileCapture.MaterialPlan.CreateVault
        assertTrue(create.suggestedName.contains("F40101") || create.suggestedName.contains("k2"))
        assertEquals(32, create.fingerprint.length)
    }

    @Test
    fun same_material_two_slots_one_create_fingerprint() {
        val material = ByteArray(16) { 0x33 }
        val plan = KeyProfileCapture.plan(
            slots = listOf(
                slot("000000", 0, material),
                slot("F40101", 0, material.copyOf()),
            ),
            knownVaultIds = emptySet(),
            existingVaultNames = emptyList(),
        )
        val creates = plan.map { it.plan }.filterIsInstance<KeyProfileCapture.MaterialPlan.CreateVault>()
        assertEquals(2, creates.size)
        assertEquals(creates[0].fingerprint, creates[1].fingerprint)
        assertEquals(creates[0].suggestedName, creates[1].suggestedName)
        assertEquals(
            1,
            KeyProfileCapture.countNeedingVaultCreate(
                plan,
                plan.map { it.selectionKey }.toSet(),
            ),
        )
    }

    @Test
    fun toBindings_resolves_create_map() {
        val material = ByteArray(16) { 0x44 }
        val plan = KeyProfileCapture.plan(
            slots = listOf(
                slot("000000", 0, ByteArray(16)),
                slot("F40101", 1, material),
            ),
            knownVaultIds = emptySet(),
            existingVaultNames = emptyList(),
        )
        val selected = plan.map { it.selectionKey }.toSet()
        val fp = (plan[1].plan as KeyProfileCapture.MaterialPlan.CreateVault).fingerprint
        val bindings = KeyProfileCapture.toBindings(
            planned = plan,
            selectedKeys = selected,
            vaultIdByFingerprint = mapOf(fp to "new-vault"),
        )
        assertEquals(2, bindings.size)
        assertEquals(MaterialRef.FactoryZero, bindings[0].materialRef)
        assertEquals(MaterialRef.VaultEntry("new-vault"), bindings[1].materialRef)
        assertEquals(BindingScope.Application("F40101"), bindings[1].scope)
    }

    @Test
    fun suggestProfileName_from_single_app() {
        val name = KeyProfileCapture.suggestProfileName(
            slots = listOf(
                slot("000000", 0, ByteArray(16)),
                slot("F40101", 1, ByteArray(16) { 1 }),
            ),
            existingProfileNames = emptyList(),
        )
        assertEquals("Site F40101", name)
    }

    @Test
    fun suggestProfileName_collision_falls_back() {
        val name = KeyProfileCapture.suggestProfileName(
            slots = listOf(slot("F40101", 0, ByteArray(16) { 1 })),
            existingProfileNames = listOf("Site F40101"),
        )
        assertEquals("Profil 1", name)
    }

    @Test
    fun selection_filters_bindings() {
        val plan = KeyProfileCapture.plan(
            slots = listOf(
                slot("000000", 0, ByteArray(16)),
                slot("F40101", 1, ByteArray(16) { 2 }),
            ),
            knownVaultIds = emptySet(),
            existingVaultNames = emptyList(),
        )
        val onlyPicc = setOf(plan[0].selectionKey)
        val fp = (plan[1].plan as KeyProfileCapture.MaterialPlan.CreateVault).fingerprint
        val bindings = KeyProfileCapture.toBindings(
            plan,
            onlyPicc,
            mapOf(fp to "x"),
        )
        assertEquals(1, bindings.size)
        assertEquals(BindingScope.Picc, bindings[0].scope)
    }
}
