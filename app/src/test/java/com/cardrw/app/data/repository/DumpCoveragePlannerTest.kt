package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.desfire.model.AccessRights
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.FileSettings
import com.cardrw.desfire.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpCoveragePlannerTest {

    private val aid = Aid.fromHex("F40101")

    private fun rights(read: Int, write: Int = 0, rw: Int = 14, change: Int = 0) = AccessRights(
        read = read,
        write = write,
        readWrite = rw,
        change = change,
        raw = (read shl 12) or (write shl 8) or (rw shl 4) or change,
    )

    private fun stdFile(no: Int, rights: AccessRights, dataHex: String? = null) = FileNode(
        settings = FileSettings(
            fileNo = no,
            fileType = FileType.STANDARD,
            commMode = CommMode.FULL,
            accessRights = rights,
            sizeBytes = 16,
            raw = ByteArray(7),
        ),
        dataHex = dataHex,
    )

    private val siteA = KeyProfile(
        id = "p1",
        displayName = "Site A",
        createdAt = 1L,
        updatedAt = 1L,
        allowFactoryFallback = false,
        bindings = listOf(
            KeyBinding(BindingScope.Application("F40101"), 1, MaterialRef.VaultEntry("vault-r"), roleHint = "read"),
            KeyBinding(BindingScope.Application("F40101"), 0, MaterialRef.VaultEntry("vault-m"), roleHint = "master"),
        ),
    )

    @Test
    fun free_and_already_read() {
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(
                stdFile(0, rights(read = 0x0E), dataHex = "AA"),
                stdFile(1, rights(read = 0x0E)),
            ),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = siteA,
            knownVaultIds = setOf("vault-r", "vault-m"),
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.READ, plan.rows.first { it.fileNo == 0 }.status)
        assertEquals(DumpCoveragePlanner.Status.FREE, plan.rows.first { it.fileNo == 1 }.status)
        assertEquals(0, plan.missingCount)
    }

    @Test
    fun binding_covers_read_key() {
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(stdFile(0, rights(read = 1, rw = 15))),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = siteA,
            knownVaultIds = setOf("vault-r", "vault-m"),
            includePicc = false,
        )
        val file = plan.rows.first { it.fileNo == 0 }
        assertEquals(DumpCoveragePlanner.Status.BINDING, file.status)
        assertTrue(file.detail.contains("k1"))
    }

    @Test
    fun missing_material_when_no_binding_no_fallback() {
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(stdFile(0, rights(read = 2, rw = 15))),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = siteA,
            knownVaultIds = setOf("vault-r", "vault-m"),
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.MISSING, plan.rows.first { it.fileNo == 0 }.status)
        assertTrue(plan.missingCount >= 1)
    }

    @Test
    fun factory_fallback_when_allowed() {
        val lab = siteA.copy(allowFactoryFallback = true, bindings = emptyList())
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(stdFile(0, rights(read = 2, rw = 15))),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = lab,
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.FACTORY, plan.rows.first { it.fileNo == 0 }.status)
    }

    @Test
    fun remembered_slot_counts_as_ready() {
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(stdFile(0, rights(read = 3, rw = 15))),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = siteA,
            rememberedByAid = mapOf("F40101" to setOf(3)),
            knownVaultIds = setOf("vault-r", "vault-m"),
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.BINDING, plan.rows.first { it.fileNo == 0 }.status)
    }

    @Test
    fun unexplored_app_unknown_without_master() {
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("AABBCC"),
            exploreByAid = emptyMap(),
            profile = siteA.copy(allowFactoryFallback = false),
            knownVaultIds = setOf("vault-r", "vault-m"),
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.UNKNOWN, plan.rows.single().status)
    }

    @Test
    fun never_read_is_final() {
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(stdFile(0, rights(read = 0x0F, rw = 0x0F))),
        )
        val plan = DumpCoveragePlanner.plan(
            applicationAids = listOf("F40101"),
            exploreByAid = mapOf("F40101" to explore),
            profile = siteA,
            knownVaultIds = setOf("vault-r"),
            includePicc = false,
        )
        assertEquals(DumpCoveragePlanner.Status.NEVER, plan.rows.first { it.fileNo == 0 }.status)
    }
}
