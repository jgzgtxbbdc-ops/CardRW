package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.desfire.dump.CardDumpDocument
import com.cardrw.desfire.dump.DumpApplication
import com.cardrw.desfire.dump.DumpCardSection
import com.cardrw.desfire.dump.DumpDataSection
import com.cardrw.desfire.dump.DumpFileMeta
import com.cardrw.desfire.dump.DumpKeySettings
import com.cardrw.desfire.dump.DumpRestorePlanner
import com.cardrw.desfire.dump.DumpStructureSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreMaterialPlannerTest {

    private val siteA = KeyProfile(
        id = "p1",
        displayName = "Site A",
        createdAt = 1L,
        updatedAt = 1L,
        allowFactoryFallback = false,
        bindings = listOf(
            KeyBinding(BindingScope.Picc, 0, MaterialRef.VaultEntry("vault-picc"), required = true),
            KeyBinding(BindingScope.Application("F00102"), 0, MaterialRef.VaultEntry("vault-m")),
            KeyBinding(BindingScope.Application("F00102"), 2, MaterialRef.VaultEntry("vault-w"), roleHint = "write"),
        ),
    )

    private fun dump(writeRights: Int = 0x1220) = CardDumpDocument(
        createdAt = "2026-01-01T00:00:00Z",
        appVersion = "test",
        note = "t",
        card = DumpCardSection(typeLabel = "EV3", uidTag = "01020304", uidKind = "FIXED"),
        structure = DumpStructureSection(
            applications = listOf(
                DumpApplication(
                    aid = "F00102",
                    explored = true,
                    keySettings = DumpKeySettings(settingsRaw = 0x0F, maxKeys = 3),
                    files = listOf(
                        DumpFileMeta(
                            fileNo = 0,
                            type = "Std",
                            commMode = "FULL",
                            sizeBytes = 16,
                            accessRights = "r:1 w:2 rw:2 ch:0",
                            accessRightsRaw = writeRights,
                            commModeWire = 0x03,
                            dataStatus = "read",
                        ),
                    ),
                ),
            ),
        ),
        data = DumpDataSection(files = mapOf("F00102/0" to "AABB")),
    )

    @Test
    fun complete_profile_all_ready() {
        val plan = DumpRestorePlanner.plan(dump(), formatFirst = true)
        val report = RestoreMaterialPlanner.check(
            plan = plan,
            profile = siteA,
            knownVaultIds = setOf("vault-picc", "vault-m", "vault-w"),
        )
        assertEquals(0, report.blockingCount)
        assertTrue(report.checks.all { it.ready })
        assertTrue(report.summaryLine().contains("Site A"))
    }

    @Test
    fun missing_write_key_is_warning_not_blocking() {
        val plan = DumpRestorePlanner.plan(dump())
        val report = RestoreMaterialPlanner.check(
            plan = plan,
            profile = siteA.copy(bindings = siteA.bindings.filter { it.keyNo != 2 }),
            knownVaultIds = setOf("vault-picc", "vault-m"),
        )
        val write = report.checks.first { it.label.contains("WriteData") }
        assertFalse(write.ready)
        assertFalse(write.blocking)
        assertTrue(write.detail.contains("k2"))
    }

    @Test
    fun missing_picc_master_blocks() {
        val plan = DumpRestorePlanner.plan(dump(), formatFirst = true)
        val report = RestoreMaterialPlanner.check(
            plan = plan,
            profile = siteA.copy(bindings = siteA.bindings.filter { it.scope !is BindingScope.Picc }),
            knownVaultIds = setOf("vault-m", "vault-w"),
        )
        assertTrue(report.blockingCount >= 1)
        val picc = report.checks.first { it.label.contains("PICC") }
        assertFalse(picc.ready)
        assertTrue(picc.blocking)
    }

    @Test
    fun factory_fallback_covers_missing_bindings() {
        val plan = DumpRestorePlanner.plan(dump())
        val report = RestoreMaterialPlanner.check(
            plan = plan,
            profile = siteA.copy(allowFactoryFallback = true, bindings = emptyList()),
        )
        assertEquals(0, report.blockingCount)
        assertTrue(report.checks.all { it.ready })
    }

    @Test
    fun write_free_uses_app_master() {
        val plan = DumpRestorePlanner.plan(dump(writeRights = 0xEEEE))
        val report = RestoreMaterialPlanner.check(
            plan = plan,
            profile = siteA,
            knownVaultIds = setOf("vault-picc", "vault-m", "vault-w"),
        )
        val write = report.checks.first { it.label.contains("WriteData") }
        assertTrue(write.ready)
        assertTrue(write.detail.contains("k0"))
    }
}
