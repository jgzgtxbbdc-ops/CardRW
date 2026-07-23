package com.cardrw.desfire.dump

import com.cardrw.desfire.model.AccessRights
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.FileSettings
import com.cardrw.desfire.model.FileType
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.util.Hex
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardDumpBuilderTest {

    @Test
    fun build_includes_structure_and_data_no_secrets() {
        val aid = Aid.fromHex("F00102")
        val rights = AccessRights(
            read = 0x0E,
            write = 0x0E,
            readWrite = 0x0E,
            change = 0x0E,
            raw = 0xEEEE,
        )
        val settings = FileSettings(
            fileNo = 0,
            fileType = FileType.STANDARD,
            commMode = CommMode.PLAIN,
            accessRights = rights,
            sizeBytes = 16,
            raw = ByteArray(7),
        )
        val explore = ApplicationExploreResult(
            aid = aid,
            keySettings = null,
            files = listOf(
                FileNode(settings = settings, dataHex = "0011223344556677"),
            ),
        )
        val identity = CardIdentity(
            uidFromTag = Hex.decode("04112233445566"),
            uidKind = UidKind.FIXED,
            version = null,
            freeMemoryBytes = 8000,
            applications = listOf(aid),
            typeLabel = "DESFire EV1 (probable)",
        )
        val doc = CardDumpBuilder.build(
            identity = identity,
            exploreByAid = mapOf("F00102" to explore),
            appVersion = "0.5.0-test",
            createdAt = Instant.parse("2026-07-23T12:00:00Z"),
        )
        assertEquals(2, doc.formatVersion)
        assertNotNull(doc.integritySha256)
        assertEquals(64, doc.integritySha256!!.length)
        assertFalse(doc.secrets.keysIncluded)
        assertTrue(doc.secrets.keys.isEmpty())
        assertEquals(1, doc.structure.applications.size)
        assertEquals("F00102", doc.structure.applications[0].aid)
        assertEquals("0011223344556677", doc.data.files["F00102/0"])
        val json = CardDumpBuilder.toPrettyJson(doc)
        assertTrue(json.contains("format_version"))
        assertTrue(json.contains("F00102"))
        val text = CardDumpBuilder.toHumanText(doc)
        assertTrue(text.contains("AID F00102"))
    }
}
